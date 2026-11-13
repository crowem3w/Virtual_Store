package org.example.test.world

import android.util.Log
import org.example.test.recon.DepthEstimator
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class CameraGeometry(val focalMm: Double?, val sensorWidthMm: Double?)

class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    fun matrix(): Mat = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0) }
}

/** World -> camera: x_cam = R * x_world + t. [r] is row-major 3x3. OpenCV camera axes (x right, y down, z forward). */
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

    fun dirToWorld(x: Double, y: Double, z: Double) = doubleArrayOf(
        r[0] * x + r[3] * y + r[6] * z,
        r[1] * x + r[4] * y + r[7] * z,
        r[2] * x + r[5] * y + r[8] * z,
    )
}

enum class TrackState { INITIALIZING, RELOCALIZING, TRACKING, LOST }

/** Camera pose in the gravity-aligned world (x right, y down, z forward), for rendering. */
class CamPose(val r: DoubleArray, val t: DoubleArray, val rotDeg: Int)

/**
 * Live monocular tracking + mapping, one frame at a time.
 *
 *  - Initialisation: ORB matches between a reference frame and a later frame, essential matrix,
 *    triangulation. The scale is fixed so the median initial point is [INIT_DEPTH_M] away.
 *  - Tracking: ORB matches against the last keyframes' map points, PnP + RANSAC.
 *  - Mapping: new keyframes triangulate new sparse points; a background thread runs the depth model
 *    on every keyframe and adds a dense, coloured point set to [cloud].
 *
 * Resume: [loadSaved] rebuilds a sparse map from the saved keyframes (their poses are known) and the state
 * becomes RELOCALIZING until a camera frame matches that map; tracking then continues in the saved frame.
 * If that fails, [placeManually] starts a fresh tracking session that the user has positioned in the saved world.
 *
 * No bundle adjustment or loop closure yet (phase 3): long walks drift.
 * [process] is called from the camera analysis thread; [finish] from any thread.
 */
class LiveSlam(
    private val geometry: CameraGeometry,
    private val depth: DepthEstimator?,
    val cloud: PointCloud,
    private val session: WorldSession,
    val tier: Tier = Tier.MID,
) {
    private class Features(val xy: FloatArray, var desc: Mat?, val pids: IntArray) {
        /** Pinned features (saved keyframes used for relocalization) survive [release]; only [forceRelease] frees them. */
        var pinned = false
        fun release() { if (!pinned) forceRelease() }
        fun forceRelease() { desc?.release(); desc = null }
    }

    private class Keyframe(val id: Int, val pose: Pose, val rotDeg: Int, val feat: Features, val seg: Int)

    private class RelocAcc {
        val obj = ArrayList<Point3>(); val img = ArrayList<Point>()
        val cand = ArrayList<IntArray>() // (map point id, keypoint index in the live frame)
        val seen = HashSet<Int>()
        val hits = ArrayList<Pair<Keyframe, Int>>()
    }

    @Volatile var state = TrackState.INITIALIZING; private set
    @Volatile var hint = "Move the phone sideways, slowly"; private set
    @Volatile var keyframeCount = 0; private set
    @Volatile var camPose: CamPose? = null; private set
    val hasDepthModel: Boolean get() = depth != null

    /** Chunked TSDF + incremental mesh. Only exists with a depth model (the mesh needs dense depth). */
    val tsdf: ChunkedTsdf? =
        if (depth != null) ChunkedTsdf(tier.voxel, File(session.dir, "chunks"), tier.maxBlocksInRam) else null
    val meshStore = MeshStore()
    private val mesher: MeshWorker? = if (tsdf != null && tier.liveMesh) MeshWorker(tsdf, meshStore, tier) else null
    val liveMesh: Boolean get() = mesher != null
    val triangles: Int get() = meshStore.triangles
    val blockCount: Int get() = tsdf?.blockCount ?: 0

    init { mesher?.start() }

    private var k: Intrinsics? = null
    private var kMat: Mat? = null
    private val orb = ORB.create(1000)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)

    private var mapXyz = DoubleArray(3 * 4096)
    private var mapN = 0
    private val kfs = ArrayList<Keyframe>()
    private var nextKfId = 0
    private var lostCount = 0
    private var lastKfInliers = 0
    private var lastKfMs = 0L
    private var lastDepthMs = 0L
    private var lastPose: Pose? = null

    private var initFeat: Features? = null
    private var initRgba: Mat? = null
    private var initRot = 90
    private var initDev: FloatArray? = null
    private var initAge = 0

    /** Slam frames of this world; [segIndex] is the one the camera is tracked in now. See [Segment]. */
    private val segments = ArrayList<Segment>()
    @Volatile private var segIndex = 0
    private val seg: Segment get() = segments.getOrElse(segIndex) { Segment.identity() }

    // Resume state.
    private val anchors = ArrayList<Keyframe>() // saved keyframes with pinned features, relocalization targets
    private var relocCursor = 0
    private var placement: DoubleArray? = null // x, y, z, yaw of a manual placement, used by the next init
    private var resumeCamY = 0.0
    /** World x,z (pairs) of the saved camera positions, for the manual placement screen. */
    @Volatile var resumePathXZ = FloatArray(0); private set

    private var stopped = false
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    @Volatile private var lastJpeg: ByteArray? = null
    private var lastFlushMs = System.currentTimeMillis()

    // ---- entry point ------------------------------------------------------------------------------

    /** [rgba] is an 8-bit 4-channel frame in sensor orientation; [rotDeg] rotates it upright (clockwise). */
    @Synchronized
    fun process(rgba: Mat, rotDeg: Int, dev: FloatArray?) {
        if (stopped) return
        if (k == null) setupIntrinsics(rgba.cols(), rgba.rows())
        val f = detect(rgba)
        if (f == null) { hint = "Point at something with more texture"; return }
        try {
            when (state) {
                TrackState.INITIALIZING -> tryInit(f, rgba, rotDeg, dev)
                TrackState.RELOCALIZING -> relocalize(f, rotDeg)
                else -> track(f, rgba, rotDeg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame failed", e)
            f.release()
        }
    }

    @Synchronized
    fun finish() {
        if (stopped) return
        stopped = true
        initFeat?.release(); initRgba?.release()
        executor.shutdown()
        runCatching { executor.awaitTermination(30, TimeUnit.SECONDS) }
        mesher?.stop()
        session.finish(cloud, nextKfId, lastJpeg, tsdf, tier)
        kfs.forEach { it.feat.release() }
        anchors.forEach { it.feat.forceRelease() }
        depth?.close()
    }

    /** Stops without saving: for a resume that could not be set up. */
    @Synchronized
    fun abort() {
        if (stopped) return
        stopped = true
        executor.shutdown()
        mesher?.stop()
        anchors.forEach { it.feat.forceRelease() }
        depth?.close()
    }

    // ---- setup / features -------------------------------------------------------------------------

    private fun setupIntrinsics(w: Int, h: Int) {
        val f = geometry.focalMm; val s = geometry.sensorWidthMm
        val fx = if (f != null && s != null && s > 0) f * w / s else 0.85 * w
        useIntrinsics(Intrinsics(fx, fx, w / 2.0, h / 2.0))
    }

    private fun useIntrinsics(intr: Intrinsics) {
        k = intr; kMat = intr.matrix()
        session.intrinsics = intr
    }

    private fun detect(rgba: Mat): Features? {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        return detectGray(gray)
    }

    /** Takes ownership of [gray]. */
    private fun detectGray(gray: Mat): Features? {
        val kp = MatOfKeyPoint(); val desc = Mat()
        orb.detectAndCompute(gray, Mat(), kp, desc)
        gray.release()
        val arr = kp.toArray(); kp.release()
        if (desc.empty() || arr.size < MIN_KEYPOINTS) { desc.release(); return null }
        val xy = FloatArray(arr.size * 2)
        for (a in arr.indices) { xy[2 * a] = arr[a].pt.x.toFloat(); xy[2 * a + 1] = arr[a].pt.y.toFloat() }
        return Features(xy, desc, IntArray(arr.size) { -1 })
    }

    /** Ratio-test matches as (indexInA, indexInB) pairs, one per B keypoint. */
    private fun match(a: Features, b: Features): List<IntArray> {
        val da = a.desc ?: return emptyList()
        val db = b.desc ?: return emptyList()
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(da, db, knn, 2)
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

    // ---- resume: saved map, relocalization, manual placement --------------------------------------

    /**
     * Rebuilds a sparse map from the saved keyframes: their poses are known, so matching consecutive
     * keyframes and triangulating gives map points with descriptors. Call before the analyzer gets this
     * object. Returns false when too few keyframes could be loaded. [progress] receives (done, total).
     * Long worlds are thinned to [MAX_ANCHORS] evenly spaced keyframes.
     */
    @Synchronized
    fun loadSaved(data: ResumeData, dir: File, progress: (Int, Int) -> Unit): Boolean {
        if (stopped || data.keyframes.isEmpty()) return false
        segments.clear(); segments.addAll(data.segments)
        segIndex = data.keyframes.last().seg.coerceIn(0, segments.size - 1)
        session.segments = segments.toList()
        data.intrinsics?.let { useIntrinsics(it) }
        resumeCamY = data.cameraY
        nextKfId = data.keyframes.last().id + 1
        keyframeCount = nextKfId

        val path = FloatArray(2 * data.keyframes.size)
        for ((i, sk) in data.keyframes.withIndex()) {
            val c = data.segments[sk.seg].toWorld(sk.pose.center)
            path[2 * i] = c[0].toFloat(); path[2 * i + 1] = c[2].toFloat()
        }
        resumePathXZ = path

        val picked = pickEvenly(data.keyframes, MAX_ANCHORS)
        val recent = ArrayList<Keyframe>() // the last two loaded keyframes: triangulation partners
        var done = 0
        for (sk in picked) {
            progress(done++, picked.size)
            val bgr = Imgcodecs.imread(File(File(dir, "keyframes"), "%05d.jpg".format(sk.id)).absolutePath)
            if (bgr.empty()) { bgr.release(); continue }
            if (k == null) setupIntrinsics(bgr.cols(), bgr.rows())
            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            bgr.release()
            val f = detectGray(gray) ?: continue
            f.pinned = true
            val kf = Keyframe(sk.id, sk.pose, sk.rotDeg, f, sk.seg)
            for (prev in recent) if (prev.seg == kf.seg) linkSaved(prev, kf)
            recent.add(kf)
            if (recent.size > 2) recent.removeAt(0)
            anchors.add(kf)
        }
        progress(picked.size, picked.size)
        if (anchors.size < ResumeData.MIN_KEYFRAMES) {
            anchors.forEach { it.feat.forceRelease() }; anchors.clear()
            return false
        }

        // Show the saved world from where the last scan ended while the user finds their way back.
        val last = anchors.last()
        segIndex = last.seg
        publishPose(last.pose, last.rotDeg)
        state = TrackState.RELOCALIZING
        hint = "Point the camera at an area you scanned before"
        return true
    }

    /** Puts the saved mesh into the live view (blocks stream from disk). Only for tiers with a live mesh. */
    fun publishSavedMesh(progress: (Int, Int) -> Unit) {
        val ts = tsdf ?: return
        if (!tier.liveMesh) return
        ts.meshFromDisk({ meshStore.publish(it) }, progress, tier.maxBlocksInRam / 2)
    }

    /** Matches [a] (earlier) with [b]: shares map points where [a] has them, triangulates new ones from the known poses. */
    private fun linkSaved(a: Keyframe, b: Keyframe) {
        val intr = k ?: return
        for (mm in match(a.feat, b.feat)) {
            if (b.feat.pids[mm[1]] >= 0) continue
            val pa = a.feat.pids[mm[0]]
            if (pa >= 0) { b.feat.pids[mm[1]] = pa; continue }
            val x = triangulate(
                intr, a.pose, a.feat.xy[2 * mm[0]], a.feat.xy[2 * mm[0] + 1],
                b.pose, b.feat.xy[2 * mm[1]], b.feat.xy[2 * mm[1] + 1],
            ) ?: continue
            if (x[3] < 1.5) continue
            val za = a.pose.toCamera(x)[2]; val zb = b.pose.toCamera(x)[2]
            if (za < 0.05 || za > 60.0 || zb < 0.05 || zb > 60.0) continue
            val id = addMapPoint(x[0], x[1], x[2])
            a.feat.pids[mm[0]] = id; b.feat.pids[mm[1]] = id
        }
    }

    private fun <T> pickEvenly(all: List<T>, max: Int): List<T> {
        if (all.size <= max) return all
        return (0 until max).map { (it.toLong() * (all.size - 1) / (max - 1)).toInt() }.distinct().map { all[it] }
    }

    /**
     * Tries to find the camera in the saved map: a few saved keyframes per frame (the window cycles through
     * all of them), PnP + RANSAC on the matched map points. On success tracking continues in that keyframe's
     * slam frame, so new data lines up with the old without any extra transform.
     */
    private fun relocalize(f: Features, rotDeg: Int) {
        val km = kMat
        val n = anchors.size
        if (km == null || n == 0) { f.release(); return }
        val take = minOf(RELOC_WINDOW, n)
        val bySeg = HashMap<Int, RelocAcc>()
        for (i in 0 until take) {
            val kf = anchors[(relocCursor + i) % n]
            val acc = bySeg.getOrPut(kf.seg) { RelocAcc() }
            var hitCount = 0
            for (mm in match(kf.feat, f)) {
                val pid = kf.feat.pids[mm[0]]
                if (pid < 0) continue
                hitCount++
                if (!acc.seen.add(pid)) continue
                acc.obj.add(Point3(mapXyz[3 * pid], mapXyz[3 * pid + 1], mapXyz[3 * pid + 2]))
                acc.img.add(Point(f.xy[2 * mm[1]].toDouble(), f.xy[2 * mm[1] + 1].toDouble()))
                acc.cand.add(intArrayOf(pid, mm[1]))
            }
            acc.hits.add(kf to hitCount)
        }
        relocCursor = (relocCursor + take) % n

        for ((sIdx, acc) in bySeg) {
            if (acc.cand.size < RELOC_MIN_INLIERS) continue
            val rvec = Mat(); val tvec = Mat(); val inl = Mat()
            val ok = Calib3d.solvePnPRansac(
                MatOfPoint3f(*acc.obj.toTypedArray()), MatOfPoint2f(*acc.img.toTypedArray()), km, MatOfDouble(),
                rvec, tvec, false, 300, 4.0f, 0.99, inl, Calib3d.SOLVEPNP_ITERATIVE,
            )
            val inliers = inl.rows()
            if (!ok || inliers < RELOC_MIN_INLIERS || inliers < 0.35 * acc.cand.size) {
                rvec.release(); tvec.release(); inl.release()
                continue
            }
            val rm = Mat(); Calib3d.Rodrigues(rvec, rm)
            val r = DoubleArray(9); rm.get(0, 0, r)
            val t = DoubleArray(3); tvec.get(0, 0, t)
            val idx = IntArray(inliers); inl.get(0, 0, idx)
            rm.release(); rvec.release(); tvec.release(); inl.release()

            val pose = Pose(r, t)
            for (q in idx) f.pids[acc.cand[q][1]] = acc.cand[q][0]
            segIndex = sIdx
            kfs.clear()
            kfs.addAll(acc.hits.sortedByDescending { it.second }.take(3).map { it.first }.sortedBy { it.id })
            lostCount = 0
            lastKfInliers = inliers
            lastKfMs = System.currentTimeMillis()
            lastPose = pose
            state = TrackState.TRACKING
            hint = "Found your place: keep moving slowly"
            publishPose(pose, rotDeg)
            f.release()
            return
        }
        f.release()
    }

    /**
     * Fallback when relocalization does not find the place: starts a fresh tracking session whose start is
     * at world ([x], [z]) facing [yawRad] (0 = +z, towards +x as it grows), at the height of the saved camera.
     * The scale of a fresh session is only a guess, so seams with the old data may not be exact.
     */
    @Synchronized
    fun placeManually(x: Float, z: Float, yawRad: Double) {
        if (stopped || state != TrackState.RELOCALIZING) return
        placement = doubleArrayOf(x.toDouble(), resumeCamY, z.toDouble(), yawRad)
        anchors.forEach { it.feat.forceRelease() }
        anchors.clear()
        kfs.clear()
        lostCount = 0
        state = TrackState.INITIALIZING
        hint = "Placed: move the phone sideways, slowly, to start"
    }

    // ---- initialisation ---------------------------------------------------------------------------

    private fun setInitRef(f: Features, rgba: Mat, rotDeg: Int, dev: FloatArray?) {
        initFeat?.release(); initRgba?.release()
        initFeat = f; initRgba = rgba.clone(); initRot = rotDeg
        initDev = dev?.copyOf(); initAge = 0
    }

    private fun tryInit(f: Features, rgba: Mat, rotDeg: Int, dev: FloatArray?) {
        val a = initFeat
        if (a == null) { setInitRef(f, rgba, rotDeg, dev); return }
        initAge++
        val m = match(a, f)
        if (m.size < 50 || initAge > 90) { setInitRef(f, rgba, rotDeg, dev); return }

        val disp = DoubleArray(m.size) {
            hypot((a.xy[2 * m[it][0]] - f.xy[2 * m[it][1]]).toDouble(), (a.xy[2 * m[it][0] + 1] - f.xy[2 * m[it][1] + 1]).toDouble())
        }
        if (median(disp) < 14.0) { f.release(); hint = "Move sideways, don't just turn on the spot"; return }

        val intr = k ?: return
        val km = kMat ?: return
        val pa = MatOfPoint2f(*m.map { Point(a.xy[2 * it[0]].toDouble(), a.xy[2 * it[0] + 1].toDouble()) }.toTypedArray())
        val pb = MatOfPoint2f(*m.map { Point(f.xy[2 * it[1]].toDouble(), f.xy[2 * it[1] + 1].toDouble()) }.toTypedArray())
        val mask = Mat()
        var e = Calib3d.findEssentialMat(pa, pb, km, Calib3d.RANSAC, 0.999, 1.5, 1000, mask)
        if (e.empty()) { f.release(); return }
        if (e.rows() > 3) e = e.rowRange(0, 3).clone()
        val rm = Mat(); val tm = Mat()
        val inl = Calib3d.recoverPose(e, pa, pb, km, rm, tm, mask)
        if (inl < 50) { f.release(); return }
        val r = DoubleArray(9); rm.get(0, 0, r)
        val t = DoubleArray(3); tm.get(0, 0, t)
        val p0 = Pose(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0))
        val p1 = Pose(r, t)

        val mk = ByteArray(m.size); mask.get(0, 0, mk)
        val tri = ArrayList<Triple<Int, Int, DoubleArray>>()
        val par = ArrayList<Double>()
        for (q in m.indices) {
            if (mk[q].toInt() == 0) continue
            val ia = m[q][0]; val ib = m[q][1]
            val x = triangulate(intr, p0, a.xy[2 * ia], a.xy[2 * ia + 1], p1, f.xy[2 * ib], f.xy[2 * ib + 1]) ?: continue
            if (x[2] > 100.0) continue
            tri.add(Triple(ia, ib, x)); par.add(x[3])
        }
        if (tri.size < 40) { f.release(); return }
        if (median(par.toDoubleArray()) < 1.5) { f.release(); hint = "Move sideways, don't just turn on the spot"; return }

        // Fix the scale so the typical initial point is INIT_DEPTH_M away (indoors ~ metres).
        val s = INIT_DEPTH_M / median(DoubleArray(tri.size) { tri[it].third[2] })
        val pose1 = Pose(r, doubleArrayOf(t[0] * s, t[1] * s, t[2] * s))
        val ga = buildAlignment(initDev, initRot)
        val place = placement
        val newSeg = if (place != null) placementSegment(ga, place[0], place[1], place[2], place[3]) else Segment(ga, doubleArrayOf(0.0, 0.0, 0.0))
        placement = null
        segments.add(newSeg)
        segIndex = segments.size - 1
        session.segments = segments.toList()

        val ref = initRgba ?: return
        for ((ia, ib, x) in tri) {
            val id = addMapPoint(x[0] * s, x[1] * s, x[2] * s)
            a.pids[ia] = id; f.pids[ib] = id
            if (depth == null) addSparse(x[0] * s, x[1] * s, x[2] * s, ref, a.xy[2 * ia], a.xy[2 * ia + 1])
        }
        val kf0 = Keyframe(nextKfId++, p0, initRot, a, segIndex)
        val kf1 = Keyframe(nextKfId++, pose1, rotDeg, f, segIndex)
        addKeyframe(kf0, ref)
        addKeyframe(kf1, rgba)
        initFeat = null; initRgba?.release(); initRgba = null
        lastKfInliers = tri.size
        lastKfMs = System.currentTimeMillis()
        lastPose = pose1
        publishPose(pose1, rotDeg)
        state = TrackState.TRACKING
        hint = "Tracking: keep moving slowly"
    }

    // ---- tracking ---------------------------------------------------------------------------------

    private fun track(f: Features, rgba: Mat, rotDeg: Int) {
        val intr = k ?: return
        val km = kMat ?: return
        val refs = kfs.takeLast(if (lostCount > 0) 8 else 3)
        val obj = ArrayList<Point3>(); val img = ArrayList<Point>()
        val cand = ArrayList<IntArray>() // (map point id, keypoint index in f)
        val seen = HashSet<Int>()
        val perRef = ArrayList<Pair<Keyframe, List<IntArray>>>()
        for (kf in refs) {
            if (kf.feat.desc == null) continue
            val m = match(kf.feat, f)
            perRef.add(kf to m)
            for (mm in m) {
                val pid = kf.feat.pids[mm[0]]
                if (pid < 0 || !seen.add(pid)) continue
                obj.add(Point3(mapXyz[3 * pid], mapXyz[3 * pid + 1], mapXyz[3 * pid + 2]))
                img.add(Point(f.xy[2 * mm[1]].toDouble(), f.xy[2 * mm[1] + 1].toDouble()))
                cand.add(intArrayOf(pid, mm[1]))
            }
        }
        if (cand.size < MIN_TRACK_INLIERS) { trackFailed(f); return }

        val rvec = Mat(); val tvec = Mat(); val inl = Mat()
        val ok = Calib3d.solvePnPRansac(
            MatOfPoint3f(*obj.toTypedArray()), MatOfPoint2f(*img.toTypedArray()), km, MatOfDouble(),
            rvec, tvec, false, 200, 4.0f, 0.99, inl, Calib3d.SOLVEPNP_ITERATIVE,
        )
        if (!ok || inl.rows() < MIN_TRACK_INLIERS) { trackFailed(f); return }

        val rm = Mat(); Calib3d.Rodrigues(rvec, rm)
        val r = DoubleArray(9); rm.get(0, 0, r)
        val t = DoubleArray(3); tvec.get(0, 0, t)
        val pose = Pose(r, t)
        val prev = lastPose
        if (prev != null && dist(pose.center, prev.center) > 2.0 * INIT_DEPTH_M) { trackFailed(f); return } // implausible jump

        val inIdx = IntArray(inl.rows()); inl.get(0, 0, inIdx)
        for (q in inIdx) f.pids[cand[q][1]] = cand[q][0]
        lostCount = 0
        state = TrackState.TRACKING
        hint = "Tracking: keep moving slowly"
        lastPose = pose
        publishPose(pose, rotDeg)

        // Keyframe decision.
        val depths = DoubleArray(inIdx.size) { pose.toCamera(doubleArrayOf(obj[inIdx[it]].x, obj[inIdx[it]].y, obj[inIdx[it]].z))[2] }
        val sceneDepth = median(depths).coerceAtLeast(0.3)
        val last = kfs.last()
        val base = dist(pose.center, last.pose.center)
        val now = System.currentTimeMillis()
        val needKf = now - lastKfMs > 150 && (base > 0.12 * sceneDepth || inIdx.size < 0.55 * lastKfInliers)
        if (!needKf) { f.release(); return }

        for ((kf, m) in perRef) {
            for (mm in m) {
                if (kf.feat.pids[mm[0]] >= 0 || f.pids[mm[1]] >= 0) continue
                val x = triangulate(
                    intr, kf.pose, kf.feat.xy[2 * mm[0]], kf.feat.xy[2 * mm[0] + 1],
                    pose, f.xy[2 * mm[1]], f.xy[2 * mm[1] + 1],
                ) ?: continue
                if (x[3] < 1.5) continue
                val zc = pose.toCamera(x)[2]
                if (zc < 0.05 || zc > 60.0) continue
                val id = addMapPoint(x[0], x[1], x[2])
                kf.feat.pids[mm[0]] = id; f.pids[mm[1]] = id
                if (depth == null) addSparse(x[0], x[1], x[2], rgba, f.xy[2 * mm[1]], f.xy[2 * mm[1] + 1])
            }
        }
        val kf = Keyframe(nextKfId++, pose, rotDeg, f, segIndex)
        lastKfInliers = inIdx.size
        lastKfMs = now
        addKeyframe(kf, rgba)
    }

    private fun trackFailed(f: Features) {
        f.release()
        lostCount++
        if (lostCount >= 8) {
            if (anchors.isNotEmpty()) {
                state = TrackState.RELOCALIZING
                hint = "Tracking lost: point at an area you scanned before"
            } else {
                state = TrackState.LOST
                hint = "Tracking lost: return to a place you've already scanned"
            }
        } else {
            hint = "Tracking weak: slow down"
        }
    }

    // ---- map / keyframes --------------------------------------------------------------------------

    private fun addMapPoint(x: Double, y: Double, z: Double): Int {
        if (3 * (mapN + 1) > mapXyz.size) mapXyz = mapXyz.copyOf(mapXyz.size * 2)
        mapXyz[3 * mapN] = x; mapXyz[3 * mapN + 1] = y; mapXyz[3 * mapN + 2] = z
        return mapN++
    }

    /** Sparse fallback when no depth model is bundled: the triangulated point itself, coloured from the frame. */
    private fun addSparse(x: Double, y: Double, z: Double, rgba: Mat, u: Float, v: Float) {
        val row = v.toInt().coerceIn(0, rgba.rows() - 1); val col = u.toInt().coerceIn(0, rgba.cols() - 1)
        val px = rgba.get(row, col) ?: return
        val color = (0xFF shl 24) or (px[0].toInt() shl 16) or (px[1].toInt() shl 8) or px[2].toInt()
        val g = toWorld(doubleArrayOf(x, y, z))
        cloud.add(g[0].toFloat(), g[1].toFloat(), g[2].toFloat(), color)
    }

    private fun addKeyframe(kf: Keyframe, rgba: Mat) {
        kfs.add(kf)
        keyframeCount = kf.id + 1
        if (kfs.size > FEATURES_KEPT) kfs[kfs.size - 1 - FEATURES_KEPT].feat.release()
        submitJob(kf, rgba)
    }

    private fun toWorld(p: DoubleArray, s: Segment = seg): DoubleArray = s.toWorld(p)

    private fun publishPose(pose: Pose, rotDeg: Int) {
        val sg = seg
        val a = sg.align
        val rg = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (m in 0..2) s += pose.r[i * 3 + m] * a[j * 3 + m] // R * A^T
            rg[i * 3 + j] = s
        }
        // x_cam = R x_slam + t with x_slam = A^T (x_world - T)  =>  x_cam = rg x_world + (t - rg T)
        val o = sg.offset
        val tg = DoubleArray(3) { i -> pose.t[i] - (rg[3 * i] * o[0] + rg[3 * i + 1] * o[1] + rg[3 * i + 2] * o[2]) }
        camPose = CamPose(rg, tg, rotDeg)
        tsdf?.let {
            val c = toWorld(pose.center)
            it.focus = floatArrayOf(c[0].toFloat(), c[1].toFloat(), c[2].toFloat())
        }
    }

    /** Rows (right, down, forward) of the gravity-aligned frame, expressed in the slam frame. */
    private fun buildAlignment(dev: FloatArray?, rotDeg: Int): DoubleArray {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        if (dev == null) return identity
        val ux = dev[6].toDouble(); val uy = dev[7].toDouble(); val uz = dev[8].toDouble() // world up in device axes
        val rad = Math.toRadians(rotDeg.toDouble()); val c = cos(rad); val s = sin(rad)
        val up = doubleArrayOf(ux * c - uy * s, -ux * s - uy * c, -uz) // world up in the sensor-buffer camera axes
        val down = unit(doubleArrayOf(-up[0], -up[1], -up[2]))
        var fwd = removeComponent(doubleArrayOf(0.0, 0.0, 1.0), down)
        if (norm(fwd) < 0.3) fwd = removeComponent(doubleArrayOf(0.0, -1.0, 0.0), down) // looking straight up/down
        fwd = unit(fwd)
        val right = cross(down, fwd)
        return doubleArrayOf(right[0], right[1], right[2], down[0], down[1], down[2], fwd[0], fwd[1], fwd[2])
    }

    // ---- background depth + persistence -----------------------------------------------------------

    private fun submitJob(kf: Keyframe, rgba: Mat) {
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        val xy = kf.feat.xy; val pids = kf.feat.pids
        val obs = ArrayList<DoubleArray>() // u, v, X, Y, Z (slam frame)
        for (q in pids.indices) {
            val pid = pids[q]
            if (pid < 0) continue
            obs.add(doubleArrayOf(xy[2 * q].toDouble(), xy[2 * q + 1].toDouble(), mapXyz[3 * pid], mapXyz[3 * pid + 1], mapXyz[3 * pid + 2]))
        }
        // Keep up: the keyframe is still saved, its dense points are skipped. The tier sets the depth rate
        // (the first two keyframes, the initial pair, always get depth).
        val nowMs = System.currentTimeMillis()
        val skipDepth = pending.get() >= 3 || (kfs.size > 2 && nowMs - lastDepthMs < tier.depthIntervalMs)
        if (!skipDepth) lastDepthMs = nowMs
        pending.incrementAndGet()
        val id = kf.id; val pose = kf.pose; val rot = kf.rotDeg; val kfTotal = kf.id + 1
        val sIdx = segIndex; val sg = seg
        executor.execute { runJob(id, bgr, pose, rot, obs, skipDepth, kfTotal, sIdx, sg) }
    }

    private fun runJob(id: Int, bgr: Mat, pose: Pose, rotDeg: Int, obs: List<DoubleArray>, skipDepth: Boolean, kfTotal: Int, sIdx: Int, sg: Segment) {
        try {
            val buf = MatOfByte()
            Imgcodecs.imencode(".jpg", bgr, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85))
            val jpeg = buf.toArray(); buf.release()
            session.saveKeyframe(id, jpeg, pose.r, pose.t, rotDeg, sIdx)
            lastJpeg = jpeg
            if (depth != null && !skipDepth) fuse(bgr, pose, obs, sg)
            val now = System.currentTimeMillis()
            if (now - lastFlushMs > FLUSH_MS) {
                lastFlushMs = now
                session.flush(cloud, kfTotal, tsdf, tier)
                tsdf?.evictToCap()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "keyframe job failed", e)
        } finally {
            bgr.release()
            pending.decrementAndGet()
        }
    }

    /** Runs the depth model, fits it to the sparse points (1/z = a*d + b) and adds coloured points to the cloud. */
    private fun fuse(bgr: Mat, pose: Pose, obs: List<DoubleArray>, sg: Segment) {
        val dp = depth ?: return
        val intr = k ?: return
        val w = bgr.cols(); val h = bgr.rows()
        val disp = dp.estimate(bgr)

        val ds = ArrayList<Double>(); val inv = ArrayList<Double>()
        for (o in obs) {
            val z = pose.toCamera(doubleArrayOf(o[2], o[3], o[4]))[2]
            if (z < 1e-3) continue
            ds.add(disp.sample((o[0] / w).toFloat(), (o[1] / h).toFloat()).toDouble()); inv.add(1.0 / z)
        }
        val fit = fitAffine(ds, inv) ?: return
        val a = fit[0]; val b = fit[1]

        val px = ByteArray(w * h * 3); bgr.get(0, 0, px)
        val stride = tier.pixelStride
        val gw = (w - 2 * BORDER + stride - 1) / stride
        val gh = (h - 2 * BORDER + stride - 1) / stride
        val zs = FloatArray(gw * gh) // metric depth per sample, 0 = invalid
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val iz = a * disp.sample((BORDER + gx * stride).toFloat() / w, (BORDER + gy * stride).toFloat() / h) + b
            zs[gy * gw + gx] = if (iz > 1e-6) (1.0 / iz).toFloat() else 0f
        }

        val ts = tsdf
        val rays = if (ts != null) FloatArray(4 * gw * gh) else null
        val rayCol = if (ts != null) IntArray(gw * gh) else null
        var nRays = 0
        val org = sg.toWorld(pose.center)

        for (gy in 0 until gh) for (gx in 0 until gw) {
            val z = zs[gy * gw + gx].toDouble()
            if (z < MIN_DEPTH || z > MAX_DEPTH) continue
            val u = BORDER + gx * stride; val v = BORDER + gy * stride
            val dcx = (u - intr.cx) / intr.fx; val dcy = (v - intr.cy) / intr.fy
            val pc = doubleArrayOf(dcx * z, dcy * z, z)
            val pw = pose.dirToWorld(pc[0] - pose.t[0], pc[1] - pose.t[1], pc[2] - pose.t[2]) // R^T (pc - t)
            val g = sg.toWorld(pw)
            val o = (v * w + u) * 3
            val color = (0xFF shl 24) or ((px[o + 2].toInt() and 255) shl 16) or
                ((px[o + 1].toInt() and 255) shl 8) or (px[o].toInt() and 255)
            cloud.add(g[0].toFloat(), g[1].toFloat(), g[2].toFloat(), color)

            // The mesh only takes samples from smooth depth: blurred depth edges would make "flying" surfaces.
            if (rays != null && rayCol != null && z <= TSDF_MAX_DEPTH && smoothAt(zs, gx, gy, gw, gh)) {
                val dl = sqrt(dcx * dcx + dcy * dcy + 1.0)
                val dw = sg.dirToWorld(pose.dirToWorld(dcx, dcy, 1.0))
                rays[4 * nRays] = (dw[0] / dl).toFloat()
                rays[4 * nRays + 1] = (dw[1] / dl).toFloat()
                rays[4 * nRays + 2] = (dw[2] / dl).toFloat()
                rays[4 * nRays + 3] = (z * dl).toFloat()
                rayCol[nRays] = color
                nRays++
            }
        }
        if (ts != null && rays != null && rayCol != null && nRays > 0) {
            ts.integrateFrame(org[0].toFloat(), org[1].toFloat(), org[2].toFloat(), rays, rayCol, nRays)
        }
    }

    /** False when a neighbouring depth sample differs by more than 10 %: that is a depth edge, not a surface. */
    private fun smoothAt(zs: FloatArray, gx: Int, gy: Int, gw: Int, gh: Int): Boolean {
        val z = zs[gy * gw + gx]
        val tol = 0.10f * z
        if (gx > 0 && jump(zs[gy * gw + gx - 1], z, tol)) return false
        if (gx < gw - 1 && jump(zs[gy * gw + gx + 1], z, tol)) return false
        if (gy > 0 && jump(zs[(gy - 1) * gw + gx], z, tol)) return false
        if (gy < gh - 1 && jump(zs[(gy + 1) * gw + gx], z, tol)) return false
        return true
    }

    private fun jump(other: Float, z: Float, tol: Float) = other > 0f && abs(other - z) > tol

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

    // ---- geometry helpers -------------------------------------------------------------------------

    /** Midpoint triangulation from two views. Returns x,y,z,parallaxDegrees or null. */
    private fun triangulate(intr: Intrinsics, pa: Pose, ua: Float, va: Float, pb: Pose, ub: Float, vb: Float): DoubleArray? {
        val da = unit(pa.dirToWorld((ua - intr.cx) / intr.fx, (va - intr.cy) / intr.fy, 1.0))
        val db = unit(pb.dirToWorld((ub - intr.cx) / intr.fx, (vb - intr.cy) / intr.fy, 1.0))
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
        if (reprojError(intr, pa, p, ua, va) > 3.0 || reprojError(intr, pb, p, ub, vb) > 3.0) return null
        return doubleArrayOf(p[0], p[1], p[2], Math.toDegrees(acos(b.coerceIn(-1.0, 1.0))))
    }

    private fun reprojError(intr: Intrinsics, pose: Pose, p: DoubleArray, u: Float, v: Float): Double {
        val c = pose.toCamera(p)
        if (c[2] <= 1e-9) return Double.MAX_VALUE
        val du = intr.fx * c[0] / c[2] + intr.cx - u
        val dv = intr.fy * c[1] / c[2] + intr.cy - v
        return sqrt(du * du + dv * dv)
    }

    private fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        val s = a.sortedArray()
        return s[s.size / 2]
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(v: DoubleArray) = sqrt(dot(v, v))
    private fun unit(v: DoubleArray): DoubleArray { val l = norm(v); return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l) }
    private fun removeComponent(v: DoubleArray, axis: DoubleArray): DoubleArray {
        val d = dot(v, axis)
        return doubleArrayOf(v[0] - d * axis[0], v[1] - d * axis[1], v[2] - d * axis[2])
    }
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )

    companion object {
        private const val TAG = "LiveSlam"
        private const val MIN_KEYPOINTS = 60
        private const val MIN_TRACK_INLIERS = 18
        private const val INIT_DEPTH_M = 2.5
        private const val FEATURES_KEPT = 40
        private const val MAX_ANCHORS = 400 // saved keyframes kept as relocalization targets (about 35 KB each)
        private const val RELOC_WINDOW = 12 // saved keyframes matched per camera frame
        private const val RELOC_MIN_INLIERS = 35
        private const val FLUSH_MS = 8_000L
        private const val TSDF_MAX_DEPTH = 6.0 // metres: monocular depth is too noisy beyond this for the mesh
        private const val BORDER = 8
        private const val MIN_DEPTH = 0.3
        private const val MAX_DEPTH = 10.0
    }
}
