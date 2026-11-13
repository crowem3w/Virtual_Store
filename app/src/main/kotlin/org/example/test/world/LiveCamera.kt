package org.example.test.world

import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.example.test.OrientationTracker
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.ExecutorService

/** Feeds camera frames (RGBA, sensor orientation) into the current [LiveSlam], if a scan is running. */
class LiveAnalyzer(private val tracker: OrientationTracker) : ImageAnalysis.Analyzer {
    @Volatile var slam: LiveSlam? = null

    private var bytes = ByteArray(0)
    private var mat: Mat? = null
    private val rot = FloatArray(9)

    override fun analyze(image: ImageProxy) {
        try {
            val s = slam ?: return
            val w = image.width; val h = image.height
            val plane = image.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            if (bytes.size != w * h * 4) { bytes = ByteArray(w * h * 4); mat?.release(); mat = null }
            if (rs == w * 4) {
                buf.rewind()
                buf.get(bytes, 0, minOf(bytes.size, buf.remaining()))
            } else {
                for (row in 0 until h) {
                    buf.position(row * rs)
                    buf.get(bytes, row * w * 4, w * 4)
                }
            }
            val m = mat ?: Mat(h, w, CvType.CV_8UC4).also { mat = it }
            m.put(0, 0, bytes)
            val dev = if (tracker.hasData) rot.also { tracker.rawMatrix(it) } else null
            s.process(m, image.imageInfo.rotationDegrees, dev)
        } catch (_: Exception) {
            // A failed frame must never crash the camera loop.
        } finally {
            image.close()
        }
    }
}

/** The small live camera view (preview + analysis bound together). */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun LiveCameraPreview(
    analyzer: LiveAnalyzer,
    executor: ExecutorService,
    modifier: Modifier,
    onGeometry: (CameraGeometry) -> Unit,
) {
    val context = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val analysisRef = remember { arrayOfNulls<ImageAnalysis>(1) }
    val shape = RoundedCornerShape(16.dp)

    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
    }

    AndroidView(
        modifier = modifier.clip(shape).border(2.dp, Color.White, shape),
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
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
                                        android.util.Size(640, 480),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    ),
                                )
                                .build(),
                        )
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, analyzer) }
                    analysisRef[0] = analysis
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        context as LifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    onGeometry(readGeometry(camera))
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
        update = { view ->
            // The activity is not recreated on rotation, so keep the analysis rotation in sync by hand.
            @Suppress("UNUSED_VARIABLE") val o = orientation
            analysisRef[0]?.targetRotation = view.display?.rotation ?: Surface.ROTATION_0
        },
    )
}

@OptIn(ExperimentalCamera2Interop::class)
private fun readGeometry(camera: Camera): CameraGeometry = try {
    val info = Camera2CameraInfo.from(camera.cameraInfo)
    val f = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    val s = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    CameraGeometry(f?.toDouble(), s?.width?.toDouble())
} catch (_: Throwable) {
    CameraGeometry(null, null)
}
