package org.example.test

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.emptyFlow
import org.example.test.data.Shop
import org.example.test.data.ShopRegistry
import org.example.test.data.WifiAp
import org.example.test.ui.theme.TestTheme
import org.example.test.wifi.WifiScanner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TestTheme {
                App()
            }
        }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val registry = remember { ShopRegistry(context) }
    val scanner = remember { WifiScanner(context) }

    var hasPermission by remember { mutableStateOf(context.hasFineLocation()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    var currentShop by remember { mutableStateOf<Shop?>(null) }

    // Scan only while permitted and while the home screen is visible (saves battery + scan quota).
    val scanFlow = remember(hasPermission, currentShop) {
        if (hasPermission && currentShop == null) scanner.scans() else emptyFlow()
    }
    val scan by scanFlow.collectAsState(initial = emptyList<WifiAp>())
    var registryVersion by remember { mutableIntStateOf(0) }
    val match = remember(scan, registryVersion) { registry.match(scan) }

    val shop = currentShop
    if (shop != null) {
        StoreScreen(shop = shop, onExit = { currentShop = null })
    } else {
        HomeScreen(
            hasPermission = hasPermission,
            locationEnabled = context.isLocationEnabled(),
            scan = scan,
            match = match,
            registry = registry,
            registryVersion = registryVersion,
            onRegistryChanged = { registryVersion++ },
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            onEnter = { currentShop = it },
        )
    }
}

private fun Context.hasFineLocation() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.isLocationEnabled(): Boolean =
    LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))
