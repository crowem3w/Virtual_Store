package org.example.test.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.test.data.WifiAp

/**
 * Emits the latest Wi-Fi scan results while collected.
 *
 * Android throttles foreground scans to 4 per 2 minutes, so we request one
 * every [intervalMs] (default 30 s) and also emit whenever the system
 * delivers fresh results (including its own scans).
 */
class WifiScanner(context: Context) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)

    fun scans(intervalMs: Long = 30_000L): Flow<List<WifiAp>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(readResults())
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        trySend(readResults()) // cached results, instantly

        val scanLoop = launch {
            while (isActive) {
                @Suppress("DEPRECATION")
                wifiManager?.startScan() // may return false when throttled; fine
                delay(intervalMs)
            }
        }

        awaitClose {
            scanLoop.cancel()
            appContext.unregisterReceiver(receiver)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readResults(): List<WifiAp> = try {
        wifiManager?.scanResults.orEmpty().map { it.toAp() }
    } catch (_: SecurityException) {
        emptyList() // permission revoked while running
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.toAp() = WifiAp(ssid = SSID.orEmpty(), bssid = BSSID.orEmpty(), rssi = level)
}
