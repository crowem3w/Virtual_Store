package org.example.test.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.sqrt

/** Fuses depth rays of a synthetic sphere from three viewpoints and checks the extracted mesh. */
class TsdfTest {
    private val cx = 0.0; private val cy = 0.0; private val cz = 3.0; private val radius = 1.0

    private class Rays(val data: FloatArray, val colors: IntArray, val n: Int)

    private fun sphereRays(ox: Double, oy: Double, oz: Double, rnd: Random): Rays {
        val data = FloatArray(4 * 80 * 107); val colors = IntArray(80 * 107)
        var n = 0
        for (v in 0 until 480 step 6) for (u in 0 until 640 step 6) {
            val dx = (u - 320) / 500.0; val dy = (v - 240) / 500.0; val dz = 1.0
            val l = sqrt(dx * dx + dy * dy + dz * dz)
            val d = doubleArrayOf(dx / l, dy / l, dz / l)
            val oc = doubleArrayOf(ox - cx, oy - cy, oz - cz)
            val b = oc[0] * d[0] + oc[1] * d[1] + oc[2] * d[2]
            val c = oc[0] * oc[0] + oc[1] * oc[1] + oc[2] * oc[2] - radius * radius
            val disc = b * b - c
            if (disc < 0) continue
            val t = -b - sqrt(disc) + rnd.nextGaussian() * 0.01 // 1 cm depth noise
            if (t <= 0) continue
            data[4 * n] = d[0].toFloat(); data[4 * n + 1] = d[1].toFloat(); data[4 * n + 2] = d[2].toFloat()
            data[4 * n + 3] = t.toFloat()
            colors[n] = 0xFF808080.toInt()
            n++
        }
        return Rays(data, colors, n)
    }

    private fun fuse(tsdf: ChunkedTsdf) {
        val rnd = Random(1)
        for (o in arrayOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.4, 0.0, 0.0), doubleArrayOf(-0.4, 0.1, 0.1))) {
            val r = sphereRays(o[0], o[1], o[2], rnd)
            tsdf.integrateFrame(o[0].toFloat(), o[1].toFloat(), o[2].toFloat(), r.data, r.colors, r.n)
        }
    }

    private fun vertexRadiusErrors(meshes: List<BlockMesh>): List<Double> {
        val errs = ArrayList<Double>()
        for (m in meshes) {
            val bb = ByteBuffer.wrap(m.vertexBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until m.vertexCount) {
                val x = bb.getFloat(i * 20) - cx; val y = bb.getFloat(i * 20 + 4) - cy; val z = bb.getFloat(i * 20 + 8) - cz
                errs.add(kotlin.math.abs(sqrt(x * x + y * y + z * z) - radius))
            }
        }
        return errs.sorted()
    }

    @Test
    fun reconstructsSphere() {
        val tsdf = ChunkedTsdf(0.04f)
        fuse(tsdf)
        val meshes = ArrayList<BlockMesh>()
        tsdf.meshDirty(Long.MAX_VALUE, Int.MAX_VALUE) { meshes.add(it) }
        assertEquals(0, tsdf.dirtyCount)
        val tris = meshes.sumOf { it.indexCount / 3 }
        assertTrue("triangles: $tris", tris > 1500)
        val errs = vertexRadiusErrors(meshes)
        assertTrue("median error ${errs[errs.size / 2]}", errs[errs.size / 2] < 0.02)
        assertTrue("95% error ${errs[(errs.size * 0.95).toInt()]}", errs[(errs.size * 0.95).toInt()] < 0.05)
    }

    @Test
    fun savedChunksMeshTheSameAfterReload() {
        val dir = File.createTempFile("chunks", "").also { it.delete(); it.mkdirs() }
        try {
            val tsdf = ChunkedTsdf(0.04f, dir)
            fuse(tsdf)
            val live = ArrayList<BlockMesh>()
            tsdf.meshDirty(Long.MAX_VALUE, Int.MAX_VALUE) { live.add(it) }
            val written = tsdf.saveDirtyBlocks()
            assertTrue(written > 0)
            assertEquals(0, tsdf.saveDirtyBlocks()) // nothing changed since

            val reloaded = ChunkedTsdf(0.04f, dir)
            assertEquals(written, reloaded.storedKeys().size)
            val disk = ArrayList<BlockMesh>()
            reloaded.meshFromDisk({ disk.add(it) }, { _, _ -> })
            val a = live.sumOf { it.indexCount / 3 }.toDouble()
            val b = disk.sumOf { it.indexCount / 3 }.toDouble()
            assertTrue("live $a vs disk $b", b > 0.9 * a && b < 1.1 * a)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun blockKeysRoundTripNegativeCoordinates() {
        val k = blockKey(-5, 12, -1)
        assertEquals(-5, keyBx(k)); assertEquals(12, keyBy(k)); assertEquals(-1, keyBz(k))
    }
}
