package org.example.test.world

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orbit viewer for a saved world: drag to rotate, pinch to zoom.
 * Worlds with saved TSDF chunks are meshed from disk on open and drawn with OpenGL; older point-only worlds
 * fall back to the point renderer.
 */
@Composable
fun WorldViewScreen(info: WorldInfo, onExit: () -> Unit) {
    BackHandler(onBack = onExit)

    val useMesh = info.chunks > 0 && info.voxel > 0f
    var cloud by remember { mutableStateOf<PointCloud?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0.35f) }
    var dist by remember { mutableFloatStateOf(6f) }
    var target by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f)) }
    val renderer = remember { CloudRenderer() }
    val meshStore = remember { MeshStore() }
    val glRef = remember { arrayOfNulls<MeshGlView>(1) }
    val pointPx = with(LocalDensity.current) { 2.5.dp.toPx() }

    LaunchedEffect(info.id) {
        if (useMesh) {
            withContext(Dispatchers.IO) {
                val tsdf = ChunkedTsdf(info.voxel, info.chunksDir)
                val keys = tsdf.storedKeys()
                if (keys.isNotEmpty()) {
                    val vs = info.voxel * BLOCK
                    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
                    var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
                    for (k in keys) {
                        val cx = (keyBx(k) + 0.5f) * vs; val cy = (keyBy(k) + 0.5f) * vs; val cz = (keyBz(k) + 0.5f) * vs
                        x0 = minOf(x0, cx); x1 = maxOf(x1, cx)
                        y0 = minOf(y0, cy); y1 = maxOf(y1, cy)
                        z0 = minOf(z0, cz); z1 = maxOf(z1, cz)
                    }
                    target = floatArrayOf((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
                    dist = (max(max(x1 - x0, y1 - y0), z1 - z0) * 1.1f + 3f).coerceAtLeast(3f)
                }
                tsdf.meshFromDisk({ meshStore.publish(it) }, { done, total -> progress = done to total })
            }
        } else {
            val loaded = withContext(Dispatchers.IO) { PointCloud.load(info.pointsFile) }
            val n = loaded.count
            if (n > 0) {
                var cx = 0.0; var cy = 0.0; var cz = 0.0
                for (i in 0 until n) { cx += loaded.xyz[3 * i]; cy += loaded.xyz[3 * i + 1]; cz += loaded.xyz[3 * i + 2] }
                cx /= n; cy /= n; cz /= n
                var ss = 0.0
                for (i in 0 until n) {
                    val dx = loaded.xyz[3 * i] - cx; val dy = loaded.xyz[3 * i + 1] - cy; val dz = loaded.xyz[3 * i + 2] - cz
                    ss += dx * dx + dy * dy + dz * dz
                }
                target = floatArrayOf(cx.toFloat(), cy.toFloat(), cz.toFloat())
                dist = (sqrt(ss / n) * 2.5).toFloat().coerceAtLeast(2f)
            }
            cloud = loaded
        }
    }

    // Mesh view: ~30 fps redraw with the current orbit camera.
    LaunchedEffect(useMesh) {
        if (!useMesh) return@LaunchedEffect
        while (isActive) {
            delay(33)
            val v = glRef[0] ?: continue
            val (r, t) = orbit(yaw, pitch, dist, target)
            v.renderer.setView(r, t, 0, 0f, false)
            v.requestRender()
        }
    }

    val gestures = Modifier.fillMaxSize().pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
            yaw -= pan.x * 0.006f
            pitch = (pitch + pan.y * 0.006f).coerceIn(-1.4f, 1.4f)
            dist = (dist / zoom).coerceIn(0.5f, 200f)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (useMesh) {
            AndroidView(
                factory = { ctx ->
                    MeshGlView(ctx).also {
                        it.renderer.store = meshStore
                        (ctx as? androidx.lifecycle.LifecycleOwner)?.let { o -> it.bindLifecycle(o) }
                        glRef[0] = it
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Box(gestures) // transparent touch layer above the GL surface
        } else {
            Canvas(gestures) {
                val c = cloud ?: return@Canvas
                val (r, t) = orbit(yaw, pitch, dist, target)
                drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, c, r, t, 0, 0f, pointPx, false) }
            }
        }

        FilledTonalButton(
            onClick = onExit,
            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
        ) { Text("Back") }

        val status = when {
            useMesh -> {
                val p = progress
                when {
                    p == null -> "Building mesh…"
                    p.first < p.second -> "Building mesh… ${p.first}/${p.second} blocks"
                    else -> "${info.name} · ${meshStore.triangles} triangles · drag to look, pinch to zoom"
                }
            }
            cloud == null -> "Loading…"
            cloud?.count == 0 -> "This world has no points yet."
            else -> "${info.name} · ${cloud?.count} points · drag to look, pinch to zoom"
        }
        Text(
            status,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        )
    }
}

/**
 * Camera looking along f, orbiting [target] at [dist]. World frame: x right, y DOWN, z forward.
 * Returns the world -> camera rotation (row-major 3x3) and translation.
 */
private fun orbit(yaw: Float, pitch: Float, dist: Float, target: FloatArray): Pair<DoubleArray, DoubleArray> {
    val fx = cos(pitch) * sin(yaw); val fy = sin(pitch); val fz = cos(pitch) * cos(yaw)
    var rx = fz; var rz = -fx // right = down(0,1,0) x f
    val rl = sqrt(rx * rx + rz * rz).coerceAtLeast(1e-4f); rx /= rl; rz /= rl
    // down_cam = f x right
    val dx = fy * rz - fz * 0f; val dy = fz * rx - fx * rz; val dz = fx * 0f - fy * rx
    val r = doubleArrayOf(
        rx.toDouble(), 0.0, rz.toDouble(),
        dx.toDouble(), dy.toDouble(), dz.toDouble(),
        fx.toDouble(), fy.toDouble(), fz.toDouble(),
    )
    val cxp = target[0] - dist * fx; val cyp = target[1] - dist * fy; val czp = target[2] - dist * fz
    val t = doubleArrayOf(
        -(r[0] * cxp + r[1] * cyp + r[2] * czp),
        -(r[3] * cxp + r[4] * cyp + r[5] * czp),
        -(r[6] * cxp + r[7] * cyp + r[8] * czp),
    )
    return r to t
}
