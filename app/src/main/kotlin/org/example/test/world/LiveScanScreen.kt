package org.example.test.world

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.test.OrientationTracker
import org.example.test.recon.DepthEstimator
import org.opencv.android.OpenCVLoader
import java.io.File
import java.util.concurrent.Executors

private class StartResult(val slam: LiveSlam?, val error: String?)

/**
 * Black screen, the world drawing itself, a small camera view top-right.
 * Start begins a new world (or continues [resume]); Stop (or Back) saves it and returns to the list.
 * When resuming, the camera first has to find its place in the saved world, or the user places it by hand.
 */
@Composable
fun LiveScanScreen(store: WorldStore, settings: TierSettings, hasCamera: Boolean, resume: WorldInfo? = null, onExit: () -> Unit) {
    val context = LocalContext.current
    val tracker = remember { OrientationTracker(context) }
    DisposableEffect(tracker) {
        tracker.start()
        onDispose { tracker.stop() }
    }
    val analyzer = remember { LiveAnalyzer(tracker) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val renderer = remember { CloudRenderer() }
    val scope = rememberCoroutineScope()

    var geometry by remember { mutableStateOf(CameraGeometry(null, null)) }
    var slam by remember { mutableStateOf<LiveSlam?>(null) }
    var starting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var thirdPerson by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var placing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val glRef = remember { arrayOfNulls<MeshGlView>(1) }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.slam = null
            executor.shutdown()
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) { delay(70); tick++ } // ~14 redraws per second
    }

    fun start() {
        if (starting || slam != null) return
        starting = true
        scope.launch {
            notice = if (settings.effective() == null && resume == null) "Checking device speed (first scan only)…" else null
            val result = withContext(Dispatchers.Default) {
                if (!OpenCVLoader.initLocal()) return@withContext StartResult(null, "OpenCV failed to load.")
                val depth = try { DepthEstimator(context) } catch (_: Throwable) { null }
                val data = resume?.let { ResumeData.read(it.dir) }
                if (resume != null && data == null) {
                    depth?.close()
                    return@withContext StartResult(null, "This world has too few saved keyframes to continue.")
                }
                // A resumed world keeps the voxel size of its saved blocks.
                val tier = data?.tier ?: settings.effective() ?: TierBenchmark.run(context, depth).also { settings.save(it) }.tier
                val session = if (resume != null) store.open(resume) else store.create()
                val live = LiveSlam(geometry, depth, PointCloud(), session, tier)
                if (resume != null && data != null) {
                    notice = "Loading saved world…"
                    PointCloud.loadInto(File(resume.dir, "points.bin"), live.cloud)
                    session.setSavedPoints(live.cloud.count)
                    val ok = live.loadSaved(data, resume.dir) { d, t -> notice = "Preparing saved world: keyframe $d of $t" }
                    if (!ok) {
                        live.abort()
                        return@withContext StartResult(null, "Could not read enough saved keyframes to continue.")
                    }
                    live.publishSavedMesh { d, t -> notice = "Loading saved mesh: $d of $t blocks" }
                }
                StartResult(live, null)
            }
            val created = result.slam
            if (created == null) {
                notice = result.error
            } else {
                notice = when {
                    !created.hasDepthModel -> "No depth model (assets/models/depth.tflite): showing sparse points only."
                    !created.tier.liveMesh -> "${created.tier.label} quality: live points, the mesh is built when you open the world."
                    else -> null
                }
                analyzer.slam = created
                slam = created
            }
            starting = false
        }
    }

    fun stopAndExit() {
        if (saving) return
        val s = slam
        if (s == null) { onExit(); return }
        saving = true
        analyzer.slam = null
        scope.launch {
            withContext(Dispatchers.Default) { s.finish() }
            onExit()
        }
    }
    BackHandler(onBack = ::stopAndExit)

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pointPx = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }

    // The mesh view redraws at about 30 fps with the latest camera pose.
    LaunchedEffect(slam) {
        while (isActive) {
            delay(33)
            val cam = slam?.camPose
            val v = glRef[0]
            if (v != null && cam != null) {
                v.renderer.setView(cam.r, cam.t, cam.rotDeg, if (thirdPerson) 2.0f else 0f, thirdPerson)
                v.requestRender()
            }
        }
    }

    val placeTarget = slam
    if (placing && placeTarget != null && placeTarget.state == TrackState.RELOCALIZING) {
        PlaceStartPicker(
            cloud = placeTarget.cloud,
            path = placeTarget.resumePathXZ,
            onConfirm = { x, z, yaw -> placeTarget.placeManually(x, z, yaw); placing = false },
            onCancel = { placing = false },
        )
        BackHandler { placing = false }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val live = slam
        if (live != null && live.liveMesh) {
            AndroidView(
                factory = { ctx ->
                    MeshGlView(ctx).also {
                        it.renderer.store = live.meshStore
                        (ctx as? androidx.lifecycle.LifecycleOwner)?.let { o -> it.bindLifecycle(o) }
                        glRef[0] = it
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val redraw = tick
                val s = slam
                val cam = s?.camPose
                if (s != null && cam != null) {
                    drawIntoCanvas {
                        renderer.draw(
                            it.nativeCanvas, size.width, size.height, s.cloud,
                            cam.r, cam.t, cam.rotDeg, if (thirdPerson) 2.0f else 0f, pointPx, thirdPerson,
                        )
                    }
                }
            }
        }

        if (hasCamera) {
            LiveCameraPreview(
                analyzer = analyzer,
                executor = executor,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .size(width = if (landscape) 160.dp else 120.dp, height = if (landscape) 120.dp else 160.dp),
                onGeometry = { geometry = it },
            )
        }

        Column(
            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
        ) {
            FilledTonalButton(onClick = ::stopAndExit, enabled = !saving) {
                Text(if (slam != null) "Stop and save" else "Back")
            }
            val s = slam
            if (s != null) {
                @Suppress("UNUSED_VARIABLE") val redraw = tick
                Text(
                    if (s.liveMesh) {
                        "${s.state.name.lowercase()} · ${s.tier.label} · ${s.triangles} triangles · ${s.keyframeCount} keyframes"
                    } else {
                        "${s.state.name.lowercase()} · ${s.tier.label} · ${s.cloud.count} points · ${s.keyframeCount} keyframes"
                    },
                    color = Color.White,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val s = slam
            @Suppress("UNUSED_VARIABLE") val redraw = tick
            val text = when {
                !hasCamera -> "Camera permission needed."
                saving -> "Saving world…"
                s == null -> notice ?: if (resume != null) {
                    "Press Continue, then point the camera at a place you scanned before."
                } else {
                    "Press Start, then walk slowly and move sideways."
                }
                else -> listOfNotNull(s.hint, notice).joinToString("\n")
            }
            Text(text, color = Color.White)
            Row(Modifier.padding(top = 8.dp)) {
                if (s == null) {
                    Button(onClick = ::start, enabled = hasCamera && !starting) {
                        Text(if (starting) "Starting…" else if (resume != null) "Continue" else "Start")
                    }
                } else {
                    FilledTonalButton(onClick = { thirdPerson = !thirdPerson }) {
                        Text(if (thirdPerson) "View: from behind" else "View: camera")
                    }
                    if (s.state == TrackState.RELOCALIZING) {
                        FilledTonalButton(onClick = { placing = true }, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Place by hand")
                        }
                    }
                }
            }
        }
    }
}
