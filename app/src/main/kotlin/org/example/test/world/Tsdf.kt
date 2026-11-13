package org.example.test.world

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Voxels per block edge. A block is the unit of fusion, meshing, saving and eviction. */
const val BLOCK = 16
private const val BLOCK3 = BLOCK * BLOCK * BLOCK

/** A voxel only counts as observed once it has gathered this much weight (about two hits). */
private const val MIN_W = 1.0f
private const val W_MAX = 40f
private const val MAGIC = 0x4B4C4254
private const val UNSET = -2
private const val NONE = -1
private const val VERTEX_STRIDE = 20 // float3 position, 3 x snorm8 normal + pad, 4 x unorm8 colour

fun blockKey(bx: Int, by: Int, bz: Int): Long =
    ((bx.toLong() and 0x1FFFFFL) shl 42) or ((by.toLong() and 0x1FFFFFL) shl 21) or (bz.toLong() and 0x1FFFFFL)

private fun signExtend21(v: Int) = (v shl 11) shr 11
fun keyBx(k: Long): Int = signExtend21((k shr 42).toInt())
fun keyBy(k: Long): Int = signExtend21((k shr 21).toInt() and 0x1FFFFF)
fun keyBz(k: Long): Int = signExtend21(k.toInt() and 0x1FFFFF)

/**
 * 16x16x16 voxels. Voxel (ix,iy,iz) is a sample at world position (ix,iy,iz)*voxelSize.
 * sdf is the truncated signed distance, positive in front of a surface (towards the camera), 1 = unknown/free.
 */
class VoxelBlock(val bx: Int, val by: Int, val bz: Int) {
    val key: Long = blockKey(bx, by, bz)
    val sdf = FloatArray(BLOCK3) { 1f }
    val w = FloatArray(BLOCK3)
    val rgb = IntArray(BLOCK3) // ARGB, 0 = no colour yet
    @JvmField var meshDirty = false
    @JvmField var saveDirty = false
    @JvmField var touched = 0L
}

/** Interleaved vertices ([VERTEX_STRIDE] bytes each) and triangle indices for one block. Empty indices = no surface. */
class BlockMesh(
    val key: Long,
    val vertexBytes: ByteArray,
    val vertexCount: Int,
    val indices: IntArray,
    val cx: Float, val cy: Float, val cz: Float,
    val radius: Float,
) {
    val indexCount: Int get() = indices.size
}

private val EDGE_A = intArrayOf(0, 2, 4, 6, 0, 1, 4, 5, 0, 1, 2, 3)
private val EDGE_B = intArrayOf(1, 3, 5, 7, 2, 3, 6, 7, 4, 5, 6, 7)

/**
 * Sparse, block-chunked TSDF in the gravity-aligned world frame (x right, y DOWN, z forward).
 *
 *  - [integrateFrame] fuses one depth map as rays (analysis/depth thread).
 *  - [meshDirty] re-meshes changed blocks with naive surface nets (mesher thread).
 *  - [saveDirtyBlocks] / [evictToCap] persist changed blocks and bound RAM; evicted blocks reload from disk
 *    when touched again.
 *
 * Everything public is safe to call from any thread; one lock guards all state. The class has no Android
 * dependencies so it can be unit-tested on the JVM.
 */
class ChunkedTsdf(
    val voxel: Float,
    private val chunkDir: File? = null,
    private val maxBlocks: Int = Int.MAX_VALUE,
) {
    val lock = Any()
    private val blocks = HashMap<Long, VoxelBlock>()
    private val dirty = HashSet<Long>()
    private var clock = 0L
    private val hardCap = if (maxBlocks == Int.MAX_VALUE) Int.MAX_VALUE else maxBlocks + maxBlocks / 8

    /** Camera position in the world frame; meshing handles the nearest dirty blocks first. */
    @Volatile var focus = floatArrayOf(0f, 0f, 0f)

    val blockCount: Int get() = synchronized(lock) { blocks.size }

    // ---- fusion -----------------------------------------------------------------------------------

    /**
     * Integrates [n] depth rays seen from ([ox],[oy],[oz]). [rays] holds 4 floats per ray: unit direction (x,y,z)
     * and the range along it; [colors] holds one ARGB per ray.
     */
    fun integrateFrame(ox: Float, oy: Float, oz: Float, rays: FloatArray, colors: IntArray, n: Int) {
        synchronized(lock) {
            clock++
            val inv = 1f / voxel
            val step = voxel * 0.5f
            var cur: VoxelBlock? = null
            for (i in 0 until n) {
                val dx = rays[4 * i]; val dy = rays[4 * i + 1]; val dz = rays[4 * i + 2]
                val range = rays[4 * i + 3]
                val trunc = 3f * voxel + 0.02f * range
                val wi = 1f / (1f + 0.2f * range)
                val color = colors[i]
                var s = max(range - trunc, 0.05f)
                val sEnd = range + trunc
                var li = Int.MIN_VALUE; var lj = Int.MIN_VALUE; var lk = Int.MIN_VALUE
                while (s <= sEnd) {
                    val ix = Math.round((ox + dx * s) * inv)
                    val iy = Math.round((oy + dy * s) * inv)
                    val iz = Math.round((oz + dz * s) * inv)
                    if (ix != li || iy != lj || iz != lk) {
                        li = ix; lj = iy; lk = iz
                        // Distance of this voxel's own position to the surface, measured along the ray.
                        val along = (ix * voxel - ox) * dx + (iy * voxel - oy) * dy + (iz * voxel - oz) * dz
                        val d = range - along
                        if (d >= -trunc) { // voxels far behind the surface stay untouched
                            val sdf = if (d > trunc) 1f else d / trunc
                            val bx = ix shr 4; val by = iy shr 4; val bz = iz shr 4
                            var b = cur
                            if (b == null || b.bx != bx || b.by != by || b.bz != bz) {
                                b = fetchForWrite(bx, by, bz)
                                cur = b
                            }
                            if (b != null) update(b, ix and 15, iy and 15, iz and 15, sdf, wi, color)
                        }
                    }
                    s += step
                }
            }
        }
    }

    private fun fetchForWrite(bx: Int, by: Int, bz: Int): VoxelBlock? {
        val k = blockKey(bx, by, bz)
        var b = blocks[k]
        if (b == null) {
            if (blocks.size >= hardCap) return null // RAM is full until the next eviction pass
            b = loadBlock(bx, by, bz) ?: VoxelBlock(bx, by, bz)
            blocks[k] = b
        }
        b.touched = clock
        return b
    }

    private fun update(b: VoxelBlock, lx: Int, ly: Int, lz: Int, sdf: Float, wi: Float, color: Int) {
        val i = lx + BLOCK * (ly + BLOCK * lz)
        val w0 = b.w[i]
        val wn = w0 + wi
        b.sdf[i] = (b.sdf[i] * w0 + sdf * wi) / wn
        b.w[i] = min(wn, W_MAX)
        if (sdf > -0.6f && sdf < 0.6f) {
            val c0 = b.rgb[i]
            b.rgb[i] = if (c0 == 0) color else blend(c0, color, wi / wn)
        }
        b.saveDirty = true
        if (!b.meshDirty) { b.meshDirty = true; dirty.add(b.key) }
        if (lx == 0 || lx == BLOCK - 1 || ly == 0 || ly == BLOCK - 1 || lz == 0 || lz == BLOCK - 1) markNeighbors(b, lx, ly, lz)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val r = ((a shr 16) and 255) + ((((b shr 16) and 255) - ((a shr 16) and 255)) * f).toInt()
        val g = ((a shr 8) and 255) + ((((b shr 8) and 255) - ((a shr 8) and 255)) * f).toInt()
        val bl = (a and 255) + (((b and 255) - (a and 255)) * f).toInt()
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
    }

    /** A voxel on a block face also changes the cells of the neighbouring block(s): mark them for re-meshing. */
    private fun markNeighbors(b: VoxelBlock, lx: Int, ly: Int, lz: Int) {
        val xs = if (lx == 0) -1 else if (lx == BLOCK - 1) 1 else 0
        val ys = if (ly == 0) -1 else if (ly == BLOCK - 1) 1 else 0
        val zs = if (lz == 0) -1 else if (lz == BLOCK - 1) 1 else 0
        for (a in 0..1) for (c in 0..1) for (e in 0..1) {
            val dx = if (a == 1) xs else 0
            val dy = if (c == 1) ys else 0
            val dz = if (e == 1) zs else 0
            if (dx == 0 && dy == 0 && dz == 0) continue
            val nbk = blocks[blockKey(b.bx + dx, b.by + dy, b.bz + dz)] ?: continue
            if (!nbk.meshDirty) { nbk.meshDirty = true; dirty.add(nbk.key) }
        }
    }

    // ---- meshing ----------------------------------------------------------------------------------

    private val nb = arrayOfNulls<VoxelBlock>(27)
    private val cellVert = IntArray(18 * 18 * 18)
    private var vPos = FloatArray(6 * 4096)
    private var vCol = IntArray(4096)
    private var vN = 0
    private var idx = IntArray(6 * 4096)
    private var iN = 0
    private val cs = FloatArray(8)
    private val cc = IntArray(8)
    private val quad = IntArray(4)

    val dirtyCount: Int get() = synchronized(lock) { dirty.size }

    fun markAllDirty() {
        synchronized(lock) { for (b in blocks.values) { b.meshDirty = true; dirty.add(b.key) } }
    }

    /**
     * Re-meshes dirty blocks, nearest to [focus] first, until [budgetMs] or [maxCount] is used up.
     * Each finished block goes to [sink]. Returns how many blocks were meshed.
     */
    fun meshDirty(budgetMs: Long, maxCount: Int, sink: (BlockMesh) -> Unit): Int {
        val keys = synchronized(lock) { nearestDirtyKeys() }
        val limit = if (budgetMs >= Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else budgetMs * 1_000_000L
        val t0 = System.nanoTime()
        var n = 0
        for (k in keys) {
            if (n >= maxCount || System.nanoTime() - t0 > limit) break
            val mesh = synchronized(lock) {
                dirty.remove(k)
                val b = blocks[k]
                if (b == null) null else { b.meshDirty = false; meshBlock(b, false) }
            }
            if (mesh != null) sink(mesh)
            n++
        }
        return n
    }

    private fun nearestDirtyKeys(): LongArray {
        if (dirty.isEmpty()) return LongArray(0)
        val f = focus
        val vs = voxel * BLOCK
        return dirty.sortedBy { k ->
            val dx = (keyBx(k) + 0.5f) * vs - f[0]
            val dy = (keyBy(k) + 0.5f) * vs - f[1]
            val dz = (keyBz(k) + 0.5f) * vs - f[2]
            dx * dx + dy * dy + dz * dz
        }.toLongArray()
    }

    /**
     * Meshes every block stored on disk without keeping them all in RAM (at most about [cacheBlocks] at once).
     * Used by the viewer. [progress] receives (done, total).
     */
    fun meshFromDisk(sink: (BlockMesh) -> Unit, progress: (Int, Int) -> Unit, cacheBlocks: Int = 1500) {
        val keys = storedKeys().sortedWith(compareBy<Long>({ keyBz(it) }, { keyBy(it) }, { keyBx(it) }))
        var n = 0
        for (k in keys) {
            val mesh = synchronized(lock) {
                clock++
                val b = fetch(keyBx(k), keyBy(k), keyBz(k), true)
                if (b == null) null else { dirty.remove(k); b.meshDirty = false; meshBlock(b, true) }
            }
            if (mesh != null) sink(mesh)
            n++
            if (n % 32 == 0) {
                progress(n, keys.size)
                synchronized(lock) { trim(cacheBlocks) }
            }
        }
        progress(keys.size, keys.size)
    }

    private fun fetch(bx: Int, by: Int, bz: Int, load: Boolean): VoxelBlock? {
        val k = blockKey(bx, by, bz)
        var b = blocks[k]
        if (b == null && load) {
            b = loadBlock(bx, by, bz)
            if (b != null) blocks[k] = b
        }
        b?.touched = clock
        return b
    }

    private fun nbBlock(lx: Int, ly: Int, lz: Int): VoxelBlock? {
        val ox = if (lx < 0) 0 else if (lx >= BLOCK) 2 else 1
        val oy = if (ly < 0) 0 else if (ly >= BLOCK) 2 else 1
        val oz = if (lz < 0) 0 else if (lz >= BLOCK) 2 else 1
        return nb[ox + 3 * (oy + 3 * oz)]
    }

    /** SDF at block-local voxel coords (-1..16), or NaN when that voxel is unobserved. */
    private fun sdfAt(lx: Int, ly: Int, lz: Int): Float {
        val bl = nbBlock(lx, ly, lz) ?: return Float.NaN
        val i = (lx and 15) + BLOCK * ((ly and 15) + BLOCK * (lz and 15))
        return if (bl.w[i] >= MIN_W) bl.sdf[i] else Float.NaN
    }

    /**
     * Naive surface nets. One vertex per cell that straddles the surface, one quad per sign-changing voxel edge.
     * A block owns the edges that start at its voxels, so no quad is produced twice across block borders.
     */
    private fun meshBlock(b: VoxelBlock, loadNeighbors: Boolean): BlockMesh {
        for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
            nb[(dx + 1) + 3 * ((dy + 1) + 3 * (dz + 1))] = when {
                dx == 0 && dy == 0 && dz == 0 -> b
                loadNeighbors -> fetch(b.bx + dx, b.by + dy, b.bz + dz, true)
                else -> blocks[blockKey(b.bx + dx, b.by + dy, b.bz + dz)]
            }
        }
        java.util.Arrays.fill(cellVert, UNSET)
        vN = 0; iN = 0
        for (z in 0 until BLOCK) for (y in 0 until BLOCK) for (x in 0 until BLOCK) {
            val s0 = sdfAt(x, y, z)
            if (s0.isNaN()) continue
            for (axis in 0..2) {
                val s1 = sdfAt(x + (if (axis == 0) 1 else 0), y + (if (axis == 1) 1 else 0), z + (if (axis == 2) 1 else 0))
                if (s1.isNaN() || (s0 < 0f) == (s1 < 0f)) continue
                emitQuad(b, x, y, z, axis)
            }
        }
        return pack(b)
    }

    private fun emitQuad(b: VoxelBlock, x: Int, y: Int, z: Int, axis: Int) {
        val b1 = (axis + 1) % 3
        val b2 = (axis + 2) % 3
        for (q in 0..3) {
            val o1 = if (q == 1 || q == 2) -1 else 0
            val o2 = if (q >= 2) -1 else 0
            var cx = x; var cy = y; var cz = z
            when (b1) { 0 -> cx += o1; 1 -> cy += o1; else -> cz += o1 }
            when (b2) { 0 -> cx += o2; 1 -> cy += o2; else -> cz += o2 }
            val v = cellVertex(b, cx, cy, cz)
            if (v < 0) return
            quad[q] = v
        }
        if (iN + 6 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[iN++] = quad[0]; idx[iN++] = quad[1]; idx[iN++] = quad[2]
        idx[iN++] = quad[0]; idx[iN++] = quad[2]; idx[iN++] = quad[3]
    }

    /** Vertex index for the cell with min corner (cx,cy,cz) (block-local, -1..16), NONE if it has no surface. */
    private fun cellVertex(b: VoxelBlock, cx: Int, cy: Int, cz: Int): Int {
        val ci = (cx + 1) + 18 * ((cy + 1) + 18 * (cz + 1))
        val cached = cellVert[ci]
        if (cached != UNSET) return cached
        for (k in 0..7) {
            val lx = cx + (k and 1); val ly = cy + ((k shr 1) and 1); val lz = cz + ((k shr 2) and 1)
            val bl = nbBlock(lx, ly, lz)
            if (bl == null) { cellVert[ci] = NONE; return NONE }
            val i = (lx and 15) + BLOCK * ((ly and 15) + BLOCK * (lz and 15))
            if (bl.w[i] < MIN_W) { cellVert[ci] = NONE; return NONE }
            cs[k] = bl.sdf[i]; cc[k] = bl.rgb[i]
        }
        var sx = 0f; var sy = 0f; var sz = 0f; var cnt = 0
        for (e in 0 until 12) {
            val a = EDGE_A[e]; val bb = EDGE_B[e]
            val va = cs[a]; val vb = cs[bb]
            if ((va < 0f) != (vb < 0f)) {
                val t = va / (va - vb)
                val ax = (a and 1).toFloat(); val ay = ((a shr 1) and 1).toFloat(); val az = ((a shr 2) and 1).toFloat()
                val bx = (bb and 1).toFloat(); val by = ((bb shr 1) and 1).toFloat(); val bz = ((bb shr 2) and 1).toFloat()
                sx += ax + t * (bx - ax); sy += ay + t * (by - ay); sz += az + t * (bz - az)
                cnt++
            }
        }
        if (cnt == 0) { cellVert[ci] = NONE; return NONE }
        val inv = 1f / cnt
        val px = (b.bx * BLOCK + cx + sx * inv) * voxel
        val py = (b.by * BLOCK + cy + sy * inv) * voxel
        val pz = (b.bz * BLOCK + cz + sz * inv) * voxel

        // Normal = SDF gradient (points out of the surface, towards the camera).
        var gx = (cs[1] - cs[0]) + (cs[3] - cs[2]) + (cs[5] - cs[4]) + (cs[7] - cs[6])
        var gy = (cs[2] - cs[0]) + (cs[3] - cs[1]) + (cs[6] - cs[4]) + (cs[7] - cs[5])
        var gz = (cs[4] - cs[0]) + (cs[5] - cs[1]) + (cs[6] - cs[2]) + (cs[7] - cs[3])
        val gl = sqrt(gx * gx + gy * gy + gz * gz)
        if (gl < 1e-6f) { gx = 0f; gy = -1f; gz = 0f } else { gx /= gl; gy /= gl; gz /= gl }

        var r = 0; var g = 0; var bl2 = 0; var m = 0
        for (k in 0..7) {
            val c = cc[k]
            if (c != 0) { r += (c shr 16) and 255; g += (c shr 8) and 255; bl2 += c and 255; m++ }
        }
        val color = if (m == 0) 0xFF808080.toInt()
        else (0xFF shl 24) or ((r / m) shl 16) or ((g / m) shl 8) or (bl2 / m)

        if (6 * (vN + 1) > vPos.size) vPos = vPos.copyOf(vPos.size * 2)
        if (vN + 1 > vCol.size) vCol = vCol.copyOf(vCol.size * 2)
        val o = 6 * vN
        vPos[o] = px; vPos[o + 1] = py; vPos[o + 2] = pz; vPos[o + 3] = gx; vPos[o + 4] = gy; vPos[o + 5] = gz
        vCol[vN] = color
        cellVert[ci] = vN
        return vN++
    }

    private fun pack(b: VoxelBlock): BlockMesh {
        val bytes = ByteArray(vN * VERTEX_STRIDE)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until vN) {
            val o = 6 * i
            bb.putFloat(vPos[o]); bb.putFloat(vPos[o + 1]); bb.putFloat(vPos[o + 2])
            bb.put((vPos[o + 3] * 127f).toInt().toByte())
            bb.put((vPos[o + 4] * 127f).toInt().toByte())
            bb.put((vPos[o + 5] * 127f).toInt().toByte())
            bb.put(0.toByte())
            val c = vCol[i]
            bb.put((c shr 16).toByte()); bb.put((c shr 8).toByte()); bb.put(c.toByte()); bb.put(255.toByte())
        }
        val vs = voxel * BLOCK
        return BlockMesh(
            b.key, bytes, vN, idx.copyOf(iN),
            (b.bx + 0.5f) * vs, (b.by + 0.5f) * vs, (b.bz + 0.5f) * vs, vs * 0.9f + voxel,
        )
    }

    // ---- persistence + memory ---------------------------------------------------------------------

    private fun blockFile(bx: Int, by: Int, bz: Int) = File(chunkDir, "${bx}_${by}_${bz}.blk")

    private class Pending(val key: Long, val file: File, val bytes: ByteArray)

    /** Writes every block changed since the last call to chunks/<bx>_<by>_<bz>.blk. Returns how many were written. */
    fun saveDirtyBlocks(): Int {
        val dir = chunkDir ?: return 0
        val out = ArrayList<Pending>()
        synchronized(lock) {
            for (b in blocks.values) if (b.saveDirty) {
                out.add(Pending(b.key, blockFile(b.bx, b.by, b.bz), encode(b)))
                b.saveDirty = false
            }
        }
        if (out.isEmpty()) return 0
        dir.mkdirs()
        var written = 0
        for (p in out) {
            try {
                val tmp = File(p.file.path + ".tmp")
                tmp.writeBytes(p.bytes)
                if (!tmp.renameTo(p.file)) { p.file.delete(); tmp.renameTo(p.file) }
                written++
            } catch (e: java.io.IOException) {
                synchronized(lock) { blocks[p.key]?.saveDirty = true } // retry on the next flush
            }
        }
        return written
    }

    /** Drops the least recently touched, already saved blocks once RAM is over budget. */
    fun evictToCap(): Int = synchronized(lock) {
        if (chunkDir == null) 0 else trim(maxBlocks - maxBlocks / 10)
    }

    private fun trim(cap: Int): Int {
        if (blocks.size <= cap) return 0
        val victims = blocks.values.filter { !it.saveDirty && !it.meshDirty }.sortedBy { it.touched }
        var removed = 0
        for (v in victims) {
            if (blocks.size <= cap) break
            blocks.remove(v.key)
            removed++
        }
        return removed
    }

    fun storedKeys(): List<Long> {
        val files = chunkDir?.listFiles() ?: return emptyList()
        val out = ArrayList<Long>(files.size)
        for (f in files) {
            if (!f.name.endsWith(".blk")) continue
            val p = f.name.removeSuffix(".blk").split('_')
            if (p.size != 3) continue
            val x = p[0].toIntOrNull() ?: continue
            val y = p[1].toIntOrNull() ?: continue
            val z = p[2].toIntOrNull() ?: continue
            out.add(blockKey(x, y, z))
        }
        return out
    }

    private fun encode(b: VoxelBlock): ByteArray {
        val raw = ByteBuffer.allocate(8 + BLOCK3 * 6).order(ByteOrder.LITTLE_ENDIAN)
        raw.putInt(MAGIC); raw.putInt(1)
        for (i in 0 until BLOCK3) raw.putShort((b.sdf[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        for (i in 0 until BLOCK3) raw.put(min(255, (b.w[i] * 6f).toInt()).toByte())
        for (i in 0 until BLOCK3) {
            val c = b.rgb[i]
            raw.put((c shr 16).toByte()); raw.put((c shr 8).toByte()); raw.put(c.toByte())
        }
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos).use { it.write(raw.array()) }
        return bos.toByteArray()
    }

    private fun loadBlock(bx: Int, by: Int, bz: Int): VoxelBlock? {
        if (chunkDir == null) return null
        val f = blockFile(bx, by, bz)
        if (!f.exists()) return null
        return try {
            val bytes = InflaterInputStream(FileInputStream(f)).use { it.readBytes() }
            if (bytes.size != 8 + BLOCK3 * 6) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt() != MAGIC || bb.getInt() != 1) return null
            val b = VoxelBlock(bx, by, bz)
            for (i in 0 until BLOCK3) b.sdf[i] = bb.getShort() / 32767f
            for (i in 0 until BLOCK3) b.w[i] = (bb.get().toInt() and 255) / 6f
            for (i in 0 until BLOCK3) {
                val r = bb.get().toInt() and 255; val g = bb.get().toInt() and 255; val bl = bb.get().toInt() and 255
                b.rgb[i] = if (r == 0 && g == 0 && bl == 0) 0 else (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
            b
        } catch (e: Exception) {
            null
        }
    }
}

/** Meshes handed from the mesher thread to the GL thread. Keeps the latest mesh per block so a lost GL context can re-upload. */
class MeshStore {
    private val pending = LinkedHashMap<Long, BlockMesh>()
    private val all = HashMap<Long, BlockMesh>()

    @Volatile var triangles = 0
        private set

    @Synchronized
    fun publish(m: BlockMesh) {
        val old = (if (m.indexCount == 0) all.remove(m.key) else all.put(m.key, m))?.indexCount ?: 0
        pending[m.key] = m
        triangles += (m.indexCount - old) / 3
    }

    @Synchronized
    fun drain(): List<BlockMesh> {
        if (pending.isEmpty()) return emptyList()
        val l = ArrayList(pending.values)
        pending.clear()
        return l
    }

    /** Re-queues everything, for a fresh GL context. */
    @Synchronized
    fun requeueAll() {
        pending.clear()
        pending.putAll(all)
    }
}

/** Background thread that keeps re-meshing dirty blocks while a scan runs. */
class MeshWorker(private val tsdf: ChunkedTsdf, private val store: MeshStore, private val tier: Tier) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mesher").also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY - 1 }
    }

    fun start() {
        exec.scheduleWithFixedDelay({
            runCatching { tsdf.meshDirty(tier.meshBudgetMs, 64) { store.publish(it) } }
        }, 100, 100, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        exec.shutdown()
        runCatching { exec.awaitTermination(5, TimeUnit.SECONDS) }
    }
}
