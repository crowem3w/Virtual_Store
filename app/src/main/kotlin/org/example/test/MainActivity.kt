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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var hasCamera by remember { mutableStateOf(context.hasCamera()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermission = context.hasFineLocation() // Wi-Fi scan results need FINE, not just COARSE
        hasCamera = context.hasCamera()
    }

    LaunchedEffect(Unit) {
        if (!hasPermission || !hasCamera) permissionLauncher.launch(APP_PERMISSIONS)
    }

    var currentShop by remember { mutableStateOf<Shop?>(null) }
    var registryVersion by remember { mutableIntStateOf(0) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            importStatus = "Importing…"
            val result = withContext(Dispatchers.IO) { registry.importScan(uri) }
            importStatus = result.fold(
                onSuccess = { "Imported %.1f MB".format(it / 1_048_576.0) },
                onFailure = { "Import failed: ${it.message}" },
            )
            registryVersion++
        }
    }

    // Scan only while permitted and while the home screen is visible (saves battery + scan quota).
    val scanFlow = remember(hasPermission, currentShop) {
        if (hasPermission && currentShop == null) scanner.scans() else emptyFlow()
    }
    val scan by scanFlow.collectAsState(initial = emptyList<WifiAp>())
    val match = remember(scan, registryVersion) { registry.match(scan) }

    val shop = currentShop
    if (shop != null) {
        StoreScreen(shop = shop, registry = registry, hasCamera = hasCamera, onExit = { currentShop = null })
    } else {
        HomeScreen(
            hasPermission = hasPermission,
            locationEnabled = context.isLocationEnabled(),
            scan = scan,
            match = match,
            registry = registry,
            registryVersion = registryVersion,
            onRegistryChanged = { registryVersion++ },
            importStatus = importStatus,
            onImport = { importLauncher.launch(arrayOf("*/*")) },
            onRemoveImport = { registry.removeScan(); importStatus = null; registryVersion++ },
            onRequestPermission = { permissionLauncher.launch(APP_PERMISSIONS) },
            onEnter = { currentShop = it },
        )
    }
}

private val APP_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.CAMERA,
)

private fun Context.hasFineLocation() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.isLocationEnabled(): Boolean =
    LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))

private fun Context.hasCamera() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
