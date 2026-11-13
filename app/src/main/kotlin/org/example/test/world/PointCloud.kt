package org.example.test.world

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Open-addressing Long -> Int map. Keys are never removed. */
private class LongIntMap {
    private var cap = 1 shl 16
    private var keys = LongArray(cap) { EMPTY }
    private var vals = IntArray(cap)
    private var size = 0

    private fun slot(k: Long, mask: Int): Int {
        var h = k * -7046029254386353131L
        h = h xor (h ushr 32)
        return h.toInt() and mask
    }

    /** Returns true if [k] was new. */
    fun putIfAbsent(k: Long, v: Int): Boolean {
        if (size * 2 >= cap) grow()
        val mask = cap - 1
        var i = slot(k, mask)
        while (true) {
            val kk = keys[i]
            if (kk == EMPTY) { keys[i] = k; vals[i] = v; size++; return true }
            if (kk == k) return false
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val ok = keys; val ov = vals
        cap *= 2
        keys = LongArray(cap) { EMPTY }
        vals = IntArray(cap)
        size = 0
        for (i in ok.indices) if (ok[i] != EMPTY) putIfAbsent(ok[i], ov[i])
    }

    companion object { const val EMPTY = Long.MIN_VALUE }
}

/**
 * Coloured points in the gravity-aligned world frame (x right, y DOWN, z forward at the start).
 * When [indexed], points closer than one voxel to an existing point are dropped.
 * All access from other threads must hold the monitor of this object (`synchronized(cloud)`).
 */
class PointCloud(
    private val voxel: Float = 0.04f,
    private val maxPoints: Int = 1_200_000,
    indexed: Boolean = true,
) {
    var count = 0; private set
    var xyz = FloatArray(3 * 16384); private set
    var rgb = IntArray(16384); private set
    private val map: LongIntMap? = if (indexed) LongIntMap() else null

    @Synchronized
    fun add(x: Float, y: Float, z: Float, color: Int): Boolean {
        if (count >= maxPoints) return false
        if (map != null) {
            val ix = floor(x / voxel).toInt().toLong() and 0x1FFFFF
            val iy = floor(y / voxel).toInt().toLong() and 0x1FFFFF
            val iz = floor(z / voxel).toInt().toLong() and 0x1FFFFF
            val key = (ix shl 42) or (iy shl 21) or iz
            if (!map.putIfAbsent(key, count)) return false
        }
        if (count == rgb.size) {
            xyz = xyz.copyOf(xyz.size * 2)
            rgb = rgb.copyOf(rgb.size * 2)
        }
        xyz[3 * count] = x; xyz[3 * count + 1] = y; xyz[3 * count + 2] = z
        rgb[count] = color
        count++
        return true
    }

    /** Serialises points [from] until [count] (16 bytes each: 3 floats + ARGB int, little endian). */
    @Synchronized
    fun encodeFrom(from: Int): Pair<ByteArray, Int> {
        val n = count - from
        if (n <= 0) return ByteArray(0) to count
        val bb = ByteBuffer.allocate(n * RECORD).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until count) {
            bb.putFloat(xyz[3 * i]); bb.putFloat(xyz[3 * i + 1]); bb.putFloat(xyz[3 * i + 2])
            bb.putInt(rgb[i])
        }
        return bb.array() to count
    }

    companion object {
        const val RECORD = 16

        /** Loads a saved points.bin without building the dedupe index (read-only use). */
        fun load(file: File): PointCloud {
            val pc = PointCloud(indexed = false, maxPoints = Int.MAX_VALUE)
            if (!file.exists()) return pc
            val bytes = file.readBytes()
            val n = bytes.size / RECORD
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            pc.xyz = FloatArray(maxOf(3 * n, 3))
            pc.rgb = IntArray(maxOf(n, 1))
            for (i in 0 until n) {
                pc.xyz[3 * i] = bb.getFloat(); pc.xyz[3 * i + 1] = bb.getFloat(); pc.xyz[3 * i + 2] = bb.getFloat()
                pc.rgb[i] = bb.getInt()
            }
            pc.count = n
            return pc
        }
    }
}
