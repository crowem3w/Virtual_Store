package org.example.test

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import org.example.test.data.Shop
import org.example.test.data.ShopRegistry
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

private const val EYE_HEIGHT = 1.6f // metres

/**
 * Fullscreen first-person 3D store. The 3D camera follows the phone's
 * orientation; the joystick walks. A small live camera view sits top-right.
 * 1 scene unit = 1 metre, so the scanned model must be exported in metres.
 */
@Composable
fun StoreScreen(shop: Shop, registry: ShopRegistry, hasCamera: Boolean, onExit: () -> Unit) {
    BackHandler(onBack = onExit)

    val context = LocalContext.current
    val tracker = remember { OrientationTracker(context) }
    DisposableEffect(tracker) {
        tracker.start()
        onDispose { tracker.stop() }
    }
    // Scanned models have an arbitrary origin: start at 0,0. The placeholder room starts a bit inside.
    val walker = remember { Walker(startZ = if (shop.modelFile != null) 0f else 1.5f) }
    val stick = remember { floatArrayOf(0f, 0f) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(x = walker.x, y = EYE_HEIGHT, z = walker.z)
    }
    var scale by remember { mutableFloatStateOf(shop.modelScale) }
    var yOffset by remember { mutableFloatStateOf(shop.modelYOffset) }
    var showAdjust by remember { mutableStateOf(false) }

    val nodes = rememberNodes {
        val file = shop.modelFile
        val instance = if (file != null) {
            // Memory-map the file: avoids copying a large scan onto the Java heap.
            val buffer = RandomAccessFile(file, "r").use {
                it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
            }
            modelLoader.createModelInstance(buffer = buffer)
        } else {
            modelLoader.createModelInstance(assetFileLocation = shop.modelAsset)
        }
        add(
            ModelNode(modelInstance = instance).apply {
                this.scale = Scale(shop.modelScale)
                position = Position(y = shop.modelYOffset)
            },
        )
    }

    fun applyTransform(newScale: Float, newY: Float) {
        scale = newScale; yOffset = newY
        nodes.firstOrNull()?.let {
            it.scale = Scale(newScale)
            it.position = Position(y = newY)
        }
        registry.saveTransform(shop.id, newScale, newY)
    }

    Box(Modifier.fillMaxSize()) {
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            childNodes = nodes,
            cameraManipulator = null, // we drive the camera ourselves (sensor + joystick)
            onFrame = { now ->
                cameraNode.quaternion = walker.update(now, tracker, stick[0], stick[1])
                cameraNode.position = Position(x = walker.x, y = EYE_HEIGHT, z = walker.z)
            },
        )

        Row(
            Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
        ) {
            FilledTonalButton(onClick = onExit) { Text("Leave") }
            FilledTonalButton(
                onClick = { walker.needsRecenter = true },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Recenter") }
        }

        if (hasCamera) {
            CameraPip(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .size(104.dp),
            )
        }

        if (shop.modelFile != null) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (showAdjust) {
                    Row {
                        FilledTonalButton(onClick = { applyTransform(scale / 1.1f, yOffset) }) { Text("Smaller") }
                        FilledTonalButton(onClick = { applyTransform(scale * 1.1f, yOffset) }, modifier = Modifier.padding(start = 6.dp)) { Text("Bigger") }
                    }
                    Row {
                        FilledTonalButton(onClick = { applyTransform(scale, yOffset - 0.1f) }) { Text("Model ↓") }
                        FilledTonalButton(onClick = { applyTransform(scale, yOffset + 0.1f) }, modifier = Modifier.padding(start = 6.dp)) { Text("Model ↑") }
                    }
                }
                FilledTonalButton(onClick = { showAdjust = !showAdjust }) { Text(if (showAdjust) "Done" else "Adjust model") }
            }
        }

        Joystick(
            onMove = { x, y -> stick[0] = x; stick[1] = y },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
        )
    }
}
