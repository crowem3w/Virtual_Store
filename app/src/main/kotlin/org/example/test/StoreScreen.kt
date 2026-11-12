package org.example.test

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import org.example.test.data.Shop

/** Fullscreen 3D view of a shop. Drag to orbit, pinch to zoom. */
@Composable
fun StoreScreen(shop: Shop, onExit: () -> Unit) {
    BackHandler(onBack = onExit)

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(x = 0f, y = 2.6f, z = 5.5f)
    }
    val nodes = rememberNodes {
        add(
            ModelNode(
                modelInstance = modelLoader.createModelInstance(assetFileLocation = shop.modelAsset),
                // Fit the model's largest side (6 m for the demo) into 4 scene units.
                scaleToUnits = 4f,
            ),
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            childNodes = nodes,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = cameraNode.worldPosition,
                targetPosition = Position(x = 0f, y = 0.8f, z = 0f),
            ),
        )
        FilledTonalButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
        ) { Text("Leave ${shop.name}") }
    }
}
