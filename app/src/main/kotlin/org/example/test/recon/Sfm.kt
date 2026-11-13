package org.example.test.recon

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    fun scaled(s: Double) = Intrinsics(fx * s, fy * s, cx * s, cy * s)
    fun matrix(): Mat = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0) }
}

/** World -> camera: x_cam = R * x_world + t. [r] is row-major 3x3. Camera uses OpenCV axes (x right, y down, z forward). */
class Pose(val r: DoubleArray, val t: DoubleArray) {
    val center: DoubleArray
        get() = doubleArrayOf(
            -(r[0] * t[0] + r[3] * t[1] + r[6] * t[2]),
            -(r[1] * t[0] + r[4] * t[1] + r[7] * t[2]),
            -(r[2] * t[0] + r[5] * t[1] + r[8] * t[2]),
        )

    fun toCamera(p: DoubleArray) = doubleArrayOf(
        r[0] * p[0] + r[1] * p[1] + r[2] * p[2] + t[0],
        r[3] * p[0] + r[4] * p[1] + r[5] * p[2] + t[1],
        r[6] * p[0] + r[7] * p[1] + r[8] * p[2] + t[2],
    )

    /** Rotates a camera-frame direction into the world frame (R^T * v). */
    fun dirToWorld(x: Double, y: Double, z: Double) = doubleArrayOf(
        r[0] * x + r[3] * y + r[6] * z,
        r[1] * x + r[4] * y + r[7] * z,
        r[2] * x + r[5] * y + r[8] * z,
    )
}

class PointStore {
    var n = 0; private set
    var xyz = DoubleArray(3 * 4096); private set
    fun add(x: Double, y: Double, z: Double): Int {
        if (3 * (n + 1) > xyz.size) xyz = xyz.copyOf(xyz.size * 2)
        xyz[3 * n] = x; xyz[3 * n + 1] = y; xyz[3 * n + 2] = z
        return n++
    }
    fun get(i: Int) = doubleArrayOf(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2])
}

/** Output of SfM. Per frame: pose (null = not registered), keypoint pixels (x,y interleaved) and the 3D point id of each keypoint (-1 = none). */
class SfmResult(
    val poses: Array<Pose?>,
    val points: PointStore,
    val keypoints: Array<FloatArray?>,
    val pointIds: Array<IntArray?>,
)

/**
 * Incremental structure-from-motion on the saved keyframes: ORB features, essential-matrix
 * initialisation, PnP registration of each further frame, midpoint triangulation of new points.
 * No bundle adjustment yet (OpenCV's Java API has none), so long loops will drift.
 * The reconstruction is up to scale; [ReconPipeline] fixes the scale afterwards.
 */
class Sfm(private val files: List<File>, private val k: Intrinsics, private val log: (String) -> Unit) {

    private class Features(val xy: FloatArray, val desc: Mat)

    private val n = files.size
    private val kMat = k.matrix()
    private val orb = ORB.create(3000)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)

    private val poses = arrayOfNulls<Pose>(n)
    private val kps = arrayOfNulls<FloatArray>(n)
    private val pids = arrayOfNulls<IntArray>(n)
    private val points = PointStore()
    private val registered = ArrayList<Int>()

    private val cache = object : LinkedHashMap<Int, Features>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Features>): Boolean {
            val drop = size > 8
            if (drop) eldest.value.desc.release()
            return drop
        }
    }

    private fun features(i: Int): Features? {
        cache[i]?.let { return it }
        val img = Imgcodecs.imread(files[i].absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        if (img.empty()) return null
        val kp = MatOfKeyPoint(); val desc = Mat()
        orb.detectAndCompute(img, Mat(), kp, desc)
        img.release()
        if (desc.empty()) return null
        val arr = kp.toArray(); kp.release()
        val xy = FloatArray(arr.size * 2)
        for (a in arr.indices) { xy[2 * a] = arr[a].pt.x.toFloat(); xy[2 * a + 1] = arr[a].pt.y.toFloat() }
        if (kps[i] == null) { kps[i] = xy; pids[i] = IntArray(arr.size) { -1 } }
        return Features(xy, desc).also { cache[i] = it }
    }

    /** Ratio-test matches as (indexInA, indexInB) pairs, one per B keypoint. */
    private fun match(a: Features, b: Features): List<IntArray> {
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(a.desc, b.desc, knn, 2)
        val bestForB = HashMap<Int, Pair<Float, Int>>()
        for (m in knn) {
            val d = m.toArray()
            if (d.size == 2 && d[0].distance < 0.75f * d[1].distance) {
                val cur = bestForB[d[0].trainIdx]
                if (cur == null || d[0].distance < cur.first) bestForB[d[0].trainIdx] = d[0].distance to d[0].queryIdx
            }
            m.release()
        }
        return bestForB.map { (ib, v) -> intArrayOf(v.second, ib) }
    }

    private fun norm(x: Double, y: Double) = doubleArrayOf((x - k.cx) / k.fx, (y - k.cy) / k.fy)

    /** Midpoint triangulation from two views. Returns x,y,z,parallaxDegrees or null. */
    private fun triangulate(pa: Pose, ua: Float, va: Float, pb: Pose, ub: Float, vb: Float): DoubleArray? {
        val na = norm(ua.toDouble(), va.toDouble()); val nb = norm(ub.toDouble(), vb.toDouble())
        val da = unit(pa.dirToWorld(na[0], na[1], 1.0)); val db = unit(pb.dirToWorld(nb[0], nb[1], 1.0))
        val ca = pa.center; val cb = pb.center
        val w0 = doubleArrayOf(ca[0] - cb[0], ca[1] - cb[1], ca[2] - cb[2])
        val b = dot(da, db)
        val d = dot(da, w0); val e = dot(db, w0)
        val den = 1.0 - b * b
        if (den < 1e-8) return null
        val sa = (b * e - d) / den
        val sb = (e - b * d) / den
        if (sa <= 0 || sb <= 0) return null
        val p = DoubleArray(3) { 0.5 * ((ca[it] + sa * da[it]) + (cb[it] + sb * db[it])) }
        if (reprojError(pa, p, ua, va) > 3.0 || reprojError(pb, p, ub, vb) > 3.0) return null
        return doubleArrayOf(p[0], p[1], p[2], Math.toDegrees(acos(b.coerceIn(-1.0, 1.0))))
    }

    private fun reprojError(pose: Pose, p: DoubleArray, u: Float, v: Float): Double {
        val c = pose.toCamera(p)
        if (c[2] <= 1e-9) return Double.MAX_VALUE
        val du = k.fx * c[0] / c[2] + k.cx - u
        val dv = k.fy * c[1] / c[2] + k.cy - v
        return sqrt(du * du + dv * dv)
    }

    private fun unit(v: DoubleArray): DoubleArray { val l = sqrt(dot(v, v)); return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l) }
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    // ---- initialisation ---------------------------------------------------------------------------

    private fun initialise(cancel: () -> Unit): Boolean {
        for (i in 0 until minOf(n - 4, 40)) {
            for (gap in intArrayOf(4, 6, 9, 13)) {
                cancel()
                val j = i + gap
                if (j >= n) continue
                val fa = features(i) ?: continue
                val fb = features(j) ?: continue
                val m = match(fa, fb)
                if (m.size < 120) continue

                val pa = MatOfPoint2f(*m.map { Point(fa.xy[2 * it[0]].toDouble(), fa.xy[2 * it[0] + 1].toDouble()) }.toTypedArray())
                val pb = MatOfPoint2f(*m.map { Point(fb.xy[2 * it[1]].toDouble(), fb.xy[2 * it[1] + 1].toDouble()) }.toTypedArray())
                val mask = Mat()
                var e = Calib3d.findEssentialMat(pa, pb, kMat, Calib3d.RANSAC, 0.999, 1.5, 1000, mask)
                if (e.empty()) continue
                if (e.rows() > 3) e = e.rowRange(0, 3).clone()
                val rm = Mat(); val tm = Mat()
                val inl = Calib3d.recoverPose(e, pa, pb, kMat, rm, tm, mask)
                if (inl < 100) continue

                val r = DoubleArray(9); rm.get(0, 0, r)
                val t = DoubleArray(3); tm.get(0, 0, t)
                val p0 = Pose(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0))
                val p1 = Pose(r, t)

                val mk = ByteArray(m.size); mask.get(0, 0, mk)
                val par = ArrayList<Double>()
                val tri = ArrayList<Triple<Int, Int, DoubleArray>>()
                for (q in m.indices) {
                    if (mk[q].toInt() == 0) continue
                    val a = m[q][0]; val b = m[q][1]
                    val x = triangulate(p0, fa.xy[2 * a], fa.xy[2 * a + 1], p1, fb.xy[2 * b], fb.xy[2 * b + 1]) ?: continue
                    if (x[2] > 100.0) continue // unit baseline: reject points >100 baselines away
                    par.add(x[3]); tri.add(Triple(a, b, x))
                }
                if (tri.size < 100) continue
                par.sort()
                if (par[par.size / 2] < 1.5) continue // not enough sideways motion

                poses[i] = p0; poses[j] = p1
                registered.add(i); registered.add(j)
                for ((a, b, x) in tri) {
                    val id = points.add(x[0], x[1], x[2])
                    pids[i]!![a] = id; pids[j]!![b] = id
                }
                log("SfM init with frames $i,$j: ${tri.size} points, median parallax %.1f deg".format(par[par.size / 2]))
                initPair = i to j
                return true
            }
        }
        return false
    }

    private var initPair: Pair<Int, Int>? = null

    // ---- registration -----------------------------------------------------------------------------

    private fun register(idx: Int): Boolean {
        val fk = features(idx) ?: return false
        val refs = registered.sortedBy { abs(it - idx) }.take(2)

        val obj = ArrayList<Point3>(); val img = ArrayList<Point>()
        val cand = ArrayList<IntArray>() // (pid, keypointIndexInK)
        val seen = HashSet<Int>()
        val perRef = ArrayList<Pair<Int, List<IntArray>>>()
        for (r in refs) {
            val fr = features(r) ?: continue
            val m = match(fr, fk)
            perRef.add(r to m)
            for (mm in m) {
                val pid = pids[r]!![mm[0]]
                if (pid < 0 || !seen.add(pid)) continue
                obj.add(Point3(points.xyz[3 * pid], points.xyz[3 * pid + 1], points.xyz[3 * pid + 2]))
                img.add(Point(fk.xy[2 * mm[1]].toDouble(), fk.xy[2 * mm[1] + 1].toDouble()))
                cand.add(intArrayOf(pid, mm[1]))
            }
        }
        if (cand.size < 20) return false

        val rvec = Mat(); val tvec = Mat(); val inl = Mat()
        val ok = Calib3d.solvePnPRansac(
            MatOfPoint3f(*obj.toTypedArray()), MatOfPoint2f(*img.toTypedArray()), kMat, MatOfDouble(),
            rvec, tvec, false, 300, 4.0f, 0.99, inl, Calib3d.SOLVEPNP_ITERATIVE,
        )
        if (!ok || inl.rows() < 20) return false

        val rm = Mat(); Calib3d.Rodrigues(rvec, rm)
        val r = DoubleArray(9); rm.get(0, 0, r)
        val t = DoubleArray(3); tvec.get(0, 0, t)
        val pose = Pose(r, t)
        poses[idx] = pose

        val inIdx = IntArray(inl.rows()); inl.get(0, 0, inIdx)
        for (q in inIdx) pids[idx]!![cand[q][1]] = cand[q][0]

        var added = 0
        for ((r0, m) in perRef) {
            val pr = poses[r0] ?: continue
            val fr = features(r0) ?: continue
            for (mm in m) {
                if (pids[r0]!![mm[0]] >= 0 || pids[idx]!![mm[1]] >= 0) continue
                val x = triangulate(
                    pr, fr.xy[2 * mm[0]], fr.xy[2 * mm[0] + 1],
                    pose, fk.xy[2 * mm[1]], fk.xy[2 * mm[1] + 1],
                ) ?: continue
                if (x[3] < 1.5) continue
                val id = points.add(x[0], x[1], x[2])
                pids[r0]!![mm[0]] = id; pids[idx]!![mm[1]] = id
                added++
            }
        }
        registered.add(idx)
        log("frame $idx registered: ${inIdx.size} inliers, +$added points (total ${points.n})")
        return true
    }

    /** [progress] gets 0..1; [cancel] should throw when the job is cancelled. */
    fun run(progress: (Float) -> Unit, cancel: () -> Unit): SfmResult {
        require(n >= 8) { "Scan has only $n frames; need at least 8." }
        check(initialise(cancel)) {
            "Could not start reconstruction: not enough sideways movement or texture. Walk (don't just rotate) while scanning."
        }
        val (i0, j0) = initPair!!
        val order = ArrayList<Int>()
        for (q in i0 + 1 until j0) order.add(q)
        for (q in j0 + 1 until n) order.add(q)
        for (q in i0 - 1 downTo 0) order.add(q)
        var failed = 0
        for ((c, idx) in order.withIndex()) {
            cancel()
            if (!register(idx)) failed++
            progress((c + 1f) / order.size)
        }
        log("SfM done: ${registered.size}/$n frames registered, ${points.n} points, $failed failures")
        cache.values.forEach { it.desc.release() }
        cache.clear()
        check(registered.size >= 8) { "Only ${registered.size} frames could be aligned. Rescan more slowly with overlap." }
        return SfmResult(poses, points, kps, pids)
    }
}
