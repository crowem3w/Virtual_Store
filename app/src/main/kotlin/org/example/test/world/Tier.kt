package org.example.test.world

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.test.recon.DepthEstimator
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/**
 * Quality settings per device class. The numbers are starting points (see LIVE_WORLD_PLAN.md section 6)
 * to be tuned on real phones.
 *
 * @param voxel TSDF voxel size in metres (a block is 16 voxels wide)
 * @param depthIntervalMs minimum time between depth-model runs
 * @param pixelStride pixels between depth samples
 * @param liveMesh true = mesh is rebuilt while scanning; false = live point cloud, mesh is built when a world is opened
 * @param meshBudgetMs time slice per mesher cycle
 * @param maxBlocksInRam TSDF blocks kept in memory (about 48 KB each); older ones are saved and evicted
 */
enum class Tier(
    val label: String,
    val voxel: Float,
    val depthIntervalMs: Long,
    val pixelStride: Int,
    val liveMesh: Boolean,
    val meshBudgetMs: Long,
    val maxBlocksInRam: Int,
) {
    HIGH("High", 0.04f, 200L, 4, true, 40L, 2000),
    MID("Mid", 0.07f, 400L, 6, true, 30L, 1800),
    LOW("Low", 0.11f, 1000L, 8, false, 0L, 1500),
}

enum class TierChoice(val label: String) {
    AUTO("Auto (recommended)"),
    HIGH("High: 4 cm voxels, live textured mesh"),
    MID("Mid: 7 cm voxels, live coarse mesh"),
    LOW("Low: 11 cm voxels, live points, mesh built on open"),
}

class BenchResult(val tier: Tier, val orbMs: Double, val depthMs: Double?, val memMb: Int, val cores: Int) {
    fun summary(): String =
        "ORB %.0f ms · depth %s · %d MB heap · %d cores → %s".format(
            orbMs, depthMs?.let { "%.0f ms".format(it) } ?: "no model", memMb, cores, tier.label,
        )
}

/** A short startup benchmark: feature extraction speed, depth-model speed and heap size decide the tier. */
object TierBenchmark {
    /** OpenCV must already be loaded. [depth] may be null when no model is bundled. */
    fun run(context: Context, depth: DepthEstimator?): BenchResult {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = am?.largeMemoryClass ?: 256
        val cores = Runtime.getRuntime().availableProcessors()
        val orb = orbMs()
        val dm = depth?.let { depthMs(it) }
        val d = dm ?: 0.0
        val tier = when {
            mem >= 384 && orb <= 25.0 && d <= 100.0 -> Tier.HIGH
            mem >= 256 && orb <= 45.0 && d <= 300.0 -> Tier.MID
            else -> Tier.LOW
        }
        return BenchResult(tier, orb, dm, mem, cores)
    }

    private fun orbMs(): Double {
        val img = Mat(480, 640, CvType.CV_8UC1)
        Core.randu(img, 0.0, 255.0)
        Imgproc.GaussianBlur(img, img, Size(3.0, 3.0), 0.0)
        val orb = ORB.create(1000)
        val kp = MatOfKeyPoint(); val desc = Mat()
        repeat(2) { orb.detectAndCompute(img, Mat(), kp, desc) }
        val t0 = System.nanoTime()
        repeat(5) { orb.detectAndCompute(img, Mat(), kp, desc) }
        val ms = (System.nanoTime() - t0) / 5e6
        img.release(); kp.release(); desc.release()
        return ms
    }

    private fun depthMs(depth: DepthEstimator): Double {
        val bgr = Mat(480, 640, CvType.CV_8UC3)
        Core.randu(bgr, 0.0, 255.0)
        depth.estimate(bgr) // warm-up
        val t0 = System.nanoTime()
        repeat(3) { depth.estimate(bgr) }
        val ms = (System.nanoTime() - t0) / 3e6
        bgr.release()
        return ms
    }
}

/** The user's quality choice plus the last benchmark result, kept in SharedPreferences. */
class TierSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tier", Context.MODE_PRIVATE)

    var choice: TierChoice
        get() = runCatching { TierChoice.valueOf(prefs.getString("choice", null) ?: "AUTO") }.getOrDefault(TierChoice.AUTO)
        set(v) { prefs.edit().putString("choice", v.name).apply() }

    val detected: Tier?
        get() = prefs.getString("detected", null)?.let { runCatching { Tier.valueOf(it) }.getOrNull() }

    val summary: String? get() = prefs.getString("summary", null)

    fun save(r: BenchResult) {
        prefs.edit().putString("detected", r.tier.name).putString("summary", r.summary()).apply()
    }

    /** The tier to scan with now, or null when the choice is Auto and no benchmark has run yet. */
    fun effective(): Tier? = when (choice) {
        TierChoice.HIGH -> Tier.HIGH
        TierChoice.MID -> Tier.MID
        TierChoice.LOW -> Tier.LOW
        TierChoice.AUTO -> detected
    }

    /** Runs the benchmark on a background thread (loads OpenCV and the depth model itself). */
    suspend fun runBenchmark(context: Context): BenchResult = withContext(Dispatchers.Default) {
        check(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
        val depth = try { DepthEstimator(context) } catch (_: Throwable) { null }
        try {
            TierBenchmark.run(context, depth).also { save(it) }
        } finally {
            depth?.close()
        }
    }
}
