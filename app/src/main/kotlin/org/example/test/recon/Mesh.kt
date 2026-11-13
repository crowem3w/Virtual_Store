package org.example.test.recon

/** Triangle mesh in scene axes (+Y up, metres). Colours are RGBA bytes. */
class MeshData(
    val positions: FloatArray, // xyz per vertex
    val normals: FloatArray,   // xyz per vertex
    val colors: ByteArray,     // rgba per vertex
    val indices: IntArray,     // 3 per triangle, counter-clockwise seen from outside
) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3
}

/** Small growable primitive arrays: a mesh has hundreds of thousands of entries, so no boxing. */
class FloatVec {
    var a = FloatArray(1024); private set
    var size = 0; private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun toArray(): FloatArray = a.copyOf(size)
}

class IntVec {
    var a = IntArray(1024); private set
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun toArray(): IntArray = a.copyOf(size)
}

class ByteVec {
    var a = ByteArray(1024); private set
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v.toByte() }
    fun toArray(): ByteArray = a.copyOf(size)
}
