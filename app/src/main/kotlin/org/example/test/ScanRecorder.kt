package org.example.test

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.test.data.ScanSession
import org.example.test.data.ScanStore
import org.example.test.data.WifiAp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Stage 1 of the scanner: records sharp, well-spaced keyframes plus the data
 * the offline reconstruction needs (phone orientation per frame, camera
 * intrinsics, Wi-Fi fingerprint of the shop).
 */
class ScanRecorder(
    private val context: Context,
    private val store: ScanStore,
    private val tracker: OrientationTracker,
) : ImageAnalysis.Analyzer {

    var recording by mutableStateOf(false)
        private set
    var frames by mutableIntStateOf(0)
        private set
    var hint by mutableStateOf("Tap Start, then walk slowly around the shop.")
        private set

    /** 3 pitch bands (row 0 = up, 1 = level, 2 = down) x [YAW_SECTORS] directions. */
    val coverage = mutableStateListOf<Boolean>().apply { repeat(PITCH_BANDS * YAW_SECTORS) { add(false) } }

    private val lock = Any()
    private var session: ScanSession? = null
    private var meta = JSONObject()
    private var lastSavedNs = 0L
    private var sharpAvg = 0f
    private var intrinsicsDone = false
    private val rot = FloatArray(9)

    fun start(wifi: List<WifiAp>) = synchronized(lock) {
        val s = store.create()
        session = s
        frames = 0
        for (i in coverage.indices) coverage[i] = false
        lastSavedNs = 0L; sharpAvg = 0f; intrinsicsDone = false
        meta = JSONObject()
            .put("created_ms", System.currentTimeMillis())
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put(
                "wifi",
                JSONArray(
                    wifi.sortedByDescending { it.rssi }.take(10).map {
                        JSONObject().put("ssid", it.ssid).put("bssid", it.bssid).put("rssi", it.rssi)
                    },
                ),
            )
        s.writeMeta(meta)
        recording = true
        hint = "Recording: walk slowly, sweep walls, corners and shelves."
    }

    fun stop(message: String = "Saved. You can start a new scan or leave.") = synchronized(lock) {
        recording = false
        session?.let {
            meta.put("frames", it.count).put("ended_ms", System.currentTimeMillis())
            it.writeMeta(meta)
            it.close()
            hint = message
        }
        session = null
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!recording) return
            val now = image.imageInfo.timestamp
            if (now - lastSavedNs < MIN_INTERVAL_NS) return

            val sharp = sharpness(image)
            sharpAvg = if (sharpAvg == 0f) sharp else sharpAvg * 0.9f + sharp * 0.1f
            if (sharp < 0.8f * sharpAvg) { hint = "Hold the phone steadier"; return }

            val jpeg = image.toJpeg(JPEG_QUALITY)
            tracker.rawMatrix(rot)
            synchronized(lock) {
                val s = session ?: return
                if (!intrinsicsDone) {
                    meta.put("image_width", image.width).put("image_height", image.height)
                        .put("intrinsics", readIntrinsics(image.width, image.height))
                    s.writeMeta(meta)
                    intrinsicsDone = true
                }
                frames = s.saveFrame(jpeg, now, rot, image.imageInfo.rotationDegrees, sharp)
                lastSavedNs = now
                markCoverage()
                hint = "Recording: walk slowly, sweep walls, corners and shelves."
                if (frames >= MAX_FRAMES) stop("Reached $MAX_FRAMES frames. Scan saved.")
            }
        } catch (_: Exception) {
            // A failed frame must never crash the capture loop.
        } finally {
            image.close()
        }
    }

    /** Which direction the camera points, from the phone orientation (device->world, ENU). */
    private fun markCoverage() {
        val fx = -rot[2]; val fy = -rot[5]; val fz = -rot[8] // world direction of the back camera
        val yaw = atan2(fx, fy) // 0 = north
        val pitch = Math.toDegrees(asin(fz.coerceIn(-1f, 1f).toDouble()))
        val col = (((yaw + PI) / (2 * PI)) * YAW_SECTORS).toInt().coerceIn(0, YAW_SECTORS - 1)
        val row = when { pitch > 20 -> 0; pitch < -20 -> 2; else -> 1 }
        coverage[row * YAW_SECTORS + col] = true
    }

    fun levelCoverage(): Int = (0 until YAW_SECTORS).count { coverage[YAW_SECTORS + it] }

    /** Variance of a Laplacian-like filter on the luma plane (higher = sharper). */
    private fun sharpness(image: ImageProxy): Float {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val w = image.width; val h = image.height
        var sum = 0.0; var sumSq = 0.0; var n = 0
        var y = 4
        while (y < h - 4) {
            var x = 4
            while (x < w - 4) {
                val c = buf.get(y * rs + x).toInt() and 0xFF
                val l = buf.get(y * rs + x - 2).toInt() and 0xFF
                val r = buf.get(y * rs + x + 2).toInt() and 0xFF
                val u = buf.get((y - 2) * rs + x).toInt() and 0xFF
                val d = buf.get((y + 2) * rs + x).toInt() and 0xFF
                val lap = (4 * c - l - r - u - d).toDouble()
                sum += lap; sumSq += lap * lap; n++
                x += 4
            }
            y += 4
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    private fun readIntrinsics(w: Int, h: Int): JSONObject {
        val out = JSONObject()
        try {
            val cm = context.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: return out
            val ch = cm.getCameraCharacteristics(id)
            val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            out.put("camera_id", id)
            f?.let { out.put("focal_mm", it.toDouble()) }
            phys?.let { out.put("sensor_mm", JSONArray(listOf(it.width.toDouble(), it.height.toDouble()))) }
            active?.let { out.put("active_array_px", JSONArray(listOf(it.width(), it.height()))) }
            if (f != null && phys != null) {
                // First guess assuming the frame is the full sensor width; refined later by the solver.
                val fx = f * w / phys.width
                out.put("fx", fx.toDouble()).put("fy", fx.toDouble())
                    .put("cx", w / 2.0).put("cy", h / 2.0)
            }
        } catch (_: Exception) { }
        return out
    }

    companion object {
        const val YAW_SECTORS = 36
        const val PITCH_BANDS = 3
        const val MAX_FRAMES = 300
        private const val MIN_INTERVAL_NS = 500_000_000L // ~2 keyframes per second at most
        private const val JPEG_QUALITY = 92
    }
}

private fun ImageProxy.toJpeg(quality: Int): ByteArray {
    val w = width; val h = height
    val nv21 = ByteArray(w * h * 3 / 2)
    val yP = planes[0]; val uP = planes[1]; val vP = planes[2]
    var pos = 0
    for (row in 0 until h) {
        yP.buffer.position(row * yP.rowStride)
        yP.buffer.get(nv21, pos, w)
        pos += w
    }
    for (row in 0 until h / 2) {
        for (col in 0 until w / 2) {
            nv21[pos++] = vP.buffer.get(row * vP.rowStride + col * vP.pixelStride)
            nv21[pos++] = uP.buffer.get(row * uP.rowStride + col * uP.pixelStride)
        }
    }
    val out = ByteArrayOutputStream(w * h / 4)
    YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
    return out.toByteArray()
}
