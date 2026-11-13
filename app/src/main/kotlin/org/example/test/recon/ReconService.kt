package org.example.test.recon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class ReconProgress(
    val scanId: String,
    val stage: String,
    val fraction: Float,
    val error: String? = null,
    val done: Boolean = false,
)

/** Process-wide progress of reconstruction jobs, observed by the UI. */
object ReconState {
    private val _progress = MutableStateFlow<Map<String, ReconProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ReconProgress>> = _progress.asStateFlow()

    internal fun set(p: ReconProgress) = _progress.update { it + (p.scanId to p) }
    internal fun clear(id: String) = _progress.update { it - id }
    fun isRunning(id: String): Boolean = _progress.value[id]?.let { !it.done && it.error == null } == true
}

/**
 * Runs [ReconPipeline] as a foreground service so a long job survives leaving the app.
 * Jobs run one at a time; the service stops itself when the queue is empty.
 */
class ReconService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex() // one reconstruction at a time (memory)
    private val jobs = HashMap<String, Job>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val id = intent?.getStringExtra(EXTRA_SCAN_ID)
        startForeground(
            NOTIF_ID, notification("Preparing…", 0f),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        when (intent?.action) {
            ACTION_CANCEL -> id?.let { jobs[it]?.cancel() }
            else -> if (id != null && jobs[id] == null) enqueue(id)
        }
        if (jobs.isEmpty()) stopSelfSafely()
        return START_NOT_STICKY
    }

    private fun enqueue(id: String) {
        val dir = File(File(filesDir, "scans"), id)
        ReconState.set(ReconProgress(id, "Waiting", 0f))
        val job = scope.launch {
            gate.withLock {
                acquireWakeLock()
                var lastNotify = 0L
                try {
                    ReconPipeline(applicationContext, dir).run(
                        progress = { stage, f ->
                            ReconState.set(ReconProgress(id, stage, f))
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 1000) { lastNotify = now; notify("$id: $stage", f) }
                        },
                        cancel = { this@launch.ensureActive() },
                    )
                    ReconState.set(ReconProgress(id, "Done", 1f, done = true))
                } catch (e: CancellationException) {
                    File(dir, "model.glb.tmp").delete()
                    ReconState.clear(id)
                } catch (e: Throwable) { // includes OutOfMemoryError: report it instead of killing the app
                    File(dir, "model.glb.tmp").delete()
                    ReconState.set(ReconProgress(id, "Failed", 0f, error = e.message ?: e.javaClass.simpleName))
                } finally {
                    releaseWakeLock()
                }
            }
        }
        jobs[id] = job
        job.invokeOnCompletion {
            main.post {
                jobs.remove(id)
                if (jobs.isEmpty()) stopSelfSafely()
            }
        }
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "locStore:recon").apply { acquire(3 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Store scan processing", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String, fraction: Float): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Building 3D store model")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    packageManager.getLaunchIntentForPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun notify(text: String, fraction: Float) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, fraction))
    }

    companion object {
        private const val CHANNEL = "recon"
        private const val NOTIF_ID = 42
        private const val EXTRA_SCAN_ID = "scan_id"
        private const val ACTION_CANCEL = "org.example.test.recon.CANCEL"

        fun start(context: Context, scanId: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, ReconService::class.java).putExtra(EXTRA_SCAN_ID, scanId),
            )
        }

        fun cancel(context: Context, scanId: String) {
            context.startService(
                Intent(context, ReconService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_SCAN_ID, scanId),
            )
        }
    }
}
