package org.example.test.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ScanInfo(val id: String, val frames: Int, val dir: File)

/** Where raw scan captures live: filesDir/scans/<id>/{meta.json, frames.jsonl, frames/0000.jpg ...}. */
class ScanStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "scans")

    fun create(): ScanSession {
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return ScanSession(File(root, id).also { it.mkdirs() })
    }

    fun list(): List<ScanInfo> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .sortedByDescending { it.name }
        .map { dir ->
            val n = File(dir, "frames").listFiles().orEmpty().count { it.extension == "jpg" }
            ScanInfo(dir.name, n, dir)
        }

    fun delete(info: ScanInfo) { info.dir.deleteRecursively() }

    /** Zips a scan into the cache folder (shared through FileProvider). Run off the main thread. */
    fun zip(info: ScanInfo, cacheDir: File): File {
        val out = File(cacheDir, "share").also { it.mkdirs() }.resolve("scan_${info.id}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED) // JPEGs are already compressed
            info.dir.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(info.dir.parentFile!!).path))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }
}

/** One recording. Methods are synchronized: the camera analyzer thread writes, the UI thread closes. */
class ScanSession(val dir: File) {
    private val framesDir = File(dir, "frames").also { it.mkdirs() }
    private val log = File(dir, "frames.jsonl").bufferedWriter()
    private var closed = false

    var count = 0
        private set

    @Synchronized
    fun saveFrame(
        jpeg: ByteArray,
        timestampNanos: Long,
        rotation: FloatArray,
        rotationDegrees: Int,
        sharpness: Float,
    ): Int {
        if (closed) return count
        val name = "%04d.jpg".format(count)
        File(framesDir, name).writeBytes(jpeg)
        val line = JSONObject()
            .put("file", "frames/$name")
            .put("t_ns", timestampNanos)
            .put("rot_deg", rotationDegrees)
            .put("sharp", sharpness.toDouble())
            .put("R", JSONArray(rotation.map { it.toDouble() })) // device->world, row-major
        log.write(line.toString()); log.newLine(); log.flush()
        return ++count
    }

    @Synchronized
    fun writeMeta(meta: JSONObject) {
        File(dir, "meta.json").writeText(meta.toString(2))
    }

    @Synchronized
    fun close() {
        if (!closed) { closed = true; log.close() }
    }
}
