package org.example.test

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/** Small rounded-square live view of the real camera. */
@Composable
fun CameraPip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    AndroidView(
        modifier = modifier.clip(shape).border(2.dp, Color.White, shape),
        factory = { ctx ->
            PreviewView(ctx).apply {
                // TextureView-based, so Compose can clip it to rounded corners.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        context as LifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                    )
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
    )
}
