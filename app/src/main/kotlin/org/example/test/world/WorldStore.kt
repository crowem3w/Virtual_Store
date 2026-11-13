package org.example.test.world

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** [chunks] = saved TSDF blocks (the .blk files in chunks/); [voxel] is their voxel size in metres (0 = no TSDF). */
data class WorldInfo(
    val id: String, val name: String, val points: Int, val updatedMs: Long, val dir: File,
    val voxel: Float = 0f, val chunks: Int = 0, val tier: String = "", val keyframes: Int = 0,
) {
    /** A world can be continued when its keyframe images and poses are on disk. */
    val canResume: Boolean get() = keyframes >= ResumeData.MIN_KEYFRAMES && File(dir, "poses.jsonl").exists()
    val thumbnail: File get() = File(dir, "thumbnail.jpg")
    val pointsFile: File get() = File(dir, "points.bin")
    val chunksDir: File get() = File(dir, "chunks")
}

/** Saved worlds live in filesDir/worlds/<id>/{meta.json, points.bin, chunks/, poses.jsonl, keyframes/, thumbnail.jpg}. */
class WorldStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "worlds")

    fun create(): WorldSession {
        val now = Date()
        val id = "w_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val name = "World " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(now)
        return WorldSession(File(root, id).also { it.mkdirs() }, name, now.time)
    }

    /** Continues an existing world: new keyframes, points and blocks are added to the same folder. */
    fun open(info: WorldInfo): WorldSession {
        val meta = runCatching { JSONObject(File(info.dir, "meta.json").readText()) }.getOrNull()
        val created = meta?.optLong("created_ms", info.dir.lastModified()) ?: info.dir.lastModified()
        return WorldSession(info.dir, info.name, created)
    }

    fun list(): List<WorldInfo> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .sortedByDescending { it.name }
        .map { dir ->
            val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()
            val pts = File(dir, "points.bin").let { if (it.exists()) (it.length() / PointCloud.RECORD).toInt() else 0 }
            val nChunks = File(dir, "chunks").list()?.count { it.endsWith(".blk") } ?: 0
            val nKeyframes = File(dir, "keyframes").list()?.count { it.endsWith(".jpg") } ?: 0
            WorldInfo(
                id = dir.name,
                name = meta?.optString("name").orEmpty().ifBlank { dir.name },
                points = pts,
                updatedMs = meta?.optLong("updated_ms", dir.lastModified()) ?: dir.lastModified(),
                dir = dir,
                voxel = meta?.optDouble("voxel", 0.0)?.toFloat() ?: 0f,
                chunks = nChunks,
                tier = meta?.optString("tier").orEmpty(),
                keyframes = nKeyframes,
            )
        }

    fun delete(info: WorldInfo) { info.dir.deleteRecursively() }

    fun rename(info: WorldInfo, name: String) {
        val f = File(info.dir, "meta.json")
        val meta = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        meta.put("name", name.trim().ifBlank { info.name })
        f.writeText(meta.toString(2))
    }
}

/**
 * The writer for one world. Everything is append-only and flushed regularly, so an interrupted
 * scan keeps almost everything. Methods are synchronized: the depth worker and the UI thread both use it.
 */
class WorldSession(val dir: File, private val name: String, private val createdMs: Long) {
    private val kfDir = File(dir, "keyframes").also { it.mkdirs() }
    private val poseLog = File(dir, "poses.jsonl")
    private var savedPoints = 0

    /** The slam frames of this world (see [Segment]) and the camera intrinsics, written to meta.json on every flush. */
    @Volatile var segments: List<Segment> = listOf(Segment.identity())
    @Volatile var intrinsics: Intrinsics? = null

    /** After loading an existing points.bin into the cloud: only points added after these are appended. */
    @Synchronized
    fun setSavedPoints(n: Int) { savedPoints = n }

    @Synchronized
    fun saveKeyframe(id: Int, jpeg: ByteArray, r: DoubleArray, t: DoubleArray, rotDeg: Int, seg: Int = 0) {
        File(kfDir, "%05d.jpg".format(id)).writeBytes(jpeg)
        val line = JSONObject()
            .put("id", id)
            .put("rot_deg", rotDeg)
            .put("seg", seg) // which slam frame (see Segment) the pose is in
            .put("R", JSONArray(r.toList())) // world -> camera, row-major (SLAM frame)
            .put("t", JSONArray(t.toList()))
        poseLog.appendText(line.toString() + "\n")
    }

    /** Appends points not yet on disk, writes changed TSDF blocks, and rewrites meta.json. */
    @Synchronized
    fun flush(cloud: PointCloud, keyframes: Int, tsdf: ChunkedTsdf? = null, tier: Tier? = null) {
        val (bytes, newCount) = cloud.encodeFrom(savedPoints)
        if (bytes.isNotEmpty()) {
            FileOutputStream(File(dir, "points.bin"), true).use { it.write(bytes) }
            savedPoints = newCount
        }
        tsdf?.saveDirtyBlocks()
        val meta = JSONObject()
            .put("name", existingName())
            .put("created_ms", createdMs)
            .put("updated_ms", System.currentTimeMillis())
            .put("points", savedPoints)
            .put("keyframes", keyframes)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        val segs = segments
        meta.put("segments", ResumeData.segmentsToJson(segs))
        meta.put("align_slam_to_world", JSONArray(segs.first().align.toList())) // kept for older readers
        intrinsics?.let { meta.put("intrinsics", JSONArray(listOf(it.fx, it.fy, it.cx, it.cy))) }
        if (tsdf != null) meta.put("voxel", tsdf.voxel.toDouble()).put("block", BLOCK)
        if (tier != null) meta.put("tier", tier.name)
        File(dir, "meta.json").writeText(meta.toString(2))
    }

    @Synchronized
    fun finish(cloud: PointCloud, keyframes: Int, lastJpeg: ByteArray?, tsdf: ChunkedTsdf? = null, tier: Tier? = null) {
        flush(cloud, keyframes, tsdf, tier)
        if (lastJpeg != null) File(dir, "thumbnail.jpg").writeBytes(lastJpeg)
    }

    /** Keeps a user rename done in the list while a scan is still running. */
    private fun existingName(): String =
        runCatching { JSONObject(File(dir, "meta.json").readText()).optString("name") }
            .getOrNull().orEmpty().ifBlank { name }
}
