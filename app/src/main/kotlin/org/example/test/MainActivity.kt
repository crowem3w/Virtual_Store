package org.example.test

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import org.example.test.data.Shop
import org.example.test.data.ShopRegistry
import org.example.test.data.WifiAp
import org.example.test.ui.theme.TestTheme
import org.example.test.wifi.WifiScanner
import org.example.test.world.LiveScanScreen
import org.example.test.world.TierSettings
import org.example.test.world.WorldInfo
import org.example.test.world.WorldStore
import org.example.test.world.WorldViewScreen

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

private sealed interface Screen {
    data object Worlds : Screen
    data object StoreHome : Screen
    data class Live(val resume: WorldInfo?) : Screen
    data class View(val world: WorldInfo) : Screen
    data class Store(val shop: Shop) : Screen
}

@Composable
fun App() {
    val context = LocalContext.current
    val registry = remember { ShopRegistry(context) }
    val scanner = remember { WifiScanner(context) }
    val worldStore = remember { WorldStore(context) }
    val tierSettings = remember { TierSettings(context) }

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

    var screen by remember { mutableStateOf<Screen>(Screen.Worlds) }
    var version by remember { mutableIntStateOf(0) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The live scan and the world viewer rotate freely; the first-person store view is portrait only.
    LaunchedEffect(screen) {
        (context as? Activity)?.requestedOrientation =
            if (screen is Screen.Store) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }

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
            version++
        }
    }

    // Wi-Fi scanning only while the store home screen is visible (saves battery + scan quota).
    val scanFlow = remember(hasPermission, screen) {
        if (hasPermission && screen is Screen.StoreHome) scanner.scans() else emptyFlow()
    }
    val scan by scanFlow.collectAsState(initial = emptyList<WifiAp>())
    val match = remember(scan, version) { registry.match(scan) }

    when (val s = screen) {
        Screen.Worlds -> WorldListScreen(
            worlds = remember(version, screen) { worldStore.list() },
            hasCamera = hasCamera,
            onNew = { screen = Screen.Live(null) },
            onResume = { screen = Screen.Live(it) },
            onOpen = { screen = Screen.View(it) },
            onRename = { w, name -> worldStore.rename(w, name); version++ },
            onDelete = { worldStore.delete(it); version++ },
            onStore = { screen = Screen.StoreHome },
            settings = tierSettings,
        )
        is Screen.Live -> LiveScanScreen(
            store = worldStore,
            settings = tierSettings,
            hasCamera = hasCamera,
            resume = s.resume,
            onExit = { version++; screen = Screen.Worlds },
        )
        is Screen.View -> WorldViewScreen(info = s.world, onExit = { screen = Screen.Worlds })
        Screen.StoreHome -> HomeScreen(
            hasPermission = hasPermission,
            locationEnabled = context.isLocationEnabled(),
            scan = scan,
            match = match,
            registry = registry,
            registryVersion = version,
            onRegistryChanged = { version++ },
            importStatus = importStatus,
            onImport = { importLauncher.launch(arrayOf("*/*")) },
            onRemoveImport = { registry.removeScan(); importStatus = null; version++ },
            onRequestPermission = { permissionLauncher.launch(APP_PERMISSIONS) },
            onEnter = { screen = Screen.Store(it) },
            onBack = { screen = Screen.Worlds },
        )
        is Screen.Store -> StoreScreen(
            shop = s.shop,
            registry = registry,
            hasCamera = hasCamera,
            onExit = { screen = Screen.StoreHome },
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
