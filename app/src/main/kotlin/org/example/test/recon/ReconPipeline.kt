package org.example.test.recon

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a recorded scan folder (frames/0000.jpg ..., frames.jsonl, meta.json) into `model.glb` next to it.
 *
 *   1. SfM            camera poses + sparse points                    ([Sfm])
 *   2. Alignment      gravity -> +Y, floor -> y=0, camera height 1.4 m -> metres, first camera -> origin
 *   3. Depth + fusion monocular depth fitted to the sparse points, fused into a TSDF ([DepthEstimator], [VoxelGrid])
 *   4. Mesh + export  surface nets -> vertex-coloured mesh -> .glb    ([SurfaceNets], [GlbWriter])
 */
class ReconPipeline(private val context: Context, private val scanDir: File) {

    private val logLines = StringBuilder()
    private fun log(msg: String) { Log.i(TAG, msg); logLines.append(msg).append('\n') }

    /** [progress] receives (stage text, 0..1). [cancel] must throw (e.g. Job.ensureActive) when the job is cancelled. */
    fun run(progress: (String, Float) -> Unit, cancel: () -> Unit): File {
        val t0 = System.currentTimeMillis()
        check(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
        val out = File(scanDir, "model.glb")
        try {
            val meta = JSONObject(File(scanDir, "meta.json").readText())
            val frames = loadFrames()
            check(frames.size >= 8) { "Scan has only ${frames.size} frames." }

            // ---- 1. SfM
            progress("Finding camera positions", 0.02f)
            val k = intrinsics(meta, frames[0].file)
            val sfm = Sfm(frames.map { it.file }, k, ::log)
            val sr = sfm.run({ progress("Finding camera positions", 0.02f + 0.38f * it) }, cancel)

            // ---- 2. alignment
            progress("Aligning to the floor", 0.41f)
            val align = Alignment.compute(sr, frames.map { it.rotDeg }, frames.map { it.upCam }, ::log)
            log("alignment: scale=%.3f floor/camera height(raw)=%.3f up=%s".format(align.scale, align.cameraHeightRaw, align.up.joinToString { "%.2f".format(it) }))

            // ---- 3. depth + fusion
            val reg = sr.poses.indices.filter { sr.poses[it] != null }
            val stride = max(1, ceil(reg.size / MAX_FUSED_FRAMES.toDouble()).toInt())
            val use = reg.filterIndexed { i, _ -> i % stride == 0 }
            val (voxel, grid) = makeGrid(sr, align)
            log("voxel=%.3f m, fusing %d of %d registered frames".format(voxel, use.size, reg.size))

            DepthEstimator(context).use { depth ->
                var fused = 0
                for ((n, f) in use.withIndex()) {
                    cancel()
                    progress("Building 3D model (${n + 1}/${use.size})", 0.45f + 0.45f * n / use.size)
                    if (fuseFrame(f, frames[f].file, sr, align, depth, grid, k)) fused++
                }
                log("fused $fused frames, ${grid.size} voxels")
                check(fused >= 4) { "Depth could not be matched to the camera track for enough frames." }
            }

            // ---- 4. mesh + export
            cancel()
            progress("Creating mesh", 0.92f)
            val mesh = SurfaceNets.extract(grid)
            check(mesh.triangleCount > 500) { "Reconstruction produced almost no surface (${mesh.triangleCount} triangles)." }
            log("mesh: ${mesh.vertexCount} vertices, ${mesh.triangleCount} triangles")
            progress("Saving .glb", 0.97f)
            val tmp = File(scanDir, "model.glb.tmp")
            GlbWriter.write(tmp, mesh)
            out.delete()
            check(tmp.renameTo(out)) { "Could not save model.glb" }
            log("wrote ${out.length() / 1024} KB in ${(System.currentTimeMillis() - t0) / 1000} s")
            progress("Done", 1f)
            return out
        } finally {
            File(scanDir, "recon_log.txt").writeText(logLines.toString())
        }
    }

    // ---- scan input ---------------------------------------------------------------------------------

    /** [upCam] = world "up" in this frame's camera axes, from the phone's IMU (null if unavailable). */
    private class FrameRef(val file: File, val rotDeg: Int, val upCam: DoubleArray?)

    private fun loadFrames(): List<FrameRef> =
        File(scanDir, "frames.jsonl").readLines().filter { it.isNotBlank() }.mapNotNull {
            val o = JSONObject(it)
            val f = File(scanDir, o.getString("file"))
            if (!f.exists()) return@mapNotNull null
            val rot = o.optInt("rot_deg", 90)
            val rm = o.optJSONArray("R")
            val up = if (rm != null && rm.length() == 9) {
                // R is device->world (E,N,Up), so world-up in device axes is its 3rd row.
                val ux = rm.getDouble(6); val uy = rm.getDouble(7); val uz = rm.getDouble(8)
                val r = Math.toRadians(rot.toDouble()); val s = kotlin.math.sin(r); val c = kotlin.math.cos(r)
                // device (x right, y up, z out of screen) -> sensor-buffer camera axes (x right, y down, z forward)
                doubleArrayOf(ux * c - uy * s, -ux * s - uy * c, -uz)
            } else null
            FrameRef(f, rot, up)
        }

    private fun intrinsics(meta: JSONObject, first: File): Intrinsics {
        val img = Imgcodecs.imread(first.absolutePath)
        val w = img.cols(); val h = img.rows(); img.release()
        val i = meta.optJSONObject("intrinsics")
        if (i != null && i.has("fx")) {
            val metaW = meta.optInt("image_width", w).coerceAtLeast(1)
            return Intrinsics(i.getDouble("fx"), i.getDouble("fy"), i.getDouble("cx"), i.getDouble("cy")).scaled(w.toDouble() / metaW)
        }
        log("no intrinsics in meta.json, assuming ~70 deg horizontal field of view")
        return Intrinsics(0.75 * w, 0.75 * w, w / 2.0, h / 2.0)
    }

    // ---- TSDF ---------------------------------------------------------------------------------------

    private fun makeGrid(sr: SfmResult, a: Alignment): Pair<Float, VoxelGrid> {
        val n = sr.points.n
        val xs = FloatArray(n); val ys = FloatArray(n); val zs = FloatArray(n)
        for (i in 0 until n) {
            val p = a.toWorld(sr.points.get(i))
            xs[i] = p[0].toFloat(); ys[i] = p[1].toFloat(); zs[i] = p[2].toFloat()
        }
        fun ext(v: FloatArray): Double {
            v.sort(); return (v[(n * 0.98).toInt().coerceAtMost(n - 1)] - v[(n * 0.02).toInt()]).toDouble().coerceAtLeast(1.0)
        }
        val ex = ext(xs); val ey = ext(ys); val ez = ext(zs)
        val area = 2 * (ex * ez + ex * ey + ey * ez) * 1.5 // rough surface area in m^2
        val voxel = max(0.04, max(sqrt(8 * area / 3.0e6), sqrt(2 * area / TRIANGLE_BUDGET))).coerceAtMost(0.12).toFloat()
        return voxel to VoxelGrid(voxel, 4 * voxel)
    }

    /** Estimates depth for one keyframe, fits it to the sparse points, and fuses it. Returns false if skipped. */
    private fun fuseFrame(
        f: Int, file: File, sr: SfmResult, al: Alignment, depth: DepthEstimator, grid: VoxelGrid, k: Intrinsics,
    ): Boolean {
        val pose = sr.poses[f] ?: return false
        val img = Imgcodecs.imread(file.absolutePath)
        if (img.empty()) return false
        val w = img.cols(); val h = img.rows()
        val bgr = ByteArray(w * h * 3); img.get(0, 0, bgr)
        val disp = depth.estimate(img)
        img.release()

        // Fit 1/z = a*d + b on this frame's sparse points (raw SfM units).
        val kp = sr.keypoints[f]!!; val pid = sr.pointIds[f]!!
        val ds = ArrayList<Double>(); val ws = ArrayList<Double>()
        for (q in pid.indices) {
            if (pid[q] < 0) continue
            val z = pose.toCamera(sr.points.get(pid[q]))[2]
            if (z < 1e-6) continue
            ds.add(disp.sample(kp[2 * q] / w, kp[2 * q + 1] / h).toDouble()); ws.add(1.0 / z)
        }
        val fit = fitAffine(ds, ws) ?: return false
        val a = fit[0]; val b = fit[1]

        val c = al.toWorld(pose.center)
        val rcw = al.rotationToWorld(pose) // camera -> aligned world, row-major
        val ox = c[0].toFloat(); val oy = c[1].toFloat(); val oz = c[2].toFloat()
        var u = STRIDE / 2
        while (u < w) {
            var v = STRIDE / 2
            while (v < h) {
                val inv = a * disp.sample(u.toFloat() / w, v.toFloat() / h) + b
                if (inv > 1e-6) {
                    val zRaw = 1.0 / inv
                    val zm = zRaw * al.scale
                    if (zm in MIN_DEPTH..MAX_DEPTH) {
                        val nx = (u - k.cx) / k.fx; val ny = (v - k.cy) / k.fy
                        // Direction in the aligned world, unit length; depth along the ray = zm * |(nx,ny,1)|.
                        var dx = rcw[0] * nx + rcw[1] * ny + rcw[2]
                        var dy = rcw[3] * nx + rcw[4] * ny + rcw[5]
                        var dz = rcw[6] * nx + rcw[7] * ny + rcw[8]
                        val len = sqrt(dx * dx + dy * dy + dz * dz)
                        dx /= len; dy /= len; dz /= len
                        val o = (v * w + u) * 3
                        val rgb = 0x01000000 or ((bgr[o + 2].toInt() and 255) shl 16) or
                            ((bgr[o + 1].toInt() and 255) shl 8) or (bgr[o].toInt() and 255)
                        grid.integrateRay(ox, oy, oz, dx.toFloat(), dy.toFloat(), dz.toFloat(), (zm * len).toFloat(), rgb)
                    }
                }
                v += STRIDE
            }
            u += STRIDE
        }
        return true
    }

    /** Robust least squares y = a*x + b (two passes, 2.5 sigma clipping). Needs a > 0 and >= 20 points. */
    private fun fitAffine(xs: List<Double>, ys: List<Double>): DoubleArray? {
        if (xs.size < 20) return null
        var keep = BooleanArray(xs.size) { true }
        var res: DoubleArray? = null
        repeat(2) {
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var n = 0
            for (i in xs.indices) if (keep[i]) { sx += xs[i]; sy += ys[i]; sxx += xs[i] * xs[i]; sxy += xs[i] * ys[i]; n++ }
            val den = n * sxx - sx * sx
            if (n < 15 || abs(den) < 1e-12) return null
            val a = (n * sxy - sx * sy) / den
            val b = (sy - a * sx) / n
            res = doubleArrayOf(a, b)
            var ss = 0.0
            for (i in xs.indices) if (keep[i]) { val e = ys[i] - (a * xs[i] + b); ss += e * e }
            val sigma = sqrt(ss / n)
            keep = BooleanArray(xs.size) { abs(ys[it] - (a * xs[it] + b)) <= 2.5 * sigma + 1e-9 }
        }
        val r = res ?: return null
        return if (r[0] > 0) r else null
    }

    companion object {
        private const val TAG = "Recon"
        private const val STRIDE = 4                // pixels between fused rays
        private const val MAX_FUSED_FRAMES = 150
        private const val MIN_DEPTH = 0.25
        private const val MAX_DEPTH = 8.0
        private const val TRIANGLE_BUDGET = 600_000.0
    }
}

/**
 * Maps SfM world coordinates (arbitrary scale, arbitrary orientation) into the store's scene axes:
 * +Y up (from the phone's own image "down" direction), floor at y = 0, 1 unit = 1 m (camera assumed
 * [CAMERA_HEIGHT_M] above the floor), first camera at x = z = 0 looking towards -Z.
 */
class Alignment(
    private val rw: DoubleArray,       // rows: right, up, back (row-major 3x3)
    private val c0: DoubleArray,       // first camera centre (raw)
    val scale: Double,
    private val floorY: Double,        // floor height in aligned units before the final shift
    val up: DoubleArray,
    val cameraHeightRaw: Double,
) {
    fun toWorld(p: DoubleArray): DoubleArray {
        val d = doubleArrayOf(p[0] - c0[0], p[1] - c0[1], p[2] - c0[2])
        return doubleArrayOf(
            scale * (rw[0] * d[0] + rw[1] * d[1] + rw[2] * d[2]),
            scale * (rw[3] * d[0] + rw[4] * d[1] + rw[5] * d[2]) - floorY,
            scale * (rw[6] * d[0] + rw[7] * d[1] + rw[8] * d[2]),
        )
    }

    /** Camera-to-aligned-world rotation (row-major): Rw * R^T. */
    fun rotationToWorld(pose: Pose): DoubleArray {
        val rt = doubleArrayOf(
            pose.r[0], pose.r[3], pose.r[6],
            pose.r[1], pose.r[4], pose.r[7],
            pose.r[2], pose.r[5], pose.r[8],
        )
        val o = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (m in 0..2) s += rw[i * 3 + m] * rt[m * 3 + j]
            o[i * 3 + j] = s
        }
        return o
    }

    companion object {
        const val CAMERA_HEIGHT_M = 1.4

        private fun normalised(v: DoubleArray): DoubleArray {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }

        fun compute(sr: SfmResult, rotDeg: List<Int>, imuUpCam: List<DoubleArray?>, log: (String) -> Unit): Alignment {
            val reg = sr.poses.indices.filter { sr.poses[it] != null }

            // Gravity, estimate A: with the phone held upright, image "down" is buffer direction (sin r, cos r, 0).
            val down = DoubleArray(3)
            for (i in reg) {
                val r = Math.toRadians(rotDeg[i].toDouble())
                val w = sr.poses[i]!!.dirToWorld(sin(r), cos(r), 0.0)
                for (a in 0..2) down[a] += w[a]
            }
            val upImage = normalised(doubleArrayOf(-down[0], -down[1], -down[2]))

            // Gravity, estimate B: the IMU's world-up in each camera, rotated into the SfM world.
            val imu = DoubleArray(3)
            var imuN = 0
            for (i in reg) {
                val u = imuUpCam[i] ?: continue
                val w = sr.poses[i]!!.dirToWorld(u[0], u[1], u[2])
                for (a in 0..2) imu[a] += w[a]
                imuN++
            }
            var up = upImage
            if (imuN >= reg.size / 2) {
                val upImu = normalised(imu)
                val ang = Math.toDegrees(kotlin.math.acos((upImu[0] * upImage[0] + upImu[1] * upImage[1] + upImu[2] * upImage[2]).coerceIn(-1.0, 1.0)))
                if (ang <= 30.0) up = upImu
                log("gravity: IMU vs image estimate differ by %.1f deg -> using %s".format(ang, if (ang <= 30.0) "IMU" else "image"))
            } else log("gravity: no IMU data, using image estimate")

            // Heading: first camera's forward direction flattened onto the horizontal plane.
            val first = sr.poses[reg[0]]!!
            val fwd = first.dirToWorld(0.0, 0.0, 1.0)
            val d = fwd[0] * up[0] + fwd[1] * up[1] + fwd[2] * up[2]
            var f = doubleArrayOf(fwd[0] - d * up[0], fwd[1] - d * up[1], fwd[2] - d * up[2])
            var fl = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
            if (fl < 1e-3) { // looking straight up/down: use the camera's right axis instead
                val rx = first.dirToWorld(1.0, 0.0, 0.0)
                val dd = rx[0] * up[0] + rx[1] * up[1] + rx[2] * up[2]
                f = doubleArrayOf(rx[0] - dd * up[0], rx[1] - dd * up[1], rx[2] - dd * up[2])
                fl = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
            }
            f = doubleArrayOf(f[0] / fl, f[1] / fl, f[2] / fl)
            val right = doubleArrayOf(
                f[1] * up[2] - f[2] * up[1], f[2] * up[0] - f[0] * up[2], f[0] * up[1] - f[1] * up[0],
            ) // f x up
            val rw = doubleArrayOf(
                right[0], right[1], right[2],
                up[0], up[1], up[2],
                -f[0], -f[1], -f[2],
            )

            // Heights along 'up' of cameras and points (raw units).
            val camH = reg.map { val c = sr.poses[it]!!.center; c[0] * up[0] + c[1] * up[1] + c[2] * up[2] }.sorted()
            val camMedian = camH[camH.size / 2]
            val ph = DoubleArray(sr.points.n) { val p = sr.points.get(it); p[0] * up[0] + p[1] * up[1] + p[2] * up[2] }
            val sorted = ph.sortedArray()
            val low = sorted[(sorted.size * 0.02).toInt()]
            var floorH = low
            if (camMedian - low > 1e-9) {
                // Floor = lowest well-populated horizontal slab below the cameras.
                val bins = 64
                val bw = (camMedian - low) / bins
                val counts = IntArray(bins)
                for (h in ph) if (h in low..camMedian) counts[min(bins - 1, ((h - low) / bw).toInt())]++
                val peak = counts.maxOrNull() ?: 0
                val bin = counts.indexOfFirst { it >= 0.5 * peak && it > 0 }
                if (bin >= 0) floorH = low + (bin + 0.5) * bw
            }
            var height = camMedian - floorH
            if (height < 1e-6) height = max(camMedian - low, 1e-3)
            val scale = CAMERA_HEIGHT_M / height

            val c0 = first.center
            val c0h = c0[0] * up[0] + c0[1] * up[1] + c0[2] * up[2]
            val floorY = scale * (floorH - c0h) // y of the floor after scaling, relative to the first camera
            return Alignment(rw, c0, scale, floorY, up, height)
        }

        private fun sin(x: Double) = kotlin.math.sin(x)
        private fun cos(x: Double) = kotlin.math.cos(x)
    }
}
