package org.example.test

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import org.example.test.data.Shop

private const val EYE_HEIGHT = 1.6f // metres

/**
 * Fullscreen first-person 3D store. The 3D camera follows the phone's
 * orientation; the joystick walks. A small live camera view sits top-right.
 * 1 scene unit = 1 metre, so the scanned model must be exported in metres.
 */
@Composable
fun StoreScreen(shop: Shop, hasCamera: Boolean, onExit: () -> Unit) {
    BackHandler(onBack = onExit)

    val context = LocalContext.current
    val tracker = remember { OrientationTracker(context) }
    DisposableEffect(tracker) {
        tracker.start()
        onDispose { tracker.stop() }
    }
    val walker = remember { Walker() }
    val stick = remember { floatArrayOf(0f, 0f) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(x = walker.x, y = EYE_HEIGHT, z = walker.z)
    }
    val nodes = rememberNodes {
        // No scaleToUnits: keep the model's real-world scale (metres).
        add(ModelNode(modelInstance = modelLoader.createModelInstance(assetFileLocation = shop.modelAsset)))
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

        Joystick(
            onMove = { x, y -> stick[0] = x; stick[1] = y },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
        )
    }
}
