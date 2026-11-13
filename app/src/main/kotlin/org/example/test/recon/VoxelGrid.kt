package org.example.test.recon

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sparse TSDF volume: an open-addressing hash of voxels keyed by integer grid coordinates, so a whole
 * shop costs memory only near surfaces. Voxel (i,j,k) sits at world position (i,j,k) * [voxel].
 * TSDF is positive in free space (towards the camera) and negative behind the surface.
 */
class VoxelGrid(val voxel: Float, val trunc: Float, private val maxCapacity: Int = 1 shl 23) {
    var capacity = 1 shl 18; private set
    private var mask = capacity - 1
    var keys = LongArray(capacity) { EMPTY }; private set
    var tsdf = FloatArray(capacity); private set
    var weight = FloatArray(capacity); private set
    /** 0 = no colour yet, otherwise 0x01RRGGBB. */
    var rgb = IntArray(capacity); private set
    var size = 0; private set

    /** Slot of voxel (i,j,k) or -1. */
    fun find(i: Int, j: Int, k: Int): Int {
        val key = key(i, j, k)
        var s = hash(key) and mask
        while (true) {
            val kk = keys[s]
            if (kk == key) return s
            if (kk == EMPTY) return -1
            s = (s + 1) and mask
        }
    }

    private fun findOrInsert(i: Int, j: Int, k: Int): Int {
        if ((size + 1) * 2 > capacity) grow()
        val key = key(i, j, k)
        var s = hash(key) and mask
        while (true) {
            val kk = keys[s]
            if (kk == key) return s
            if (kk == EMPTY) {
                keys[s] = key; tsdf[s] = 0f; weight[s] = 0f; rgb[s] = 0; size++
                return s
            }
            s = (s + 1) and mask
        }
    }

    private fun grow() {
        check(capacity * 2 <= maxCapacity) { "Scene too large for memory (${size} voxels). Try a smaller area." }
        val oldKeys = keys; val oldT = tsdf; val oldW = weight; val oldC = rgb
        capacity *= 2; mask = capacity - 1
        keys = LongArray(capacity) { EMPTY }
        tsdf = FloatArray(capacity); weight = FloatArray(capacity); rgb = IntArray(capacity)
        for (o in oldKeys.indices) {
            val key = oldKeys[o]
            if (key == EMPTY) continue
            var s = hash(key) and mask
            while (keys[s] != EMPTY) s = (s + 1) and mask
            keys[s] = key; tsdf[s] = oldT[o]; weight[s] = oldW[o]; rgb[s] = oldC[o]
        }
    }

    /**
     * Fuses one depth sample. The ray starts at (ox,oy,oz) with unit direction (dx,dy,dz) and hits the
     * surface after [depth] metres. [color] is 0x01RRGGBB, or 0 for "don't touch colours".
     */
    fun integrateRay(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        depth: Float, color: Int,
    ) {
        val w = 1f / (1f + 0.05f * depth * depth) // far samples are less trustworthy
        var s = depth - trunc
        if (s < 0.05f) s = 0.05f
        val end = depth + trunc
        while (s <= end) {
            val i = ((ox + dx * s) / voxel).roundToInt()
            val j = ((oy + dy * s) / voxel).roundToInt()
            val k = ((oz + dz * s) / voxel).roundToInt()
            // Signed distance of the voxel centre along the ray.
            val along = (i * voxel - ox) * dx + (j * voxel - oy) * dy + (k * voxel - oz) * dz
            val sdf = depth - along
            if (sdf >= -trunc) {
                val d = min(1f, sdf / trunc)
                val slot = findOrInsert(i, j, k)
                val w0 = weight[slot]
                tsdf[slot] = (tsdf[slot] * w0 + d * w) / (w0 + w)
                weight[slot] = min(w0 + w, MAX_WEIGHT)
                if (color != 0 && abs(sdf) <= voxel) rgb[slot] = blend(rgb[slot], color)
            }
            s += voxel
        }
    }

    private fun blend(old: Int, new: Int): Int {
        if (old == 0) return new
        val r = ((old shr 16 and 255) * 4 + (new shr 16 and 255)) / 5
        val g = ((old shr 8 and 255) * 4 + (new shr 8 and 255)) / 5
        val b = ((old and 255) * 4 + (new and 255)) / 5
        return 0x01000000 or (r shl 16) or (g shl 8) or b
    }

    companion object {
        const val EMPTY = -1L
        private const val OFF = 1 shl 20
        private const val MAX_WEIGHT = 30f

        fun key(i: Int, j: Int, k: Int): Long =
            ((i + OFF).toLong() shl 42) or ((j + OFF).toLong() shl 21) or (k + OFF).toLong()

        fun ki(key: Long) = (key shr 42).toInt() - OFF
        fun kj(key: Long) = ((key shr 21) and 0x1FFFFF).toInt() - OFF
        fun kk(key: Long) = (key and 0x1FFFFF).toInt() - OFF

        private fun hash(key: Long): Int = ((key * -7046029254386353131L) ushr 32).toInt()
    }
}
