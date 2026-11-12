package org.example.test.data

import android.content.Context

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
)

data class ShopMatch(val shop: Shop, val rssi: Int, val bssid: String)

/**
 * Maps Wi-Fi access points to shops. For now the registry is local; later it
 * should come from your backend (shopId -> BSSIDs + model URL).
 */
class ShopRegistry(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("shops", Context.MODE_PRIVATE)

    val demoShop: Shop
        get() = Shop(
            id = "demo",
            name = "Demo Shop",
            description = "Placeholder store model",
            modelAsset = "models/demo_shop.glb",
            bssids = prefs.getStringSet(KEY_DEMO_BSSIDS, emptySet()).orEmpty().toSet(),
        )

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
        /** Roughly "same room / same floor". Tune on site. */
        const val MIN_RSSI = -80
    }
}
