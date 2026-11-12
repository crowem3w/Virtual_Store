package org.example.test

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.test.data.Shop
import org.example.test.data.ShopMatch
import org.example.test.data.ShopRegistry
import org.example.test.data.WifiAp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    hasPermission: Boolean,
    locationEnabled: Boolean,
    scan: List<WifiAp>,
    match: ShopMatch?,
    registry: ShopRegistry,
    registryVersion: Int,
    onRegistryChanged: () -> Unit,
    onRequestPermission: () -> Unit,
    onEnter: (Shop) -> Unit,
) {
    val registered = remember(registryVersion) { registry.demoShop.bssids }

    Scaffold(topBar = { TopAppBar(title = { Text("Local Store") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasPermission) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Location permission needed", style = MaterialTheme.typography.titleMedium)
                            Text("Android only reveals nearby Wi-Fi networks to apps with location permission. We use it only to recognise which shop you're in.")
                            Button(onClick = onRequestPermission) { Text("Grant permission") }
                        }
                    }
                }
                return@LazyColumn
            }

            if (!locationEnabled) {
                item {
                    Text(
                        "Location is switched off in system settings, so Wi-Fi scans return nothing. Turn it on.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (match != null) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (match != null) {
                            Text("You're at ${match.shop.name}", style = MaterialTheme.typography.titleMedium)
                            Text("Signal ${match.rssi} dBm · ${match.bssid}")
                            Button(onClick = { onEnter(match.shop) }) { Text("Enter virtual store") }
                        } else {
                            Text("No known shop nearby", style = MaterialTheme.typography.titleMedium)
                            Text("Scanning every 30 s. Pick one of the networks below to register it as the demo shop's signal.")
                        }
                        // Fallback until QR scanning exists.
                        OutlinedButton(onClick = { onEnter(registry.demoShop) }) { Text("Open demo store manually") }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Nearby Wi-Fi (${scan.size})",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { registry.clearDemoBssids(); onRegistryChanged() }) { Text("Clear demo signals") }
                }
            }

            items(scan.sortedByDescending { it.rssi }, key = { it.bssid }) { ap ->
                val isRegistered = ap.bssid.lowercase() in registered
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ap.ssid.ifBlank { "(hidden)" }, style = MaterialTheme.typography.bodyLarge)
                        Text("${ap.bssid} · ${ap.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                    if (isRegistered) {
                        Text("Demo ✓", color = MaterialTheme.colorScheme.primary)
                    } else {
                        TextButton(onClick = { registry.addDemoBssid(ap.bssid); onRegistryChanged() }) { Text("Use") }
                    }
                }
            }
        }
    }
}
