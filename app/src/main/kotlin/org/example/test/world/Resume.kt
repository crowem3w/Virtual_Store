package org.example.test.world

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One SLAM coordinate frame of a world. A world has one segment per tracking session that started from
 * scratch (the first scan, and every "place manually" resume). world = [align] * slam + [offset].
 * [align] is a row-major 3x3 rotation, [offset] a 3-vector. Both are in the gravity-aligned world frame
 * (x right, y down, z forward at the start of the first scan).
 */
class Segment(val align: DoubleArray, val offset: DoubleArray) {
    fun toWorld(p: DoubleArray) = doubleArrayOf(
        align[0] * p[0] + align[1] * p[1] + align[2] * p[2] + offset[0],
        align[3] * p[0] + align[4] * p[1] + align[5] * p[2] + offset[1],
        align[6] * p[0] + align[7] * p[1] + align[8] * p[2] + offset[2],
    )

    /** Rotation only: for directions, which must not get the offset. */
    fun dirToWorld(d: DoubleArray) = doubleArrayOf(
        align[0] * d[0] + align[1] * d[1] + align[2] * d[2],
        align[3] * d[0] + align[4] * d[1] + align[5] * d[2],
        align[6] * d[0] + align[7] * d[1] + align[8] * d[2],
    )

    companion object {
        fun identity() = Segment(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            doubleArrayOf(0.0, 0.0, 0.0),
        )
    }
}

/**
 * Segment for a scan that starts at world position ([x], [y], [z]) facing [yaw] radians. Yaw 0 faces +z,
 * and yaw grows towards +x: the new scan's "forward" (0,0,1) becomes (sin yaw, 0, cos yaw).
 * [gravityAlign] is the new session's own slam -> gravity-frame rotation.
 */
fun placementSegment(gravityAlign: DoubleArray, x: Double, y: Double, z: Double, yaw: Double): Segment {
    val c = cos(yaw); val s = sin(yaw)
    val ry = doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
    val out = DoubleArray(9)
    for (i in 0..2) for (j in 0..2) {
        var sum = 0.0
        for (m in 0..2) sum += ry[i * 3 + m] * gravityAlign[m * 3 + j]
        out[i * 3 + j] = sum
    }
    return Segment(out, doubleArrayOf(x, y, z))
}

/** A keyframe as saved in poses.jsonl; its image is keyframes/<id>.jpg. [pose] is in the frame of segment [seg]. */
class SavedKeyframe(val id: Int, val seg: Int, val pose: Pose, val rotDeg: Int)

/** Everything needed from disk to continue a saved world. */
class ResumeData(
    val keyframes: List<SavedKeyframe>,
    val segments: List<Segment>,
    val intrinsics: Intrinsics?,
    val tier: Tier?,
    /** Median height (world y, y points down) of the saved camera positions: where a handheld phone was. */
    val cameraY: Double,
) {
    companion object {
        const val MIN_KEYFRAMES = 3

        /** Null when the world has too few usable keyframes (for example a world saved before keyframes were kept). */
        fun read(dir: File): ResumeData? {
            val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: JSONObject()
            val segments = readSegments(meta)
            val kfDir = File(dir, "keyframes")
            val log = File(dir, "poses.jsonl")
            if (!log.exists() || !kfDir.isDirectory) return null

            val byId = sortedMapOf<Int, SavedKeyframe>()
            log.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching {
                    val o = JSONObject(line)
                    val id = o.getInt("id")
                    val r = o.getJSONArray("R").toDoubles()
                    val t = o.getJSONArray("t").toDoubles()
                    if (r.size != 9 || t.size != 3) return@runCatching
                    if (!File(kfDir, "%05d.jpg".format(id)).exists()) return@runCatching
                    val seg = o.optInt("seg", 0).coerceIn(0, segments.size - 1)
                    byId[id] = SavedKeyframe(id, seg, Pose(r, t), o.optInt("rot_deg", 90))
                }
            }
            if (byId.size < MIN_KEYFRAMES) return null
            val kfs = byId.values.toList()

            val ys = kfs.map { segments[it.seg].toWorld(it.pose.center)[1] }.sorted()
            val camY = ys[ys.size / 2]

            val intr = meta.optJSONArray("intrinsics")?.toDoubles()?.takeIf { it.size >= 4 }
                ?.let { Intrinsics(it[0], it[1], it[2], it[3]) }
            return ResumeData(kfs, segments, intr, readTier(meta), camY)
        }

        /** The tier whose voxel size matches the saved blocks: they must be fused with the same size. */
        private fun readTier(meta: JSONObject): Tier? {
            val voxel = meta.optDouble("voxel", 0.0).toFloat()
            if (voxel > 0f) Tier.entries.firstOrNull { abs(it.voxel - voxel) < 1e-4f }?.let { return it }
            val name = meta.optString("tier")
            return Tier.entries.firstOrNull { it.name == name }
        }

        private fun readSegments(meta: JSONObject): List<Segment> {
            val arr = meta.optJSONArray("segments")
            val out = ArrayList<Segment>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val a = o.optJSONArray("align")?.toDoubles()
                val t = o.optJSONArray("offset")?.toDoubles()
                if (a != null && a.size == 9 && t != null && t.size == 3) out.add(Segment(a, t))
            }
            if (out.isNotEmpty()) return out
            // Worlds saved before segments existed: one frame, no offset.
            val legacy = meta.optJSONArray("align_slam_to_world")?.toDoubles()
            return listOf(if (legacy != null && legacy.size == 9) Segment(legacy, doubleArrayOf(0.0, 0.0, 0.0)) else Segment.identity())
        }

        fun segmentsToJson(segments: List<Segment>): JSONArray = JSONArray().also { arr ->
            for (s in segments) arr.put(JSONObject().put("align", JSONArray(s.align.toList())).put("offset", JSONArray(s.offset.toList())))
        }

        private fun JSONArray.toDoubles() = DoubleArray(length()) { getDouble(it) }
    }
}
