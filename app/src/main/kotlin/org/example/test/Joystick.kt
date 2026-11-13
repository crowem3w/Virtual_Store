package org.example.test

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Virtual joystick. [onMove] gets x (right) and y (forward), each in -1..1. */
@Composable
fun Joystick(onMove: (x: Float, y: Float) -> Unit, modifier: Modifier = Modifier) {
    val radiusPx = with(LocalDensity.current) { 44.dp.toPx() }
    var knob by remember { mutableStateOf(Offset.Zero) }

    fun release() { knob = Offset.Zero; onMove(0f, 0f) }

    Box(
        modifier = modifier
            .size(120.dp)
            .background(Color.Black.copy(alpha = 0.25f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, drag ->
                    change.consume()
                    val next = knob + drag
                    val len = next.getDistance()
                    knob = if (len > radiusPx) next * (radiusPx / len) else next
                    onMove(knob.x / radiusPx, -knob.y / radiusPx)
                }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .size(48.dp)
                .background(Color.White.copy(alpha = 0.7f), CircleShape),
        )
    }
}
