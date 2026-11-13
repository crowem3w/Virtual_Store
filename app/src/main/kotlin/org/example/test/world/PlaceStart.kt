package org.example.test.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The saved world seen from above, as ground-plane x,z pairs (world y points down, so it is dropped). */
private class TopDown(val pts: FloatArray, val path: FloatArray) {
    private val minX: Float; private val maxX: Float; private val minZ: Float; private val maxZ: Float

    init {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (a in arrayOf(pts, path)) {
            var i = 0
            while (i + 1 < a.size) {
                x0 = min(x0, a[i]); x1 = max(x1, a[i]); z0 = min(z0, a[i + 1]); z1 = max(z1, a[i + 1])
                i += 2
            }
        }
        if (x0 > x1) { x0 = -1f; x1 = 1f; z0 = -1f; z1 = 1f }
        minX = x0; maxX = x1; minZ = z0; maxZ = z1
    }

    private fun scale(w: Float, h: Float) = min(w / max(maxX - minX, 1f), h / max(maxZ - minZ, 1f)) * 0.9f

    fun toScreen(x: Float, z: Float, w: Float, h: Float): Offset {
        val s = scale(w, h)
        return Offset(w / 2 + (x - (minX + maxX) / 2) * s, h / 2 - (z - (minZ + maxZ) / 2) * s)
    }

    fun toWorld(o: Offset, w: Float, h: Float): Pair<Float, Float> {
        val s = scale(w, h)
        return ((o.x - w / 2) / s + (minX + maxX) / 2) to (-(o.y - h / 2) / s + (minZ + maxZ) / 2)
    }
}

private fun sampleXZ(cloud: PointCloud, maxPoints: Int): FloatArray = synchronized(cloud) {
    val step = max(1, cloud.count / maxPoints)
    val n = (cloud.count + step - 1) / step
    val out = FloatArray(2 * n)
    var j = 0
    var i = 0
    while (i < cloud.count && j < n) {
        out[2 * j] = cloud.xyz[3 * i]; out[2 * j + 1] = cloud.xyz[3 * i + 2]
        j++; i += step
    }
    out
}

/**
 * Fallback for resuming when the camera could not find its place: the user taps where they stand on a
 * top-down view of the saved world and sets the direction they face. [onConfirm] gets world x, z and the
 * heading in radians (0 = towards the top of the screen, growing clockwise).
 */
@Composable
fun PlaceStartPicker(
    cloud: PointCloud,
    path: FloatArray,
    onConfirm: (Float, Float, Double) -> Unit,
    onCancel: () -> Unit,
) {
    val view = remember(cloud, path) { TopDown(sampleXZ(cloud, 30_000), path) }
    var tap by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var headingDeg by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Text(
            "Tap where you are standing now, then set the direction you are facing.",
            color = Color.White,
            modifier = Modifier.padding(12.dp),
        )
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(view) {
                    detectTapGestures { o ->
                        tap = view.toWorld(o, size.width.toFloat(), size.height.toFloat())
                    }
                },
        ) {
            val w = size.width; val h = size.height
            val pts = ArrayList<Offset>(view.pts.size / 2)
            var i = 0
            while (i + 1 < view.pts.size) { pts.add(view.toScreen(view.pts[i], view.pts[i + 1], w, h)); i += 2 }
            drawPoints(pts, PointMode.Points, Color(0xFF9E9E9E), 3f)
            val trail = ArrayList<Offset>(view.path.size / 2)
            i = 0
            while (i + 1 < view.path.size) { trail.add(view.toScreen(view.path[i], view.path[i + 1], w, h)); i += 2 }
            drawPoints(trail, PointMode.Points, Color(0xFF4FC3F7), 7f)

            tap?.let { (x, z) ->
                val c = view.toScreen(x, z, w, h)
                val a = Math.toRadians(headingDeg.toDouble())
                val tip = Offset(c.x + (sin(a) * 60).toFloat(), c.y - (cos(a) * 60).toFloat())
                drawLine(Color(0xFFFFB300), c, tip, strokeWidth = 8f)
                drawCircle(Color(0xFFFFB300), radius = 14f, center = c)
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text("Facing: ${headingDeg.toInt()}° (0° = up on the map)", color = Color.White)
            Slider(value = headingDeg, onValueChange = { headingDeg = it }, valueRange = 0f..359f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = tap != null,
                    onClick = { tap?.let { (x, z) -> onConfirm(x, z, Math.toRadians(headingDeg.toDouble())) } },
                ) { Text("Start here") }
                FilledTonalButton(onClick = onCancel, modifier = Modifier.padding(start = 12.dp)) { Text("Cancel") }
            }
        }
    }
}
