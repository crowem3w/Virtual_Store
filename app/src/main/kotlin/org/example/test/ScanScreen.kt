package org.example.test

import androidx.activity.compose.BackHandler
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.example.test.data.ScanStore
import org.example.test.data.WifiAp
import java.util.concurrent.Executors

/** Guided capture: live preview, record button, frame counter and a view-coverage map. */
@Composable
fun ScanScreen(store: ScanStore, wifi: List<WifiAp>, onExit: () -> Unit) {
    val context = LocalContext.current
    val tracker = remember { OrientationTracker(context) }
    DisposableEffect(tracker) {
        tracker.start()
        onDispose { tracker.stop() }
    }
    val recorder = remember { ScanRecorder(context, store, tracker) }
    val latestWifi = rememberUpdatedState(wifi)
    val executor = remember { Executors.newSingleThreadExecutor() }

    fun leave() { recorder.stop(); onExit() }
    BackHandler(onBack = ::leave)

    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    keepScreenOn = true
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            android.util.Size(1280, 960),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                        ),
                                    )
                                    .build(),
                            )
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(executor, recorder) }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            context as LifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )

        Row(
            Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
        ) {
            FilledTonalButton(onClick = ::leave) { Text("Leave") }
            FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.padding(start = 8.dp)) {
                Text("${recorder.frames} / ${ScanRecorder.MAX_FRAMES} frames")
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(recorder.hint, color = Color.White)
            Text(
                "Directions covered: ${recorder.levelCoverage()} / ${ScanRecorder.YAW_SECTORS}",
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp),
            )
            CoverageMap(recorder, Modifier.padding(vertical = 8.dp).size(width = 288.dp, height = 36.dp))
            Button(
                onClick = { if (recorder.recording) recorder.stop() else recorder.start(latestWifi.value) },
            ) { Text(if (recorder.recording) "Stop" else "Start scan") }
        }
    }
}

/** 3 rows (up / level / down) x 36 compass directions; filled cell = we have a frame there. */
@Composable
private fun CoverageMap(recorder: ScanRecorder, modifier: Modifier) {
    Canvas(modifier) {
        val cols = ScanRecorder.YAW_SECTORS
        val rows = ScanRecorder.PITCH_BANDS
        val cw = size.width / cols
        val ch = size.height / rows
        for (r in 0 until rows) for (c in 0 until cols) {
            val covered = recorder.coverage[r * cols + c]
            drawRect(
                color = if (covered) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.25f),
                topLeft = Offset(c * cw + 0.5f, r * ch + 0.5f),
                size = Size(cw - 1f, ch - 1f),
            )
        }
    }
}
