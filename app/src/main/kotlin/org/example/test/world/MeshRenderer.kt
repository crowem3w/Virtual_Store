package org.example.test.world

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

/** A GLSurfaceView that draws the [MeshStore] given to its [renderer]. Call requestRender() to redraw. */
class MeshGlView(context: Context) : GLSurfaceView(context) {
    val renderer = MeshRenderer()

    private var owner: LifecycleOwner? = null
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_PAUSE -> onPause()
            Lifecycle.Event.ON_RESUME -> onResume()
            else -> {}
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(true) // RGB + 16-bit depth
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Pauses/resumes the GL thread with the activity (GLSurfaceView needs this to release the context). */
    fun bindLifecycle(o: LifecycleOwner) {
        owner?.lifecycle?.removeObserver(observer)
        owner = o
        o.lifecycle.addObserver(observer)
    }

    override fun onDetachedFromWindow() {
        owner?.lifecycle?.removeObserver(observer)
        owner = null
        super.onDetachedFromWindow()
    }
}

private class ViewParams(val r: DoubleArray, val t: DoubleArray, val rotDeg: Int, val back: Float, val marker: Boolean)

/**
 * Draws block meshes with a depth buffer and a simple two-sided light. The camera uses the same convention as
 * the point renderer: [r]/[t] map the world (x right, y down, z forward) to camera axes, [rotDeg] turns the
 * image upright, [back] pushes the scene away from the camera for a third-person view.
 */
class MeshRenderer : GLSurfaceView.Renderer {
    @Volatile var store: MeshStore? = null
    @Volatile private var view = ViewParams(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0), 0, 0f, false,
    )

    fun setView(r: DoubleArray, t: DoubleArray, rotDeg: Int, back: Float, marker: Boolean) {
        view = ViewParams(r, t, rotDeg, back, marker)
    }

    private class GlBlock(val vao: Int, val vbo: Int, val ibo: Int, val count: Int, val cx: Float, val cy: Float, val cz: Float, val radius: Float)

    private val blocks = HashMap<Long, GlBlock>()
    private var program = 0
    private var uMvp = 0
    private var uLit = 0
    private var width = 1
    private var height = 1
    private val mvp = FloatArray(16)
    private var markerVao = 0
    private var markerVbo = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        blocks.clear() // a new GL context owns none of the old buffers
        program = link(VERTEX, FRAGMENT)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uLit = GLES30.glGetUniformLocation(program, "uLit")
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        createMarker()
        store?.requeueAll()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = maxOf(w, 1); height = maxOf(h, 1)
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        store?.drain()?.forEach { upload(it) }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val v = view
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uLit, 1f)
        buildMvp(mvp, v.r, v.t, v.rotDeg, v.back, width, height)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        val r6 = v.r[6].toFloat(); val r7 = v.r[7].toFloat(); val r8 = v.r[8].toFloat()
        val tz = v.t[2].toFloat() + v.back
        for (b in blocks.values) {
            val zc = r6 * b.cx + r7 * b.cy + r8 * b.cz + tz
            if (zc < -b.radius) continue // entirely behind the camera
            GLES30.glBindVertexArray(b.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, b.count, GLES30.GL_UNSIGNED_INT, 0)
        }

        if (v.marker) {
            // The camera frustum is defined in camera coordinates: identity pose, same back/rotation.
            buildMvp(mvp, IDENTITY, ZERO, v.rotDeg, v.back, width, height)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES30.glUniform1f(uLit, 0f)
            GLES30.glBindVertexArray(markerVao)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, MARKER_VERTS)
        }
        GLES30.glBindVertexArray(0)
    }

    private fun upload(m: BlockMesh) {
        blocks.remove(m.key)?.let { free(it) }
        if (m.indexCount == 0) return
        val ids = IntArray(3)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(2, ids, 1)
        GLES30.glBindVertexArray(ids[0])

        val vb = ByteBuffer.allocateDirect(m.vertexBytes.size).order(ByteOrder.nativeOrder())
        vb.put(m.vertexBytes).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertexBytes.size, vb, GLES30.GL_STATIC_DRAW)
        setAttribs()

        val ib = ByteBuffer.allocateDirect(m.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(m.indices).position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[2])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)

        GLES30.glBindVertexArray(0)
        blocks[m.key] = GlBlock(ids[0], ids[1], ids[2], m.indexCount, m.cx, m.cy, m.cz, m.radius)
    }

    /** Layout of one vertex: float3 position, snorm8x3 normal (+pad), unorm8x4 colour. 20 bytes. */
    private fun setAttribs() {
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_BYTE, true, STRIDE, 12)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, STRIDE, 16)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
    }

    private fun free(b: GlBlock) {
        GLES30.glDeleteVertexArrays(1, intArrayOf(b.vao), 0)
        GLES30.glDeleteBuffers(2, intArrayOf(b.vbo, b.ibo), 0)
    }

    private fun createMarker() {
        val d = 0.5f
        val o = floatArrayOf(0f, 0f, 0f)
        val c = arrayOf(
            floatArrayOf(-0.35f, -0.26f, d), floatArrayOf(0.35f, -0.26f, d),
            floatArrayOf(0.35f, 0.26f, d), floatArrayOf(-0.35f, 0.26f, d),
        )
        val pts = ArrayList<FloatArray>()
        for (p in c) { pts.add(o); pts.add(p) }
        for (i in 0..3) { pts.add(c[i]); pts.add(c[(i + 1) % 4]) }
        val bb = ByteBuffer.allocateDirect(pts.size * STRIDE).order(ByteOrder.nativeOrder())
        for (p in pts) {
            bb.putFloat(p[0]); bb.putFloat(p[1]); bb.putFloat(p[2])
            repeat(4) { bb.put(0.toByte()) } // normal (unused for lines) + pad
            repeat(4) { bb.put((-1).toByte()) } // white
        }
        bb.position(0)
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        markerVao = ids[0]; markerVbo = ids[1]
        GLES30.glBindVertexArray(markerVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, markerVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, pts.size * STRIDE, bb, GLES30.GL_STATIC_DRAW)
        setAttribs()
        GLES30.glBindVertexArray(0)
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "GL link failed: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "GL shader failed: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        private const val STRIDE = 20
        private const val MARKER_VERTS = 16
        private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        private val ZERO = doubleArrayOf(0.0, 0.0, 0.0)

        private const val VERTEX = """#version 300 es
uniform mat4 uMvp;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNrm;
layout(location = 2) in vec4 aCol;
out vec3 vNrm;
out vec3 vCol;
void main() {
    vNrm = aNrm;
    vCol = aCol.rgb;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""
        private const val FRAGMENT = """#version 300 es
precision mediump float;
uniform float uLit;
in vec3 vNrm;
in vec3 vCol;
out vec4 oColor;
void main() {
    vec3 n = normalize(vNrm);
    float d = abs(dot(n, normalize(vec3(0.35, -0.8, -0.5))));
    float l = mix(1.0, 0.45 + 0.55 * d, uLit);
    oColor = vec4(vCol * l, 1.0);
}
"""
    }
}

/**
 * Column-major 4x4 for GL: world -> camera ([r] row-major 3x3, [t]) -> push back by [back] -> rotate the image
 * by [rotDeg] -> pinhole projection with focal length min(w,h) pixels (same as the Canvas point renderer).
 */
internal fun buildMvp(out: FloatArray, r: DoubleArray, t: DoubleArray, rotDeg: Int, back: Float, w: Int, h: Int) {
    val f = min(w, h).toDouble()
    val near = 0.1; val far = 80.0
    val xr = doubleArrayOf(r[0], r[1], r[2], t[0])
    val yr = doubleArrayOf(r[3], r[4], r[5], t[1])
    val zr = doubleArrayOf(r[6], r[7], r[8], t[2] + back)
    val xv: DoubleArray; val yv: DoubleArray
    when (rotDeg) {
        90 -> { xv = DoubleArray(4) { -yr[it] }; yv = xr }
        180 -> { xv = DoubleArray(4) { -xr[it] }; yv = DoubleArray(4) { -yr[it] } }
        270 -> { xv = yr; yv = DoubleArray(4) { -xr[it] } }
        else -> { xv = xr; yv = yr }
    }
    val a = (far + near) / (far - near)
    val b = -2.0 * far * near / (far - near)
    val rows = arrayOf(
        DoubleArray(4) { xv[it] * 2.0 * f / w },
        DoubleArray(4) { -yv[it] * 2.0 * f / h },
        DoubleArray(4) { a * zr[it] + (if (it == 3) b else 0.0) },
        zr,
    )
    for (row in 0..3) for (col in 0..3) out[col * 4 + row] = rows[row][col].toFloat()
}
