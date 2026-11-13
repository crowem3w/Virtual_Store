package org.example.test.recon

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a binary glTF 2.0 (.glb) with one mesh: POSITION, NORMAL, COLOR_0 (normalised RGBA bytes) and
 * 32-bit indices. The material is KHR_materials_unlit because the vertex colours already contain the
 * shop's real lighting, and the store scene has no lights of its own.
 *
 * Layout of the single binary buffer: indices | positions | normals | colours (each a multiple of 4 bytes).
 */
object GlbWriter {
    private const val MAGIC = 0x46546C67      // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942  // "BIN\0"

    fun write(out: File, mesh: MeshData) {
        require(mesh.vertexCount > 0 && mesh.triangleCount > 0) { "Empty mesh" }
        val nV = mesh.vertexCount
        val nI = mesh.indices.size

        val idxBytes = nI * 4
        val posBytes = nV * 12
        val nrmBytes = nV * 12
        val colBytes = nV * 4
        val binLen = idxBytes + posBytes + nrmBytes + colBytes

        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (v in 0 until nV) for (a in 0..2) {
            val p = mesh.positions[v * 3 + a]
            if (p < mn[a]) mn[a] = p
            if (p > mx[a]) mx[a] = p
        }

        val json = """{"asset":{"version":"2.0","generator":"locStore-recon"},""" +
            """"extensionsUsed":["KHR_materials_unlit"],""" +
            """"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],""" +
            """"meshes":[{"primitives":[{"attributes":{"POSITION":1,"NORMAL":2,"COLOR_0":3},"indices":0,"material":0,"mode":4}]}],""" +
            """"materials":[{"pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1],"metallicFactor":0,"roughnessFactor":1},""" +
            """"doubleSided":true,"extensions":{"KHR_materials_unlit":{}}}],""" +
            """"accessors":[""" +
            """{"bufferView":0,"componentType":5125,"count":$nI,"type":"SCALAR"},""" +
            """{"bufferView":1,"componentType":5126,"count":$nV,"type":"VEC3","min":[${mn[0]},${mn[1]},${mn[2]}],"max":[${mx[0]},${mx[1]},${mx[2]}]},""" +
            """{"bufferView":2,"componentType":5126,"count":$nV,"type":"VEC3"},""" +
            """{"bufferView":3,"componentType":5121,"normalized":true,"count":$nV,"type":"VEC4"}],""" +
            """"bufferViews":[""" +
            """{"buffer":0,"byteOffset":0,"byteLength":$idxBytes,"target":34963},""" +
            """{"buffer":0,"byteOffset":$idxBytes,"byteLength":$posBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":${idxBytes + posBytes},"byteLength":$nrmBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":${idxBytes + posBytes + nrmBytes},"byteLength":$colBytes,"target":34962}],""" +
            """"buffers":[{"byteLength":$binLen}]}"""

        var jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        if (jsonPad > 0) jsonBytes = jsonBytes + ByteArray(jsonPad) { ' '.code.toByte() }

        val total = 12 + 8 + jsonBytes.size + 8 + binLen
        FileOutputStream(out).channel.use { ch ->
            val head = ByteBuffer.allocate(12 + 8).order(ByteOrder.LITTLE_ENDIAN)
            head.putInt(MAGIC).putInt(2).putInt(total)
            head.putInt(jsonBytes.size).putInt(CHUNK_JSON)
            head.flip(); ch.write(head)
            ch.write(ByteBuffer.wrap(jsonBytes))

            val binHead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            binHead.putInt(binLen).putInt(CHUNK_BIN)
            binHead.flip(); ch.write(binHead)

            val idx = ByteBuffer.allocate(idxBytes).order(ByteOrder.LITTLE_ENDIAN)
            idx.asIntBuffer().put(mesh.indices); ch.write(idx)
            val pos = ByteBuffer.allocate(posBytes).order(ByteOrder.LITTLE_ENDIAN)
            pos.asFloatBuffer().put(mesh.positions); ch.write(pos)
            val nrm = ByteBuffer.allocate(nrmBytes).order(ByteOrder.LITTLE_ENDIAN)
            nrm.asFloatBuffer().put(mesh.normals); ch.write(nrm)
            ch.write(ByteBuffer.wrap(mesh.colors))
        }
    }
}
