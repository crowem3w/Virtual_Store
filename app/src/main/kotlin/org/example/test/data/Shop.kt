package org.example.test.data

import android.content.Context
import android.net.Uri
import java.io.File

/** One access point seen in a Wi-Fi scan. */
data class WifiAp(val ssid: String, val bssid: String, val rssi: Int)

data class Shop(
    val id: String,
    val name: String,
    val description: String,
    /** Path of the .glb model inside app/src/main/assets. */
    val modelAsset: String,
    /** Lower-case BSSIDs (router MAC addresses) installed inside this shop. */
    val bssids: Set<String>,
    /** Absolute path of an imported scan; when set it is used instead of [modelAsset]. */
    val modelFile: String? = null,
    val modelScale: Float = 1f,
    val modelYOffset: Float = 0f,
)

data class ShopMatch(val shop: Shop, val rssi: Int, val bssid: String)

/**
 * Maps Wi-Fi access points to shops. For now the registry is local; later it
 * should come from your backend (shopId -> BSSIDs + model URL).
 */
class ShopRegistry(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("shops", Context.MODE_PRIVATE)

    private val appContext = context.applicationContext
    private val scanFile get() = File(appContext.filesDir, "models/scan.glb")

    fun hasScan() = scanFile.exists()

    val demoShop: Shop
        get() = Shop(
            id = "demo",
            name = if (hasScan()) "My scanned store" else "Demo Shop",
            description = "Store model",
            modelAsset = "models/demo_shop.glb",
            bssids = prefs.getStringSet(KEY_DEMO_BSSIDS, emptySet()).orEmpty().toSet(),
            modelFile = if (hasScan()) scanFile.absolutePath else null,
            modelScale = prefs.getFloat(KEY_SCALE, 1f),
            modelYOffset = prefs.getFloat(KEY_Y, 0f),
        )

    /** Copies a picked .glb into app storage. Returns its size in bytes. Run off the main thread. */
    fun importScan(uri: Uri): Result<Long> {
        val dir = File(appContext.filesDir, "models").also { it.mkdirs() }
        val tmp = File(dir, "scan.tmp")
        return runCatching {
            requireNotNull(appContext.contentResolver.openInputStream(uri)) { "Cannot open file" }
                .use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val magic = tmp.inputStream().use { ByteArray(4).also { b -> it.read(b) } }
            require(String(magic, Charsets.US_ASCII) == "glTF") { "Not a binary glTF (.glb) file" }
            scanFile.delete()
            check(tmp.renameTo(scanFile)) { "Could not save file" }
            prefs.edit().remove(KEY_SCALE).remove(KEY_Y).apply()
            scanFile.length()
        }.onFailure { tmp.delete() }
    }

    fun removeScan() {
        scanFile.delete()
        prefs.edit().remove(KEY_SCALE).remove(KEY_Y).apply()
    }

    fun saveTransform(scale: Float, yOffset: Float) {
        prefs.edit().putFloat(KEY_SCALE, scale).putFloat(KEY_Y, yOffset).apply()
    }

    fun shops(): List<Shop> = listOf(demoShop)

    /** Dev helper: treat this access point as "inside the demo shop". */
    fun addDemoBssid(bssid: String) {
        val updated = demoShop.bssids + bssid.lowercase()
        prefs.edit().putStringSet(KEY_DEMO_BSSIDS, updated).apply()
    }

    fun clearDemoBssids() {
        prefs.edit().remove(KEY_DEMO_BSSIDS).apply()
    }

    /** Best match = the registered access point with the strongest signal. */
    fun match(scan: List<WifiAp>, minRssi: Int = MIN_RSSI): ShopMatch? {
        val shops = shops()
        return scan
            .filter { it.rssi >= minRssi }
            .sortedByDescending { it.rssi }
            .firstNotNullOfOrNull { ap ->
                shops.firstOrNull { ap.bssid.lowercase() in it.bssids }
                    ?.let { ShopMatch(it, ap.rssi, ap.bssid) }
            }
    }

    companion object {
        private const val KEY_DEMO_BSSIDS = "demo_bssids"
        private const val KEY_SCALE = "scan_scale"
        private const val KEY_Y = "scan_y"
        /** Roughly "same room / same floor". Tune on site. */
        const val MIN_RSSI = -80
    }
}
