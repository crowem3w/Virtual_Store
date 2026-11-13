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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Orbit viewer for a saved world: drag to rotate, pinch to zoom. */
@Composable
fun WorldViewScreen(info: WorldInfo, onExit: () -> Unit) {
    BackHandler(onBack = onExit)

    var cloud by remember { mutableStateOf<PointCloud?>(null) }
    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0.35f) }
    var dist by remember { mutableFloatStateOf(6f) }
    var target by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f)) }
    val renderer = remember { CloudRenderer() }
    val pointPx = with(LocalDensity.current) { 2.5.dp.toPx() }

    LaunchedEffect(info.id) {
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    yaw -= pan.x * 0.006f
                    pitch = (pitch + pan.y * 0.006f).coerceIn(-1.4f, 1.4f)
                    dist = (dist / zoom).coerceIn(0.5f, 200f)
                }
            },
        ) {
            val c = cloud ?: return@Canvas
            // Camera looks along f, orbiting the target. World frame: x right, y DOWN, z forward.
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
            drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, c, r, t, 0, 0f, pointPx, false) }
        }

        FilledTonalButton(
            onClick = onExit,
            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
        ) { Text("Back") }

        Text(
            when {
                cloud == null -> "Loading…"
                cloud?.count == 0 -> "This world has no points yet."
                else -> "${info.name} · ${cloud?.count} points · drag to look, pinch to zoom"
            },
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        )
    }
}
