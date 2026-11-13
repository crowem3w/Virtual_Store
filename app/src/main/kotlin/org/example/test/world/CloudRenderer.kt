package org.example.test.world

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [PointCloud] on a plain Canvas: project, bucket by colour (3-3-2 bits), draw each bucket
 * with one drawPoints call. Cheap enough for ~60k points per frame on any phone.
 */
class CloudRenderer {
    private val paints = Array(256) { b ->
        Paint().apply {
            color = Color.rgb(((b shr 5) and 7) * 255 / 7, ((b shr 2) and 7) * 255 / 7, (b and 3) * 255 / 3)
            strokeCap = Paint.Cap.SQUARE
            isAntiAlias = false
        }
    }
    private val linePaint = Paint().apply { color = Color.WHITE; strokeWidth = 3f; isAntiAlias = true }

    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var bk = IntArray(0)
    private var out = FloatArray(0)

    /**
     * [r]/[t] map the cloud's world frame to the view camera (x right, y down, z forward).
     * [back] pushes the scene away from the camera (third-person view); [rotDeg] rotates the image upright.
     */
    fun draw(
        canvas: Canvas, w: Float, h: Float, cloud: PointCloud,
        r: DoubleArray, t: DoubleArray, rotDeg: Int, back: Float, pointSize: Float, drawCameraMarker: Boolean,
    ) {
        val f = min(w, h)
        val cx = w / 2f; val cy = h / 2f
        val r0 = r[0].toFloat(); val r1 = r[1].toFloat(); val r2 = r[2].toFloat()
        val r3 = r[3].toFloat(); val r4 = r[4].toFloat(); val r5 = r[5].toFloat()
        val r6 = r[6].toFloat(); val r7 = r[7].toFloat(); val r8 = r[8].toFloat()
        val t0 = t[0].toFloat(); val t1 = t[1].toFloat(); val t2 = t[2].toFloat()

        var m = 0
        synchronized(cloud) {
            val n = cloud.count
            val stride = max(1, ceil(n / MAX_DRAWN.toDouble()).toInt())
            val cap = n / stride + 1
            if (px.size < cap) { px = FloatArray(cap); py = FloatArray(cap); bk = IntArray(cap) }
            val xyz = cloud.xyz; val rgb = cloud.rgb
            var i = 0
            while (i < n) {
                val x = xyz[3 * i]; val y = xyz[3 * i + 1]; val z = xyz[3 * i + 2]
                val zc = r6 * x + r7 * y + r8 * z + t2 + back
                if (zc > 0.15f) {
                    val xc = r0 * x + r1 * y + r2 * z + t0
                    val yc = r3 * x + r4 * y + r5 * z + t1
                    val xv: Float; val yv: Float
                    when (rotDeg) {
                        90 -> { xv = -yc; yv = xc }
                        180 -> { xv = -xc; yv = -yc }
                        270 -> { xv = yc; yv = -xc }
                        else -> { xv = xc; yv = yc }
                    }
                    val sx = cx + f * xv / zc
                    val sy = cy + f * yv / zc
                    if (sx >= 0f && sx <= w && sy >= 0f && sy <= h) {
                        val c = rgb[i]
                        val rr = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val bb = c and 0xFF
                        px[m] = sx; py[m] = sy
                        bk[m] = ((rr shr 5) shl 5) or ((gg shr 5) shl 2) or (bb shr 6)
                        m++
                    }
                }
                i += stride
            }
        }

        if (m > 0) {
            val counts = IntArray(257)
            for (q in 0 until m) counts[bk[q] + 1]++
            for (b in 0 until 256) counts[b + 1] += counts[b]
            val pos = counts.copyOf()
            if (out.size < 2 * m) out = FloatArray(2 * m)
            for (q in 0 until m) {
                val idx = pos[bk[q]]++
                out[2 * idx] = px[q]; out[2 * idx + 1] = py[q]
            }
            for (b in 0 until 256) {
                val start = counts[b]; val end = counts[b + 1]
                if (end > start) {
                    paints[b].strokeWidth = pointSize
                    canvas.drawPoints(out, 2 * start, 2 * (end - start), paints[b])
                }
            }
        }

        if (drawCameraMarker) drawMarker(canvas, w, h, f, back, rotDeg)
    }

    /** A small frustum showing where the real camera is in the third-person view. */
    private fun drawMarker(canvas: Canvas, w: Float, h: Float, f: Float, back: Float, rotDeg: Int) {
        fun proj(x: Float, y: Float, z: Float): FloatArray {
            val zc = z + back
            val xv: Float; val yv: Float
            when (rotDeg) {
                90 -> { xv = -y; yv = x }
                180 -> { xv = -x; yv = -y }
                270 -> { xv = y; yv = -x }
                else -> { xv = x; yv = y }
            }
            return floatArrayOf(w / 2f + f * xv / zc, h / 2f + f * yv / zc)
        }
        val o = proj(0f, 0f, 0f)
        val d = 0.5f
        val corners = arrayOf(proj(-0.35f, -0.26f, d), proj(0.35f, -0.26f, d), proj(0.35f, 0.26f, d), proj(-0.35f, 0.26f, d))
        for (c in corners) canvas.drawLine(o[0], o[1], c[0], c[1], linePaint)
        for (i in 0..3) {
            val a = corners[i]; val b = corners[(i + 1) % 4]
            canvas.drawLine(a[0], a[1], b[0], b[1], linePaint)
        }
    }

    companion object { private const val MAX_DRAWN = 60_000 }
}
