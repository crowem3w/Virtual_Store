package org.example.test.recon

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Naive Surface Nets: one vertex per voxel cell that the zero level set crosses, one quad per grid edge
 * with a sign change. Needs no lookup tables (unlike marching cubes) and gives well-shaped triangles.
 */
object SurfaceNets {
    private val EDGES: Array<IntArray> = buildList {
        for (bit in 0..2) for (c in 0..7) if (c and (1 shl bit) == 0) add(intArrayOf(c, c or (1 shl bit)))
    }.toTypedArray()

    fun extract(g: VoxelGrid, minWeight: Float = 1f): MeshData {
        val cap = g.capacity
        val cellVert = IntArray(cap) { -1 }
        val pos = FloatVec(); val col = ByteVec()

        val vals = FloatArray(8)
        val slots = IntArray(8)

        // 1) a vertex in every cell with a sign change. The cell's base voxel is its minimum corner.
        for (s in 0 until cap) {
            val key = g.keys[s]
            if (key == VoxelGrid.EMPTY || g.weight[s] < minWeight) continue
            val i = VoxelGrid.ki(key); val j = VoxelGrid.kj(key); val k = VoxelGrid.kk(key)
            var neg = 0
            var ok = true
            for (c in 0..7) {
                val sl = g.find(i + (c and 1), j + ((c shr 1) and 1), k + ((c shr 2) and 1))
                if (sl < 0 || g.weight[sl] < minWeight) { ok = false; break }
                slots[c] = sl; vals[c] = g.tsdf[sl]
                if (vals[c] < 0f) neg++
            }
            if (!ok || neg == 0 || neg == 8) continue

            var sx = 0f; var sy = 0f; var sz = 0f; var n = 0
            for (e in EDGES) {
                val a = vals[e[0]]; val b = vals[e[1]]
                if ((a < 0f) == (b < 0f)) continue
                val t = a / (a - b)
                val ax = e[0] and 1; val ay = (e[0] shr 1) and 1; val az = (e[0] shr 2) and 1
                val bx = e[1] and 1; val by = (e[1] shr 1) and 1; val bz = (e[1] shr 2) and 1
                sx += ax + t * (bx - ax); sy += ay + t * (by - ay); sz += az + t * (bz - az); n++
            }
            var r = 0; var gg = 0; var bb = 0; var cn = 0
            for (c in 0..7) {
                val p = g.rgb[slots[c]]
                if (p != 0) { r += p shr 16 and 255; gg += p shr 8 and 255; bb += p and 255; cn++ }
            }
            cellVert[s] = pos.size / 3
            pos.add((i + sx / n) * g.voxel); pos.add((j + sy / n) * g.voxel); pos.add((k + sz / n) * g.voxel)
            if (cn > 0) { col.add(r / cn); col.add(gg / cn); col.add(bb / cn) } else { col.add(128); col.add(128); col.add(128) }
            col.add(255)
        }

        // 2) a quad around every grid edge that crosses the surface.
        val idx = IntVec()
        for (s in 0 until cap) {
            val key = g.keys[s]
            if (key == VoxelGrid.EMPTY) continue
            val base = intArrayOf(VoxelGrid.ki(key), VoxelGrid.kj(key), VoxelGrid.kk(key))
            for (axis in 0..2) {
                val nb = base.copyOf(); nb[axis]++
                val sb = g.find(nb[0], nb[1], nb[2])
                if (sb < 0 || g.weight[s] < minWeight || g.weight[sb] < minWeight) continue
                val va = g.tsdf[s]; val vb = g.tsdf[sb]
                if ((va < 0f) == (vb < 0f)) continue
                val ba = (axis + 1) % 3; val ca = (axis + 2) % 3 // (b, c, axis) is right-handed
                val q = IntArray(4)
                var complete = true
                // Cells sharing the edge, counter-clockwise in the (b,c) plane when seen from +axis.
                val offB = intArrayOf(-1, 0, 0, -1)
                val offC = intArrayOf(-1, -1, 0, 0)
                for (m in 0..3) {
                    val c3 = base.copyOf(); c3[ba] += offB[m]; c3[ca] += offC[m]
                    val cs = g.find(c3[0], c3[1], c3[2])
                    val v = if (cs < 0) -1 else cellVert[cs]
                    if (v < 0) { complete = false; break }
                    q[m] = v
                }
                if (!complete) continue
                // That order faces +axis; free space (positive TSDF) is on +axis when va < 0.
                if (va < 0f) {
                    idx.add(q[0]); idx.add(q[1]); idx.add(q[2]); idx.add(q[0]); idx.add(q[2]); idx.add(q[3])
                } else {
                    idx.add(q[0]); idx.add(q[2]); idx.add(q[1]); idx.add(q[0]); idx.add(q[3]); idx.add(q[2])
                }
            }
        }
        return clean(pos.toArray(), col.toArray(), idx.toArray())
    }

    /** Drops tiny floating fragments, compacts vertices and computes smooth vertex normals. */
    private fun clean(pos: FloatArray, col: ByteArray, tri: IntArray): MeshData {
        val nV = pos.size / 3
        val parent = IntArray(nV) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        for (t in 0 until tri.size / 3) {
            val a = find(tri[t * 3]); val b = find(tri[t * 3 + 1]); val c = find(tri[t * 3 + 2])
            parent[b] = a; parent[find(c)] = find(a)
        }
        val trisIn = IntArray(nV)
        for (t in 0 until tri.size / 3) trisIn[find(tri[t * 3])]++
        val biggest = trisIn.maxOrNull() ?: 0
        val minTris = max(100, biggest / 50)

        val remap = IntArray(nV) { -1 }
        var kept = 0
        for (v in 0 until nV) if (trisIn[find(v)] >= minTris) remap[v] = kept++
        val outPos = FloatArray(kept * 3); val outCol = ByteArray(kept * 4)
        for (v in 0 until nV) {
            val m = remap[v]; if (m < 0) continue
            System.arraycopy(pos, v * 3, outPos, m * 3, 3)
            System.arraycopy(col, v * 4, outCol, m * 4, 4)
        }
        val outIdx = IntVec()
        for (t in 0 until tri.size / 3) {
            val a = remap[tri[t * 3]]; val b = remap[tri[t * 3 + 1]]; val c = remap[tri[t * 3 + 2]]
            if (a >= 0 && b >= 0 && c >= 0) { outIdx.add(a); outIdx.add(b); outIdx.add(c) }
        }
        val idx = outIdx.toArray()

        val nrm = FloatArray(kept * 3)
        for (t in 0 until idx.size / 3) {
            val a = idx[t * 3] * 3; val b = idx[t * 3 + 1] * 3; val c = idx[t * 3 + 2] * 3
            val e1x = outPos[b] - outPos[a]; val e1y = outPos[b + 1] - outPos[a + 1]; val e1z = outPos[b + 2] - outPos[a + 2]
            val e2x = outPos[c] - outPos[a]; val e2y = outPos[c + 1] - outPos[a + 1]; val e2z = outPos[c + 2] - outPos[a + 2]
            val nx = e1y * e2z - e1z * e2y; val ny = e1z * e2x - e1x * e2z; val nz = e1x * e2y - e1y * e2x
            for (o in intArrayOf(a, b, c)) { nrm[o] += nx; nrm[o + 1] += ny; nrm[o + 2] += nz }
        }
        for (v in 0 until kept) {
            val x = nrm[v * 3]; val y = nrm[v * 3 + 1]; val z = nrm[v * 3 + 2]
            val l = sqrt(x * x + y * y + z * z)
            if (l > 1e-12f) { nrm[v * 3] = x / l; nrm[v * 3 + 1] = y / l; nrm[v * 3 + 2] = z / l } else nrm[v * 3 + 1] = 1f
        }
        return MeshData(outPos, nrm, outCol, idx)
    }
}
