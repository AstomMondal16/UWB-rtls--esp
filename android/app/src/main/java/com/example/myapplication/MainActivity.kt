package com.example.myapplication

import android.Manifest
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.RadioButton
import com.example.myapplication.ui.theme.positive
import com.example.myapplication.ui.theme.axisZ
import com.example.myapplication.ui.theme.positiveContainer
import com.example.myapplication.ui.theme.onPositive
import com.example.myapplication.ui.theme.onPositiveContainer

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.espressif.provisioning.DeviceConnectionEvent
import com.espressif.provisioning.ESPConstants
import com.espressif.provisioning.ESPDevice
import com.espressif.provisioning.ESPProvisionManager
import com.espressif.provisioning.listeners.BleScanListener
import com.espressif.provisioning.listeners.ProvisionListener
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.security.KeyStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val Ink: Color @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
private val Accent: Color @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.primary
private val Positive: Color @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.positive
private val Warning: Color @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.tertiary
private val Danger: Color @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.error
private val Page: Color @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.background
private val Grid: Color @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
private const val ESP_PROVISIONING_SERVICE_UUID = "1775244d-6b43-439b-877c-060f2d9bed07"

private data class DashboardPreferences(
    val theme: String = "System",
    val deviceColors: Boolean = false,
    val fontSize: String = "Medium",
    val dashboardContainers: Boolean = true,
    val monitorTabs: Boolean = true,
    val reduceAnimations: Boolean = false,
    val confirmCalibration: Boolean = true,
    val rememberLastDevice: Boolean = true
) {
    val fontScale: Float get() = when (fontSize) { "Small" -> .90f; "Large" -> 1.15f; else -> 1f }
}

private data class ImuReading(
    val sequence: Long,
    val timestampMs: Long,
    val ax: Double, val ay: Double, val az: Double,
    val gx: Double, val gy: Double, val gz: Double
)

private data class CirFrame(val startIndex: Int, val magnitude: List<Double>)

class MainActivity : ComponentActivity() {
    private lateinit var repository: DeviceRepository
    private val dashboard = mutableStateOf(DashboardSnapshot())
    private val uiPreferences = mutableStateOf(DashboardPreferences())
    private val csvFolder = mutableStateOf<Uri?>(null)
    private var pendingCsvStartCount = 0
    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) {
            repository.setStatus("CSV export cancelled")
        } else {
            val output = runCatching { contentResolver.openOutputStream(uri) }
                .onFailure { repository.setStatus("Could not create CSV file: ${it.message}") }
                .getOrNull()
            if (output == null) repository.setStatus("Could not open the selected CSV file")
            else repository.exportCsv(output, pendingCsvStartCount) { }
        }
    }
    private val chooseCsvFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            csvFolder.value = uri
            getSharedPreferences("dashboard_settings", MODE_PRIVATE).edit().putString("csv_folder", uri.toString()).apply()
            repository.setStatus("CSV export folder saved")
        }.onFailure { repository.setStatus("Could not use this folder: ${it.message}") }
    }
    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) repository.manualScan()
        else repository.setStatus("Allow local network access to discover and connect to UWB devices")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = DeviceRepository(this) { dashboard.value = it }
        dashboard.value = repository.snapshot()
        val settings = getSharedPreferences("dashboard_settings", MODE_PRIVATE)
        uiPreferences.value = DashboardPreferences(
            theme = settings.getString("theme", "System") ?: "System",
            deviceColors = settings.getBoolean("device_colors", false),
            fontSize = settings.getString("font_size", "Medium") ?: "Medium",
            dashboardContainers = settings.getBoolean("dashboard_containers", true),
            monitorTabs = settings.getBoolean("monitor_tabs", true),
            reduceAnimations = settings.getBoolean("reduce_animations", false),
            confirmCalibration = settings.getBoolean("confirm_calibration", true),
            rememberLastDevice = settings.getBoolean("remember_device", true))
        csvFolder.value = settings.getString("csv_folder", null)?.let(Uri::parse)
        requestLocalNetworkIfNeeded()
        setContent {
            val snapshot by remember { dashboard }
            val preferences by remember { uiPreferences }
            val folder by remember { csvFolder }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (preferences.theme) { "Dark" -> true; "Light" -> false; else -> systemDark }
            DisposableEffect(darkTheme) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
                onDispose { }
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * preferences.fontScale)) {
                MyApplicationTheme(darkTheme, deviceColors = preferences.deviceColors) {
                    var showSplash by remember { mutableStateOf(!preferences.reduceAnimations) }
                    LaunchedEffect(preferences.reduceAnimations) {
                        if (preferences.reduceAnimations) showSplash = false
                        else if (showSplash) { delay(1550); showSplash = false }
                    }
                    Crossfade(targetState = showSplash,
                        animationSpec = tween(if (preferences.reduceAnimations) 0 else 350), label = "startup") { splash ->
                        if (splash) SenseSplash() else DashboardApp(snapshot, repository,
                            preferences = preferences,
                            storageFolder = folder,
                            onPreferencesChanged = { changed ->
                                uiPreferences.value = changed
                                getSharedPreferences("dashboard_settings", MODE_PRIVATE).edit()
                                    .putString("theme", changed.theme).putString("font_size", changed.fontSize)
                                    .putBoolean("device_colors", changed.deviceColors)
                                    .putBoolean("dashboard_containers", changed.dashboardContainers)
                                    .putBoolean("monitor_tabs", changed.monitorTabs)
                                    .putBoolean("reduce_animations", changed.reduceAnimations)
                                    .putBoolean("confirm_calibration", changed.confirmCalibration)
                                    .putBoolean("remember_device", changed.rememberLastDevice).apply()
                            },
                            onPickStorage = { chooseCsvFolder.launch(folder) },
                            onResetStorage = {
                                folder?.let { runCatching { contentResolver.releasePersistableUriPermission(it,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
                                csvFolder.value = null
                                getSharedPreferences("dashboard_settings", MODE_PRIVATE).edit().remove("csv_folder").apply()
                            },
                            onOpenStorage = { openStorageFolder(folder) },
                            onSaveCsv = ::requestCsvExport,
                            onScan = { if (requestLocalNetworkIfNeeded()) repository.manualScan() })
                    }
                }
                }
            }
        }

    private fun requestCsvExport(label: String, startingAtRecord: Int) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US))
        val cleanLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifBlank { "session" }
        val filename = "UWB_${cleanLabel}_$stamp.csv"
        pendingCsvStartCount = startingAtRecord
        val folder = csvFolder.value
        if (folder == null) {
            createCsv.launch(filename)
            return
        }
        val uri = runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(folder)
            val root = DocumentsContract.buildDocumentUriUsingTree(folder, rootId)
            DocumentsContract.createDocument(contentResolver, root, "text/csv", filename)
                ?: error("The selected folder refused to create a CSV file")
        }.getOrElse {
            repository.setStatus("Could not create CSV in selected folder: ${it.message}")
            return
        }
        val output = runCatching { contentResolver.openOutputStream(uri) }
            .onFailure { repository.setStatus("Could not open CSV file: ${it.message}") }.getOrNull()
        if (output == null) repository.setStatus("Selected folder returned no writable stream")
        else repository.exportCsv(output, startingAtRecord) { }
    }

    private fun openStorageFolder(folder: Uri?) {
        if (folder == null) { repository.setStatus("Choose a CSV folder first"); return }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(folder, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { repository.setStatus("No file manager can open this folder: ${it.message}") }
    }

    private fun requestLocalNetworkIfNeeded(): Boolean {
        // Android 17 (API 37) gates LAN sockets, including UDP discovery and HTTP telemetry.
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            localNetworkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
            return false
        }
        return true
    }

    override fun onDestroy() {
        if (::repository.isInitialized) repository.close()
        super.onDestroy()
    }
}

@Composable
private fun NavigationGlyph(index: Int, selected: Boolean, reduceAnimations: Boolean) {
    val scale by animateFloatAsState(if (selected) 1.08f else 1f,
        animationSpec = tween(if (reduceAnimations) 0 else 180), label = "navigation-icon")
    InstrumentIcon(listOf("monitor", "devices", "settings")[index], if (selected) Accent else Muted,
        Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale })
}

@Composable
private fun AppNavigation(selected: Int, rail: Boolean, reduceAnimations: Boolean, onSelect: (Int) -> Unit) {
    val titles = listOf("Dashboard", "Devices", "Settings")
    val colors = MaterialTheme.colorScheme
    if (rail) {
        NavigationRail(containerColor = colors.surfaceContainerLow) {
            Text("SENSE", color = Accent, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 20.dp))
            titles.forEachIndexed { index, title ->
                NavigationRailItem(selected = selected == index, onClick = { onSelect(index) },
                    icon = { NavigationGlyph(index, selected == index, reduceAnimations) },
                    label = { Text(title, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationRailItemDefaults.colors(indicatorColor = colors.primaryContainer,
                        selectedIconColor = Accent, selectedTextColor = Accent))
            }
        }
    } else {
        NavigationBar(containerColor = colors.surfaceContainerLow, tonalElevation = 0.dp) {
            titles.forEachIndexed { index, title ->
                NavigationBarItem(selected = selected == index, onClick = { onSelect(index) },
                    icon = { NavigationGlyph(index, selected == index, reduceAnimations) },
                    label = { Text(title, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = colors.primaryContainer,
                        selectedIconColor = Accent, selectedTextColor = Accent))
            }
        }
    }
}

@Composable
private fun TabTransition(targetState: Int, reduceAnimations: Boolean, modifier: Modifier = Modifier,
                          label: String, content: @Composable (Int) -> Unit) {
    AnimatedContent(targetState = targetState, modifier = modifier, label = label,
        transitionSpec = {
            val duration = if (reduceAnimations) 0 else 240
            val direction = if (targetState > initialState) 1 else -1
            ((slideInHorizontally(tween(duration)) { direction*it/12 } + fadeIn(tween(duration))) togetherWith
                (slideOutHorizontally(tween(duration)) { -direction*it/16 } + fadeOut(tween(duration*2/3))))
                .using(SizeTransform(clip = true) { _, _ -> tween(duration) })
        }) { visibleState -> content(visibleState) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DashboardApp(
    snapshot: DashboardSnapshot,
    repo: DeviceRepository,
    preferences: DashboardPreferences,
    storageFolder: Uri?,
    onPreferencesChanged: (DashboardPreferences) -> Unit,
    onPickStorage: () -> Unit,
    onResetStorage: () -> Unit,
    onOpenStorage: () -> Unit,
    onSaveCsv: (String, Int) -> Unit,
    onScan: () -> Unit
) {
    var editing by remember { mutableStateOf<DeviceRecord?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var configOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var monitorTab by remember { mutableStateOf(0) }
    var settingsTab by remember { mutableStateOf(0) }
    val monitorListState = rememberLazyListState()
    val mapViewport = remember { MapViewport() }
    val navigationFocus = LocalFocusManager.current
    BackHandler(enabled = configOpen || settingsOpen) {
        configOpen = false
        settingsOpen = false
    }
    LaunchedEffect(monitorTab, preferences.monitorTabs) { monitorListState.scrollToItem(0) }
    var calibrationConfirm by remember { mutableStateOf(false) }
    var wifiProvisionOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val blePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) wifiProvisionOpen = true
        else repo.setStatus("Bluetooth permission is required to provision Wi-Fi")
    }
    fun requestWifiProvisioning() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) wifiProvisionOpen = true
        else blePermissionLauncher.launch(missing.toTypedArray())
    }
    var clearConfirm by remember { mutableStateOf(false) }
    var cirData by remember { mutableStateOf<JSONObject?>(null) }
    var cirError by remember { mutableStateOf("") }
    var cirLoading by remember { mutableStateOf(false) }
    var cirDeviceKey by remember { mutableStateOf("") }
    var selectedKey by remember {
        mutableStateOf(if (preferences.rememberLastDevice)
            context.getSharedPreferences("dashboard_settings", android.content.Context.MODE_PRIVATE)
                .getString("selected_device", "").orEmpty() else "")
    }
    var fitMapRequest by remember { mutableStateOf(0) }
    var mapFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(configOpen) { if (configOpen) onScan() }
    val names = remember(snapshot.devices, snapshot.positions) {
        val result = mutableMapOf<String, String>()
        var anchors = 0; var tags = 0
        snapshot.devices.forEach { d ->
            if (d.role == "anchor") anchors++ else tags++
            result[d.key] = if (d.role == "anchor") "A$anchors" else "T$tags"
        }
        result
    }
    val chosen = snapshot.devices.firstOrNull { it.key == selectedKey }
        ?: snapshot.devices.firstOrNull { it.role == "tag" }
        ?: snapshot.devices.firstOrNull()
    val cirHistory = remember(chosen?.key) { mutableStateListOf<CirFrame>() }
    var lastCirHistoryKey by remember(chosen?.key) { mutableStateOf("") }
    val chosenIp = chosen?.let { device ->
        device.ip.ifBlank {
            snapshot.states[device.key]?.sample?.optString("ip").orEmpty().ifBlank {
                snapshot.discovered.firstOrNull { it.mac == device.mac }?.ip.orEmpty()
            }
        }
    }.orEmpty()
    val sample = chosen?.let { snapshot.states[it.key]?.sample }
    val tracking = snapshot.states.values.any { it.online && it.sample.optBoolean("streaming", false) }
    var clockMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.collecting) {
        if (snapshot.collecting) while (isActive) { clockMs = System.currentTimeMillis(); delay(1000) }
    }
    LaunchedEffect(chosen?.key, preferences.rememberLastDevice) {
        val prefs = context.getSharedPreferences("dashboard_settings", android.content.Context.MODE_PRIVATE)
        if (preferences.rememberLastDevice) chosen?.key?.let { prefs.edit().putString("selected_device", it).apply() }
        else prefs.edit().remove("selected_device").apply()
    }
    val imuHistory = remember(chosen?.key) { mutableStateListOf<ImuReading>() }
    val orientationTracker = remember(chosen?.key) { DeviceOrientation() }
    var orientationEstimate by remember(chosen?.key) { mutableStateOf<OrientationEstimate?>(null) }
    var showRawImu by remember(chosen?.key) { mutableStateOf(false) }
    val orientationZero = remember(chosen?.key, orientationEstimate?.generation) {
        mutableStateOf<OrientationQuaternion?>(null)
    }
    val imuStamp = sample?.optLong("imu_timestamp_ms", sample.optLong("timestamp_ms", 0)) ?: 0L
    val imuSequence = sample?.optLong("imu_sequence", imuStamp) ?: imuStamp
    fun requestCir(deviceKey: String, ip: String) {
        if (cirLoading) return
        cirLoading = true
        repo.getCir(ip, chosen?.let { it.mac.ifBlank { it.ip } }.orEmpty()) { data, error ->
            if (cirDeviceKey == deviceKey) {
                if (data != null) cirData = data
                cirError = error.orEmpty()
                cirLoading = false
            }
        }
    }
    LaunchedEffect(chosen?.key, cirDeviceKey, cirData) {
        val current = cirData ?: return@LaunchedEffect
        if (cirDeviceKey != chosen?.key || !current.optBoolean("valid")) return@LaunchedEffect
        val key = listOf("sequence", "timestamp_ms", "rx_timestamp", "peer_address",
            "start_index", "first_path_index", "sample_count")
            .joinToString(":") { current.opt(it)?.toString().orEmpty() }
        if (key != lastCirHistoryKey) {
            val iq = current.optJSONArray("iq") ?: return@LaunchedEffect
            val magnitudes = (0 until iq.length()).mapNotNull { index ->
                val pair = iq.optJSONArray(index) ?: return@mapNotNull null
                if (pair.length() < 2) null else kotlin.math.hypot(pair.optDouble(0), pair.optDouble(1))
            }
            if (magnitudes.isNotEmpty()) {
                if (cirHistory.size == 48) cirHistory.removeAt(0)
                cirHistory.add(CirFrame(current.optInt("start_index"), magnitudes))
                lastCirHistoryKey = key
            }
        }
    }
    LaunchedEffect(chosen?.key, chosenIp) {
        cirData = null
        cirDeviceKey = chosen?.key.orEmpty()
        cirLoading = false
        cirError = when {
            chosen == null -> ""
            chosenIp.isBlank() -> "No IP address is available for the selected device."
            else -> "Loading CIR..."
        }
        if (chosen != null && chosenIp.isNotBlank()) {
            while (isActive) {
                requestCir(chosen.key, chosenIp)
                delay(500)
            }
        }
    }
    LaunchedEffect(chosen?.key, imuSequence, imuStamp) {
        if (sample?.optBoolean("imu_valid") == true) {
            val accel = sample.optJSONArray("accel")
            val gyro = sample.optJSONArray("gyro")
            if (accel != null && accel.length() >= 3 && gyro != null && gyro.length() >= 3) {
                val a = DoubleArray(3) { accel.optDouble(it) }
                val g = DoubleArray(3) { gyro.optDouble(it) }
                orientationEstimate = orientationTracker.update(imuStamp, a, g)
                if (a.all(Double::isFinite) && g.all(Double::isFinite) &&
                    imuHistory.lastOrNull()?.sequence != imuSequence) {
                    imuHistory.add(ImuReading(imuSequence, imuStamp, a[0], a[1], a[2], g[0], g[1], g[2]))
                    if (imuHistory.size > 120) imuHistory.removeAt(0)
                }
            } else orientationEstimate = null
        }
    }
    val minimumActionWidth = (96 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val activeScreen = if (configOpen) 1 else if (settingsOpen) 2 else 0
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp
    val selectDestination: (Int) -> Unit = { index ->
        navigationFocus.clearFocus()
        configOpen = index == 1
        settingsOpen = index == 2
    }
    Scaffold(containerColor = Page, bottomBar = {
        if (!useRail) AppNavigation(activeScreen, false, preferences.reduceAnimations, selectDestination)
    }) { padding ->
        Row(Modifier.fillMaxSize()) {
            if (useRail) AppNavigation(activeScreen, true, preferences.reduceAnimations, selectDestination)
        TabTransition(targetState = activeScreen, modifier = Modifier.weight(1f).fillMaxHeight().padding(padding).consumeWindowInsets(padding),
            reduceAnimations = preferences.reduceAnimations, label = "main-navigation") { screen ->
            when (screen) {
                1 -> DevicesPage(snapshot, names, repo,
                    onScan = onScan,
                    onAdd = { editing = null; editorOpen = true },
                    onEdit = { editing = it; editorOpen = true },
                    onClear = { clearConfirm = true }, onWifiSetup = ::requestWifiProvisioning)
                2 -> SettingsPage(
                    page = settingsTab, onPageSelected = { navigationFocus.clearFocus(); settingsTab = it },
                    snapshot = snapshot, names = names, chosen = chosen, chosenIp = chosenIp, sample = sample, cirData = cirData,
                    preferences = preferences, storageFolder = storageFolder, onPreferencesChanged = onPreferencesChanged,
                    onPickStorage = onPickStorage, onResetStorage = onResetStorage, onOpenStorage = onOpenStorage,
                    onRefresh = repo::refreshNow,
                    onDeviceSelected = { selectedKey = it },
                    onApplyDevice = { device, body, completed ->
                        repo.postControl(chosenIp, body, device.mac.ifBlank { device.ip }) { response ->
                            if (runCatching { JSONObject(response).optBoolean("ok") }.getOrDefault(false)) repo.upsert(device)
                            completed(response)
                        }
                    })
                else -> {
                    LazyColumn(state = monitorListState, modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 840.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Dashboard", color = Ink, fontWeight = FontWeight.Medium,
                                            fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp,
                                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                        Text("${snapshot.states.values.count { it.online }} online \u00b7 ${snapshot.positions.size} located tags",
                                            color = Muted, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    }
                                    val batteryPercent = sample?.optDouble("battery_percent", Double.NaN) ?: Double.NaN
                                    val batteryAvailable = chosen != null && sample?.optBoolean("battery_valid") == true &&
                                        snapshot.states[chosen.key]?.online == true && batteryPercent.isFinite()
                                    val batteryTone by animateColorAsState(
                                        if (batteryAvailable) batteryLevelColor(batteryPercent, MaterialTheme.colorScheme.surface.luminance() < .5f) else Muted,
                                        animationSpec = tween(if (preferences.reduceAnimations) 0 else 300), label = "battery-color")
                                    BatteryIcon(
                                        if (batteryAvailable) "${batteryPercent.toInt()}%" else "--",
                                        if (batteryAvailable) batteryPercent else 0.0,
                                        batteryTone,
                                        Modifier.size(width = 76.dp, height = 28.dp)
                                            .semantics { contentDescription = if (batteryAvailable) "Battery ${batteryPercent.toInt()} percent" else "Battery unavailable" },
                                        reduceAnimations = preferences.reduceAnimations
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                DevicePickerContent(snapshot.devices, chosen, names, snapshot.states,
                                    onSelect = { selectedKey = it })
                                Spacer(Modifier.height(8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    DashboardActionButton(
                                        label = if (tracking) "Pause" else "Start", icon = if (tracking) "pause" else "start",
                                        active = tracking, loading = snapshot.streamingPending,
                                        enabled = !snapshot.streamingPending && snapshot.devices.isNotEmpty(),
                                        modifier = Modifier.widthIn(min = minimumActionWidth).weight(1f)
                                    ) { repo.setStreaming(!tracking) }
                                    DashboardActionButton(
                                        label = if (snapshot.calibrationProgress.isBlank()) "Calibrate" else "Cancel",
                                        icon = if (snapshot.calibrationProgress.isBlank()) "calibrate" else "close",
                                        active = snapshot.calibrationProgress.isNotBlank(), accent = Warning,
                                        enabled = snapshot.calibrationProgress.isNotBlank() || chosen != null,
                                        modifier = Modifier.widthIn(min = minimumActionWidth).weight(1f)
                                    ) {
                                        if (snapshot.calibrationProgress.isNotBlank()) repo.startOrCancelCalibration()
                                        else if (preferences.confirmCalibration && tracking) calibrationConfirm = true
                                        else repo.startOrCancelCalibration()?.let(repo::setStatus)
                                    }
                                    DashboardActionButton(
                                        label = if (snapshot.collecting) "Stop CSV" else "Record CSV", icon = if (snapshot.collecting) "stop" else "record",
                                        active = snapshot.collecting, recording = snapshot.collecting, accent = Positive,
                                        enabled = snapshot.collecting ||
                                            (snapshot.devices.isNotEmpty() && !snapshot.status.startsWith("Exporting")),
                                        modifier = Modifier.widthIn(min = minimumActionWidth).weight(1f)
                                    ) {
                                        if (snapshot.collecting) {
                                            repo.setCollecting(false)
                                            if (snapshot.collected > snapshot.recordingStartCount)
                                                onSaveCsv(chosen?.let { names[it.key] }.orEmpty().ifBlank { "session" }, snapshot.recordingStartCount)
                                            else repo.setStatus("Recording stopped: no new device records were received")
                                        } else repo.setCollecting(true)
                                    }
                                }
                                if (snapshot.collecting) {
                                    val elapsed = ((clockMs - (snapshot.recordingStartedAtMs ?: clockMs)).coerceAtLeast(0) / 1000)
                                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(Danger))
                                        Text("Recording  ${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}  ·  ${snapshot.collected - snapshot.recordingStartCount} records",
                                            color = Danger, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                            modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                            FeedbackMessage(snapshot.status, busy = snapshot.streamingPending || snapshot.status.startsWith("Exporting"))
                            if (snapshot.calibrationProgress.isNotBlank())
                                FeedbackMessage("Calibration \u00b7 ${snapshot.calibrationProgress}", busy = true)
                        }
                        if (preferences.monitorTabs) stickyHeader(key = "monitor-tabs") {
                            SecondaryTabRow(selectedTabIndex = monitorTab, containerColor = Page) {
                                listOf("Map", "Motion", "CIR", "Battery").forEachIndexed { index, title ->
                                    Tab(selected = monitorTab == index, onClick = { monitorTab = index },
                                        selectedContentColor = Accent, unselectedContentColor = Muted,
                                        text = { Text(title, fontSize = 14.sp) })
                                }
                            }
                        }
                        item {
                            DashboardGroup(preferences.dashboardContainers) {
                                TabTransition(targetState = if (preferences.monitorTabs) monitorTab else -1,
                                    modifier = Modifier.fillMaxWidth(), reduceAnimations = preferences.reduceAnimations,
                                    label = "dashboard-view") { activeTab ->
                                    Column {
                                        val sections = if (activeTab >= 0) listOf(activeTab) else (0..3).toList()
                                        sections.forEach { section ->
                                            if (!preferences.monitorTabs && section > 0) GroupDivider()
                                            when (section) {
                                                0 -> GroupedSection("Localization map", headerAction = {
                                                    Row {
                                                        IconButton(shape = MaterialTheme.shapes.medium, onClick = { fitMapRequest++ }, modifier = Modifier.semantics {
                                                            contentDescription = "Fit all anchors and tags"
                                                        }) { InstrumentIcon("fit", Muted) }
                                                        IconButton(shape = MaterialTheme.shapes.medium, onClick = { mapFullscreen = true }, modifier = Modifier.semantics {
                                                            contentDescription = "Open fullscreen localization map"
                                                        }) { InstrumentIcon("fullscreen", Muted) }
                                                    }
                                                }) {
                                                    if (snapshot.devices.none { it.role == "anchor" }) {
                                                        EmptyHint("Add at least three anchors in Devices and enter their measured coordinates to locate a tag.")
                                                    } else {
                                                        LocalizationMap(snapshot.devices, snapshot.positions, names, snapshot.states, fitMapRequest,
                                                            Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(20.dp)), viewport = mapViewport)
                                                        MapLegend()
                                                    }
                                                    snapshot.positions.entries.firstOrNull()?.let { (tag, p) ->
                                                        Text("${names[tag] ?: "Tag"}   X ${String.format(Locale.US, "%+.2f", p.x)} m   Y ${String.format(Locale.US, "%+.2f", p.y)} m   RMS ${p.error.fmt()} m",
                                                            color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                                                    }
                                                }
                                                1 -> GroupedSection("IMU \u00b7 ${chosen?.let { names[it.key] } ?: "No device"}") {
                                                    when {
                                                        chosen == null -> EmptyHint("Add or select a device to view IMU telemetry.")
                                                        sample == null -> EmptyHint("Waiting for telemetry from ${names[chosen.key]}. Check that it is online.")
                                                        snapshot.states[chosen.key]?.online != true -> EmptyHint("${names[chosen.key]} is offline. Reconnect it to Wi-Fi to receive IMU telemetry.")
                                                        !sample.optBoolean("imu_valid") -> EmptyHint(
                                                            if (!sample.optBoolean("imu_enabled", true))
                                                                "IMU is disabled in this device's firmware. Enable the MPU6050 and flash the device."
                                                            else "Waiting for a valid MPU6050 sample. Check sensor wiring and the device boot log.")
                                                        else -> {
                                                            OrientationView(orientationEstimate, orientationZero)
                                                            TextButton(onClick = { showRawImu = !showRawImu }) {
                                                                Text(if (showRawImu) "Hide raw IMU readings" else "Show raw IMU readings")
                                                            }
                                                            if (showRawImu) ImuReadings(sample)
                                                            ImuChart("Accelerometer \u00b7 g", imuHistory, acceleration = true,
                                                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                                                            ImuChart("Gyroscope \u00b7 deg/s", imuHistory, acceleration = false,
                                                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                                        }
                                                    }
                                                }
                                                2 -> GroupedSection("Channel impulse response") {
                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                        Text(chosen?.let { "${names[it.key] ?: "Device"} | ${it.mac.ifBlank { it.ip }}" } ?: "Select a device",
                                                            color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                                        TextButton(enabled = chosen != null && chosenIp.isNotBlank(), onClick = {
                                                            if (chosen == null || chosenIp.isBlank()) cirError = "This device has no IP address"
                                                            else if (!cirLoading) {
                                                                cirError = "Loading CIR..."
                                                                cirDeviceKey = chosen.key
                                                                requestCir(chosen.key, chosenIp)
                                                            }
                                                        }) { Text("Refresh") }
                                                    }
                                                    val activeCir = cirData.takeIf { cirDeviceKey == chosen?.key }
                                                    if (activeCir?.optBoolean("valid") == true) {
                                                        if (cirError.isNotBlank() && cirError != "Loading CIR...") FeedbackMessage(cirError)
                                                        if (chosen != null && snapshot.states[chosen.key]?.online != true)
                                                            Text("Device offline - showing the last cached CIR capture", color = Warning, fontSize = 14.sp)
                                                        CirPlot(activeCir, cirHistory, Modifier.fillMaxWidth().height(260.dp))
                                                        Text("${activeCir.optInt("sample_count")} samples | seq ${activeCir.optLong("sequence")} | age ${activeCir.optLong("age_ms")} ms",
                                                            color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                                                    } else EmptyHint(cirError.ifBlank {
                                                        when {
                                                            activeCir?.optBoolean("enabled") == false -> "CIR is disabled in this device's firmware."
                                                            activeCir != null -> "CIR is enabled; waiting for a UWB packet from another device."
                                                            else -> "Waiting for automatic CIR updates from the selected device."
                                                        }
                                                    })
                                                }
                                                3 -> GroupedSection("Battery") {
                                                    when {
                                                        chosen == null -> BatteryInfoState("Select a device to view battery telemetry.")
                                                        sample == null -> BatteryInfoState("Waiting for ${names[chosen.key]} battery telemetry.")
                                                        snapshot.states[chosen.key]?.online != true -> BatteryInfoState("${names[chosen.key]} is offline.")
                                                        !sample.optBoolean("battery_valid") -> BatteryInfoState(
                                                            "Battery reading unavailable. Check the firmware battery monitor and ADC wiring.", warning = true)
                                                        !sample.optDouble("battery_percent", Double.NaN).isFinite() ||
                                                            !sample.optDouble("battery_voltage", Double.NaN).isFinite() -> BatteryInfoState(
                                                            "The device sent an invalid battery value.", warning = true)
                                                        else -> BatteryTelemetryCard(sample.optDouble("battery_percent").coerceIn(0.0, 100.0),
                                                            sample.optDouble("battery_voltage"), preferences.reduceAnimations)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${snapshot.devices.size} configured · ${snapshot.discovered.size} discovered · ${snapshot.collected} stored records",
                                    color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton(enabled = snapshot.collected > 0 && !snapshot.status.startsWith("Exporting"), onClick = {
                                    onSaveCsv(chosen?.let { names[it.key] }.orEmpty().ifBlank { "session" }, 0)
                                }) { Text("Export all", fontSize = 14.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
    }
    }
    if (mapFullscreen) Dialog(onDismissRequest = { mapFullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Page) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Localization map", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(shape = MaterialTheme.shapes.medium, onClick = { fitMapRequest++ }, modifier = Modifier.semantics {
                        contentDescription = "Fit all anchors and tags"
                    }) { InstrumentIcon("fit", Muted) }
                    TextButton(onClick = { mapFullscreen = false }) { Text("Done") }
                }
                if (snapshot.devices.none { it.role == "anchor" }) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        EmptyHint("Add at least three anchors with measured coordinates in Devices to locate a tag.")
                    }
                } else LocalizationMap(snapshot.devices, snapshot.positions, names, snapshot.states, fitMapRequest,
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)))
                MapLegend()
                Text("Pinch to zoom \u00b7 Drag to pan \u00b7 Double tap to fit", color = Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
    if (wifiProvisionOpen) WifiProvisioningDialog(onDismiss = { wifiProvisionOpen = false })
    if (calibrationConfirm) AlertDialog(onDismissRequest = { calibrationConfirm = false },
        title = { Text("Calibrate while tracking?") },
        text = { Text("Antenna delay calibration uses live ranges and may interrupt normal tracking. Keep the tag still during the calibration.") },
        confirmButton = { TextButton(onClick = {
            calibrationConfirm = false
            repo.startOrCancelCalibration()?.let(repo::setStatus)
        }) { Text("Start calibration") } },
        dismissButton = { TextButton(onClick = { calibrationConfirm = false }) { Text("Cancel") } })
    if (editorOpen) DeviceEditorDialog(editing,
        initialSecurity = LastWifiCredentialStore.loadDeviceSecurity(context,
            editing?.let { it.mac.ifBlank { it.ip } }.orEmpty()),
        onDismiss = { editorOpen = false; configOpen = true }, onSave = { record, fingerprint ->
        val error = repo.upsert(record, editing?.key)
        if (error == null) {
            if (!LastWifiCredentialStore.saveDeviceSecurity(context, record.mac.ifBlank { record.ip }, fingerprint)) {
                repo.setStatus("Could not save device connection settings")
            } else {
                if (record.ip.isNotBlank()) repo.postControl(record.ip, JSONObject().put("role", record.role), record.mac)
                editorOpen = false; configOpen = true
            }
        } else repo.setStatus(error)
    })
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("Clear configured devices?") },
        text = { Text("This removes the saved device map and localization configuration.") },
        confirmButton = { TextButton(onClick = { repo.clear(); clearConfirm = false }) { Text("Clear", color = Danger) } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Cancel") } })
}

@Composable
private fun DashboardActionButton(
    label: String, icon: String, enabled: Boolean, modifier: Modifier = Modifier,
    active: Boolean = false, loading: Boolean = false, recording: Boolean = false, accent: Color? = null,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val filled = accent == null || active || recording
    val tone = if (recording) Danger else accent ?: Accent
    val foreground = when {
        !enabled -> Muted
        !filled && accent == Positive -> colors.onPositiveContainer
        !filled && accent == Warning -> colors.onTertiaryContainer
        tone == Danger -> colors.onError
        tone == Positive -> colors.onPositive
        tone == Warning -> colors.onTertiary
        else -> colors.onPrimary
    }
    val background = when {
        filled -> tone
        accent == Positive -> colors.positiveContainer
        else -> colors.tertiaryContainer
    }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = colors.surfaceVariant, disabledContentColor = Muted),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = foreground, strokeWidth = 2.dp)
        else InstrumentIcon(icon, foreground, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun FeedbackMessage(message: String, busy: Boolean = false) {
    if (message.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    val failure = message.contains("failed", true) || message.contains("could not", true) ||
        message.contains("permission", true) || message.contains("mismatch", true)
    val summary = when {
        message.startsWith("Scan failed:") -> "Network scan failed"
        message.startsWith("Scan complete \u00b7 0 found") -> "No devices found on this network"
        message.startsWith("CIR request failed:") -> "Could not read CIR from this device"
        message.startsWith("Control failed:") -> "Device control failed"
        message.startsWith("CSV export failed:") -> "CSV export failed"
        else -> message
    }
    val hasDetails = summary != message || message.length > 80
    val colors = MaterialTheme.colorScheme
    Surface(color = if (failure) colors.errorContainer else if (busy) colors.primaryContainer else colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
                    Spacer(Modifier.width(8.dp))
                }
                if (!busy) {
                    InstrumentIcon(if (failure) "error" else "info", if (failure) colors.onErrorContainer else Muted, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(summary, color = if (failure) colors.onErrorContainer else if (busy) colors.onPrimaryContainer else Muted, fontSize = 14.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                if (hasDetails) TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Less" else "Details")
                }
            }
            if (expanded && summary != message) Text(message, color = if (failure) colors.onErrorContainer else Muted,
                fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ImuReadings(sample: JSONObject) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Axis", color = Muted, fontSize = 14.sp, modifier = Modifier.width(40.dp))
            Text("Accel \u00b7 g", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text("Gyro \u00b7 deg/s", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        listOf("X", "Y", "Z").forEachIndexed { index, axis ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(axis, color = Ink, fontSize = 14.sp, modifier = Modifier.width(40.dp))
                listOf("accel", "gyro").forEach { field ->
                    val value = sample.optJSONArray(field)?.optDouble(index, Double.NaN) ?: Double.NaN
                    Text(if (value.isFinite()) String.format(Locale.US, "%.2f", value) else "--",
                        color = Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    page: Int,
    onPageSelected: (Int) -> Unit,
    snapshot: DashboardSnapshot,
    names: Map<String, String>,
    chosen: DeviceRecord?,
    chosenIp: String,
    sample: JSONObject?,
    cirData: JSONObject?,
    preferences: DashboardPreferences,
    storageFolder: Uri?,
    onPreferencesChanged: (DashboardPreferences) -> Unit,
    onPickStorage: () -> Unit,
    onResetStorage: () -> Unit,
    onOpenStorage: () -> Unit,
    onRefresh: () -> Unit,
    onDeviceSelected: (String) -> Unit,
    onApplyDevice: (DeviceRecord, JSONObject, (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val folderPath = remember(storageFolder) { storageFolderPath(context, storageFolder) }
    Surface(Modifier.fillMaxSize(),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Settings", color = Ink, style = MaterialTheme.typography.headlineMedium)
                    Text("Make the app work your way", color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            SecondaryScrollableTabRow(selectedTabIndex = page, edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Appearance", "Files", "About", "Device").forEachIndexed { index, title ->
                    Tab(selected = page == index, onClick = { onPageSelected(index) },
                        selectedContentColor = Accent, unselectedContentColor = Muted, text = {
                        Text(title, maxLines = 1, softWrap = false, fontSize = 14.sp)
                    })
                }
            }
            TabTransition(targetState = page, modifier = Modifier.weight(1f),
                reduceAnimations = preferences.reduceAnimations, label = "settings-page") { visiblePage ->
                Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (visiblePage) {
                        0 -> PreferencesPage(preferences, onPreferencesChanged)
                        1 -> StoragePage(storageFolder, folderPath,
                            onPickStorage, onResetStorage, onOpenStorage)
                        2 -> AboutPage(chosen)
                        else -> DevicePropertiesPage(snapshot, names, chosen, chosenIp, sample, cirData, onRefresh,
                            onApplyDevice, onDeviceSelected)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferencesPage(value: DashboardPreferences, onChange: (DashboardPreferences) -> Unit) {
    SettingsHeading("Appearance", "Changes are saved automatically.")
    SettingsGroup {
        Text("Theme", color = Ink, style = MaterialTheme.typography.titleSmall)
        PreferenceChoices(listOf("Light", "Dark", "System"), value.theme) { onChange(value.copy(theme = it)) }
        SettingsSwitch("Use device colours", if (Build.VERSION.SDK_INT >= 31) "Match the app to your wallpaper palette" else "Requires Android 12 or later",
            value.deviceColors, enabled = Build.VERSION.SDK_INT >= 31) { onChange(value.copy(deviceColors = it)) }
    }
    SettingsGroup {
        Text("Text size", color = Ink, style = MaterialTheme.typography.titleSmall)
        PreferenceChoices(listOf("Small", "Medium", "Large"), value.fontSize) { onChange(value.copy(fontSize = it)) }
    }
    SettingsGroup {
        Text("Dashboard view", color = Ink, style = MaterialTheme.typography.titleSmall)
        Text("Use tabs or see all readings on one scrolling page.", color = Muted, fontSize = 14.sp)
        PreferenceChoices(listOf("Tabs", "Scrolling"), if (value.monitorTabs) "Tabs" else "Scrolling",
            preview = { MonitorViewPreview(tabs = it == "Tabs") }) {
            onChange(value.copy(monitorTabs = it == "Tabs"))
        }
    }
    SettingsGroup {
        Text("Dashboard layout", color = Ink, style = MaterialTheme.typography.titleSmall)
        Text("Put readings in cards or directly on the page.", color = Muted, fontSize = 14.sp)
        PreferenceChoices(listOf("Containers", "No containers"), if (value.dashboardContainers) "Containers" else "No containers",
            preview = { ContainerPreview(withContainers = it == "Containers") }) {
            onChange(value.copy(dashboardContainers = it == "Containers"))
        }
    }
    SettingsGroup {
        Text("Behaviour", color = Ink, style = MaterialTheme.typography.titleSmall)
        SettingsSwitch("Reduce animations", "Skip the animated startup screen and transitions.", value.reduceAnimations) {
            onChange(value.copy(reduceAnimations = it))
        }
        SettingsSwitch("Confirm calibration", "Ask before calibrating during live tracking.", value.confirmCalibration) {
            onChange(value.copy(confirmCalibration = it))
        }
        SettingsSwitch("Remember device", "Select the same device when you return.", value.rememberLastDevice) {
            onChange(value.copy(rememberLastDevice = it))
        }
    }
    TextButton(onClick = { onChange(DashboardPreferences()) }) { InstrumentIcon("reset", LocalContentColor.current, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Reset preferences") }
}

@Composable
private fun ContainerPreview(withContainers: Boolean) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(56.dp).clip(MaterialTheme.shapes.small).background(Page)) {
        val inset = 6.dp.toPx()
        if (withContainers) drawRoundRect(colors.surface,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width-inset*2, size.height-inset*2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        listOf(colors.primary, colors.secondary, colors.tertiary).forEachIndexed { index, tone ->
            val y = (15 + index*13).dp.toPx()
            drawCircle(tone, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(14.dp.toPx(), y))
            drawLine(tone, androidx.compose.ui.geometry.Offset(22.dp.toPx(), y),
                androidx.compose.ui.geometry.Offset(size.width*.65f, y), 2.dp.toPx())
            drawLine(colors.outlineVariant, androidx.compose.ui.geometry.Offset(size.width*.75f, y),
                androidx.compose.ui.geometry.Offset(size.width-14.dp.toPx(), y), 2.dp.toPx())
        }
    }
}

@Composable
private fun MonitorViewPreview(tabs: Boolean) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(56.dp).clip(MaterialTheme.shapes.small).background(Page)) {
        val inset = 4.dp.toPx()
        if (tabs) {
            val width = (size.width-inset*5)/4
            repeat(4) { index ->
                drawRoundRect(if (index == 0) colors.primaryContainer else colors.surfaceVariant,
                    topLeft = androidx.compose.ui.geometry.Offset(inset+index*(width+inset), inset),
                    size = androidx.compose.ui.geometry.Size(width, 9.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            }
            drawLine(colors.primary, androidx.compose.ui.geometry.Offset(inset, 13.dp.toPx()),
                androidx.compose.ui.geometry.Offset(inset+width, 13.dp.toPx()), 2.dp.toPx())
            drawRoundRect(colors.surface, topLeft = androidx.compose.ui.geometry.Offset(inset, 19.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width-inset*2, 33.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(inset))
            val chart = Path().apply {
                moveTo(inset*3, 44.dp.toPx())
                lineTo(size.width*.35f, 29.dp.toPx())
                lineTo(size.width*.6f, 39.dp.toPx())
                lineTo(size.width-inset*3, 27.dp.toPx())
            }
            drawPath(chart, colors.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            listOf(colors.primary, colors.secondary, colors.tertiary).forEachIndexed { index, tone ->
                val y = inset + index*17.dp.toPx()
                drawRoundRect(colors.surface, topLeft = androidx.compose.ui.geometry.Offset(inset, y),
                    size = androidx.compose.ui.geometry.Size(size.width-inset*2, 13.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(inset))
                drawLine(tone, androidx.compose.ui.geometry.Offset(inset*3, y+inset),
                    androidx.compose.ui.geometry.Offset(size.width*.55f, y+inset), 2.dp.toPx())
                drawLine(colors.outlineVariant, androidx.compose.ui.geometry.Offset(inset*3, y+inset*2),
                    androidx.compose.ui.geometry.Offset(size.width-inset*3, y+inset*2), 1.dp.toPx())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferenceChoices(options: List<String>, selected: String,
                              preview: (@Composable (String) -> Unit)? = null, onSelect: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val stacked = maxWidth < (options.size * 88 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    if (preview == null && stacked) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = selected == option, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(option.replaceFirstChar(Char::uppercaseChar), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                    RadioButton(selected = selected == option, onClick = null)
                }
            }
        }
    } else if (preview == null) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(selected = selected == option, onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(option.replaceFirstChar(Char::uppercaseChar), style = MaterialTheme.typography.labelLarge) })
            }
        }
    } else {
        val minimumWidth = (136 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val active = selected == option
                Surface(modifier = Modifier.widthIn(min = minimumWidth).weight(1f), shape = MaterialTheme.shapes.medium,
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (active) Accent else Grid)) {
                    Column(Modifier.selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(option) })) {
                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(option, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Ink,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            RadioButton(selected = active, onClick = null)
                        }
                        Box(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) { preview(option) }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SettingsSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(detail, color = Muted, fontSize = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun StoragePage(folder: Uri?, folderPath: String?,
                       onBrowse: () -> Unit, onReset: () -> Unit, onOpen: () -> Unit) {
    SettingsHeading("CSV exports", "Keep your recorded measurements together.")
    SettingsGroup {
        Text("Save location", color = Ink, style = MaterialTheme.typography.titleSmall)
        Text(folderPath ?: "Ask where to save each export", color = Muted, fontSize = 14.sp)
        Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) { InstrumentIcon("folder", LocalContentColor.current, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Choose folder") }
        OutlinedButton(onClick = onOpen, enabled = folder != null, modifier = Modifier.fillMaxWidth()) { InstrumentIcon("folder", LocalContentColor.current, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Open folder") }
        TextButton(onClick = onReset, enabled = folder != null) { Text("Ask every time instead") }
    }
}

@Composable
private fun AboutPage(chosen: DeviceRecord?) {
    val context = LocalContext.current
    val appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: "Unknown"
    SettingsHeading("About this app", "UWB localization and motion tracking.")
    SettingsGroup {
        Text("Local workspace", color = Ink, style = MaterialTheme.typography.titleSmall)
        Text("No sign-in is required. Use Devices to manage your UWB network and Dashboard to view live measurements.",
            color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        SettingsValue("App version", appVersion)
        SettingsValue("Selected device", chosen?.mac?.ifBlank { chosen.ip } ?: "None selected")
    }
}

@Composable
private fun DevicePropertiesPage(
    snapshot: DashboardSnapshot,
    names: Map<String, String>,
    device: DeviceRecord?,
    ip: String,
    sample: JSONObject?,
    cir: JSONObject?,
    onRefresh: () -> Unit,
    onApply: (DeviceRecord, JSONObject, (String) -> Unit) -> Unit,
    onDeviceSelected: (String) -> Unit
) {
    var role by remember(device?.key) { mutableStateOf(device?.role ?: "anchor") }
    var sensing by remember(device?.key) { mutableStateOf(sample?.optInt("sensing_interval_ms")?.takeIf { it > 0 }?.toString().orEmpty()) }
    var sending by remember(device?.key) { mutableStateOf(sample?.optInt("send_interval_ms")?.takeIf { it > 0 }?.toString().orEmpty()) }
    var antenna by remember(device?.key) { mutableStateOf(sample?.optInt("antenna_delay")?.toString().orEmpty()) }
    var showDiagnostics by remember(device?.key) { mutableStateOf(false) }
    var dirty by remember(device?.key) { mutableStateOf(false) }
    var validation by remember(device?.key) { mutableStateOf("") }
    var applyStatus by remember(device?.key) { mutableStateOf("") }
    var applying by remember(device?.key) { mutableStateOf(false) }
    LaunchedEffect(device?.key, sample?.optInt("sensing_interval_ms"), sample?.optInt("send_interval_ms"), sample?.optInt("antenna_delay")) {
        if (!dirty) {
            role = sample?.optString("role")?.takeIf { it == "tag" || it == "anchor" } ?: device?.role ?: "anchor"
            sensing = sample?.optInt("sensing_interval_ms")?.takeIf { it > 0 }?.toString().orEmpty()
            sending = sample?.optInt("send_interval_ms")?.takeIf { it > 0 }?.toString().orEmpty()
            antenna = sample?.optInt("antenna_delay")?.toString().orEmpty()
        }
    }
    SettingsHeading("Device configuration", "Read current values or apply supported settings.")
    if (device == null) {
        SettingsGroup { EmptyHint("Add or select a device to inspect its properties.") }
        return
    }
    SettingsGroup {
        Text("Selected device", color = Ink, fontWeight = FontWeight.SemiBold)
        DevicePickerContent(snapshot.devices, device,
            names, snapshot.states, onDeviceSelected)
        Text("${device.mac.ifBlank { device.ip }} · ${if (snapshot.states[device.key]?.online == true) "Online" else "Offline"}", color = Muted, fontSize = 14.sp)
        OutlinedButton(shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(disabledContentColor = Muted), onClick = onRefresh, enabled = ip.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Read configuration from device") }
        Text("Supported configuration", color = Ink, fontWeight = FontWeight.SemiBold)
        Text("The firmware validates these values before applying them.", color = Muted, fontSize = 14.sp)
        Text("Device role", color = Ink, fontSize = 14.sp)
        PreferenceChoices(listOf("anchor", "tag"), role) { role = it; dirty = true }
        NumericField("Acquisition interval (20–60000 ms)", sensing, { sensing = it; dirty = true }, Modifier.fillMaxWidth())
        NumericField("Reporting interval (50–60000 ms)", sending, { sending = it; dirty = true }, Modifier.fillMaxWidth())
        NumericField("Antenna delay (0–65535 DTU)", antenna, { antenna = it; dirty = true }, Modifier.fillMaxWidth())
        if (validation.isNotBlank()) Text(validation, color = Danger, fontSize = 14.sp)
        if (applyStatus.isNotBlank()) Text(applyStatus, color = Muted, fontSize = 14.sp)
        Button(shape = MaterialTheme.shapes.medium, onClick = {
            val sense = sensing.toIntOrNull()
            val send = sending.toIntOrNull()
            val delay = antenna.toIntOrNull()
            validation = when {
                ip.isBlank() -> "This device has no IP address. Connect it to Wi-Fi first."
                sense == null || sense !in 20..60000 -> "Acquisition interval must be 20–60000 ms."
                send == null || send !in 50..60000 -> "Reporting interval must be 50–60000 ms."
                delay == null || delay !in 0..65535 -> "Antenna delay must be 0–65535 DTU."
                else -> ""
            }
            if (validation.isBlank()) {
                applying = true
                applyStatus = "Sending supported settings…"
                val body = JSONObject().put("role", role).put("sensing_interval_ms", sense)
                    .put("send_interval_ms", send).put("antenna_delay", delay)
                onApply(device.copy(role = role), body) { response ->
                    applying = false
                    val success = runCatching { JSONObject(response).optBoolean("ok") }.getOrDefault(false)
                    applyStatus = if (success) "Device accepted the configuration. Values update on the next telemetry sample."
                        else response.ifBlank { "Device did not confirm the settings." }
                    if (success) dirty = false
                }
            }
        }, enabled = !applying && ip.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (applying) "Applying…" else "Apply changes")
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
            Text(if (showDiagnostics) "Hide diagnostics" else "Show diagnostics")
        }
        if (showDiagnostics) {
            SettingsValue("Firmware version", sample?.optString("firmware_version")?.takeIf { it.isNotBlank() } ?: "Not reported by firmware")
            SettingsValue("Hardware version", sample?.optString("hardware_version")?.takeIf { it.isNotBlank() } ?: "Not reported by firmware")
            SettingsValue("BLE status", sample?.optString("ble_status")?.takeIf { it.isNotBlank() } ?: "Not reported by firmware")
            SettingsValue("UWB status", if (snapshot.states[device.key]?.online == true) "Telemetry connected" else "No current telemetry")
            SettingsValue("IMU", if (sample?.has("imu_enabled") == true) if (sample.optBoolean("imu_enabled")) "Enabled" else "Disabled" else "Not reported")
            SettingsValue("CIR", if (cir?.has("enabled") == true) if (cir.optBoolean("enabled")) "Enabled" else "Disabled" else "Not reported")
            SettingsValue("CIR capture", cir?.optString("sample_count")?.takeIf { it.isNotBlank() }?.let { "$it samples" } ?: "No sample reported")
            SettingsValue("CIR first path", cir?.optString("first_path_index")?.takeIf { it.isNotBlank() } ?: "Not reported")
            Text("Individual CIR/IMU, UWB TX/RX, LED, persistence and restore-default controls are not exposed by this firmware.",
                color = Muted, fontSize = 14.sp)
        }
        TextButton(onClick = {}, enabled = false) { Text("Restore device defaults · unsupported") }
    }
}

@Composable
private fun SettingsHeading(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Ink, fontWeight = FontWeight.Medium, fontSize = 20.sp)
        Text(detail, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SettingsValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

private fun storageFolderPath(context: android.content.Context, tree: Uri?): String? {
    if (tree == null) return null
    return runCatching {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val name = context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: id.substringAfterLast('/')
        when (tree.authority) {
            "com.android.externalstorage.documents" -> {
                val volume = id.substringBefore(':', "primary")
                val relativePath = id.substringAfter(':', "").trim('/')
                val root = if (volume.equals("primary", true)) "Internal storage" else "External storage"
                if (relativePath.isBlank()) root else "$root/$relativePath"
            }
            "com.android.providers.downloads.documents" -> "Downloads/$name"
            else -> "$name (${tree.authority})"
        }
    }.getOrNull()
}

@Composable
private fun DevicePickerContent(devices: List<DeviceRecord>, chosen: DeviceRecord?, names: Map<String, String>,
                                states: Map<String, DeviceState>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, enabled = devices.isNotEmpty(), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { contentDescription = "Select dashboard device" }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InstrumentIcon("devices", Accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(chosen?.let { "${names[it.key] ?: "Device"} · ${it.role.replaceFirstChar(Char::uppercaseChar)}" }
                        ?: "No configured devices", color = Ink, style = MaterialTheme.typography.titleSmall)
                    chosen?.let {
                        Text(it.mac.ifBlank { it.ip }, color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (chosen != null) Text(if (states[chosen.key]?.online == true) "Online" else "Offline",
                    color = if (states[chosen.key]?.online == true) Positive else Muted,
                    style = MaterialTheme.typography.labelSmall)
                InstrumentIcon("chevron", Muted)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(.88f)) {
            devices.forEach { device ->
                DropdownMenuItem(leadingIcon = { InstrumentIcon("devices", Muted) }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${names[device.key] ?: "Device"} | ${device.role.replaceFirstChar(Char::uppercaseChar)}", color = Ink)
                        Text(device.mac.ifBlank { device.ip }, color = Muted, fontSize = 13.sp)
                    }
                }, onClick = { onSelect(device.key); expanded = false })
            }
        }
    }
}

@Composable
private fun BatteryInfoState(message: String, warning: Boolean = false) {
    Surface(shape = RoundedCornerShape(24.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = if (warning) Warning else Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BatteryTelemetryCard(percent: Double, voltage: Double, reduceAnimations: Boolean) {
    val tone by animateColorAsState(batteryLevelColor(percent, MaterialTheme.colorScheme.surface.luminance() < .5f),
        animationSpec = tween(if (reduceAnimations) 0 else 300), label = "battery-detail-color")
    val fill by animateFloatAsState((percent/100).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(if (reduceAnimations) 0 else 300), label = "battery-detail-fill")
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, tone.copy(alpha = .3f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Charge", color = Muted, fontSize = 12.sp)
                    Text("${percent.toInt()}%", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Voltage", color = Muted, fontSize = 12.sp)
                    Text(String.format(Locale.US, "%.2f V", voltage), color = Ink, fontSize = 18.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.small).background(tone.copy(alpha = .14f))) {
                Box(Modifier.fillMaxWidth(fill).height(8.dp).background(tone))
            }
        }
    }
}

@Composable
private fun BatteryIcon(label: String, percent: Double, tint: Color, modifier: Modifier = Modifier, reduceAnimations: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
        BatteryGlyph(percent.toFloat(), tint, Modifier.size(width = 28.dp, height = 16.dp), reduceAnimations)
        Text(label, color = Ink, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
    }
}

@Composable
private fun BatteryGlyph(percent: Float, tint: Color, modifier: Modifier, reduceAnimations: Boolean) {
    val fill by animateFloatAsState(percent.coerceIn(0f, 100f)/100f,
        animationSpec = tween(if (reduceAnimations) 0 else 300), label = "battery-icon-fill")
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val bodyWidth = size.width-4.dp.toPx()
        val inset = stroke/2
        val bodySize = androidx.compose.ui.geometry.Size(bodyWidth-stroke, size.height-stroke)
        val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        drawRoundRect(tint.copy(alpha = .1f), topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = bodySize, cornerRadius = corner)
        drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = bodySize, cornerRadius = corner, style = Stroke(stroke))
        val innerWidth = (bodySize.width-stroke*2).coerceAtLeast(0f)*fill
        if (innerWidth > 0f) drawRoundRect(tint.copy(alpha = .85f),
            topLeft = androidx.compose.ui.geometry.Offset(inset+stroke, inset+stroke),
            size = androidx.compose.ui.geometry.Size(innerWidth, bodySize.height-stroke*2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
        drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(bodyWidth+1.dp.toPx(), size.height*.3f),
            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height*.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
    }
}

@Composable
private fun DashboardGroup(withContainers: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (withContainers) {
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
        }
    } else Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), content = content)
}

@Composable
private fun GroupedSection(title: String, headerAction: (@Composable () -> Unit)? = null,
                           content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp, modifier = Modifier.weight(1f))
            headerAction?.invoke()
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun GroupDivider() {
    Spacer(Modifier.height(24.dp))
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Grid))
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun DevicesPage(snapshot: DashboardSnapshot, names: Map<String, String>, repo: DeviceRepository,
                                onScan: () -> Unit, onAdd: () -> Unit, onEdit: (DeviceRecord) -> Unit,
                                onClear: () -> Unit, onWifiSetup: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Page) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Devices", color = Ink, style = MaterialTheme.typography.headlineMedium)
                    Text("${snapshot.devices.size} saved \u00b7 ${snapshot.discovered.size} discovered", color = Muted, fontSize = 14.sp)
                }
                TextButton(onClick = onWifiSetup) { InstrumentIcon("wifi", LocalContentColor.current, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Wi-Fi setup") }
            }
            Text("Add devices, assign roles, and set measured X/Y/Z map coordinates.", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(shape = MaterialTheme.shapes.medium,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(disabledContentColor = Muted), onClick = onScan, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp)) { InstrumentIcon("scan", LocalContentColor.current, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Scan network") }
                Button(shape = MaterialTheme.shapes.medium,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContentColor = Muted), onClick = onAdd, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp)) { InstrumentIcon("add", LocalContentColor.current, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Add device") }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                FeedbackMessage(snapshot.status, busy = snapshot.status.startsWith("Scanning"))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot.devices.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Saved devices", color = Ink, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClear) { Text("Clear all", color = Danger) }
                    }
                }
                items(snapshot.devices, key = { it.key }) { device ->
                    ConfigurationDeviceRow(device, names[device.key] ?: "Device", snapshot.states[device.key],
                        onEdit = { onEdit(device) }, onDelete = { repo.remove(device.key) },
                        onApplyRole = {
                            if (device.ip.isBlank()) repo.setStatus("Scan or add an IP before applying the role")
                            else repo.postControl(device.ip, JSONObject().put("role", device.role), device.mac)
                        })
                }
                val available = snapshot.discovered.filter { found ->
                    snapshot.devices.none { device ->
                        if (found.mac.isNotBlank() && device.mac.isNotBlank())
                            found.mac.equals(device.mac, ignoreCase = true)
                        else device.ip == found.ip
                    }
                }
                if (available.isEmpty() && snapshot.devices.isEmpty()) item {
                    EmptyHint("No devices configured yet. Scan the shared Wi-Fi network or add a device by MAC address or IP.")
                }
                if (available.isNotEmpty()) item {
                    Text("Discovered on network", color = Accent, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                }
                items(available, key = { "found-${it.mac}" }) { found ->
                    Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SelectionContainer {
                                Text(found.mac, color = Ink, fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${found.ip} · ${found.role.replaceFirstChar(Char::uppercaseChar)}", color = Muted, fontSize = 14.sp,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.positiveContainer, shape = RoundedCornerShape(4.dp)) {
                                    Text("Found", color = Positive, fontWeight = FontWeight.Medium, fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                                TextButton(onClick = {
                                    onEdit(DeviceRecord(found.mac, found.ip, found.role.takeIf { it == "tag" || it == "anchor" } ?: "anchor"))
                                }) { Text("Configure") }
                            }
                        }
                    }
                }
            }
        }
    }
}


internal data class SavedWifiCredentials(val ssid: String = "", val password: String = "")
internal data class DeviceSecurity(val tlsFingerprint: String = "")

internal object LastWifiCredentialStore {
    private const val PREFS = "wifi_provisioning"
    private const val VALUE = "last_credentials"
    private const val KEY_ALIAS = "uwb_wifi_credentials_v1"
    private const val IV_LENGTH = 12

    fun load(context: android.content.Context): SavedWifiCredentials {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val encoded = prefs.getString(VALUE, null) ?: return SavedWifiCredentials()
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_LENGTH)
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            SavedWifiCredentials(json.optString("ssid"), json.optString("password"))
        } catch (_: Exception) {
            prefs.edit().remove(VALUE).apply()
            SavedWifiCredentials()
        }
    }

    fun save(context: android.content.Context, ssid: String, password: String): Boolean = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val plaintext = JSONObject().put("ssid", ssid).put("password", password)
            .toString().toByteArray(Charsets.UTF_8)
        val packed = cipher.iv + cipher.doFinal(plaintext)
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString(VALUE, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
        true
    } catch (_: Exception) {
        false
    }

    fun loadDeviceSecurity(context: android.content.Context, mac: String): DeviceSecurity {
        val value = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString("device_${deviceIdentity(mac)}", null) ?: return DeviceSecurity()
        return try {
            val packed = Base64.decode(value, Base64.NO_WRAP)
            require(packed.size > IV_LENGTH)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                GCMParameterSpec(128, packed.copyOfRange(0, IV_LENGTH)))
            val json = JSONObject(String(cipher.doFinal(packed.copyOfRange(IV_LENGTH, packed.size)), Charsets.UTF_8))
            DeviceSecurity(json.optString("tls_fingerprint"))
        } catch (_: Exception) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .remove("device_${deviceIdentity(mac)}").apply()
            DeviceSecurity()
        }
    }

    fun saveDeviceSecurity(context: android.content.Context, mac: String,
                           tlsFingerprint: String): Boolean = try {
        val key = "device_${deviceIdentity(mac)}"
        if (tlsFingerprint.isBlank()) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .remove(key).apply()
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val plaintext = JSONObject().put("tls_fingerprint", tlsFingerprint.trim())
                .toString().toByteArray(Charsets.UTF_8)
            val packed = cipher.iv + cipher.doFinal(plaintext)
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun deviceIdentity(value: String): String =
        normalizeMac(value).ifBlank { value.trim().lowercase(Locale.ROOT) }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        } catch (_: Exception) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}

private data class BleProvisionTarget(val device: BluetoothDevice, val serviceUuid: String)

private class ProvisionConnectionObserver(
    private val onConnected: () -> Unit,
    private val onFailed: (String) -> Unit
) {
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onConnectionEvent(event: DeviceConnectionEvent) {
        when (event.eventType) {
            ESPConstants.EVENT_DEVICE_CONNECTED -> onConnected()
            ESPConstants.EVENT_DEVICE_CONNECTION_FAILED -> onFailed("Could not connect to the device")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiProvisioningDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val manager = remember { ESPProvisionManager.getInstance(context.applicationContext) }
    var devices by remember { mutableStateOf<List<BleProvisionTarget>>(emptyList()) }
    val savedCredentials = remember { LastWifiCredentialStore.load(context) }
    var ssid by remember { mutableStateOf(savedCredentials.ssid) }
    var password by remember { mutableStateOf(savedCredentials.password) }
    LaunchedEffect(ssid, password) {
        delay(300)
        LastWifiCredentialStore.save(context, ssid, password)
    }
    fun dismissDialog() {
        LastWifiCredentialStore.save(context, ssid, password)
        onDismiss()
    }
    var status by remember { mutableStateOf("Searching for nearby provisioning devices…") }
    var scanning by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var bleConnected by remember { mutableStateOf(false) }
    var activeDevice by remember { mutableStateOf<ESPDevice?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    val observer = remember {
        ProvisionConnectionObserver(
            onConnected = { bleConnected = true; status = "Connected. Enter the Wi-Fi network name and password." },
            onFailed = { bleConnected = false; status = it }
        )
    }
    DisposableEffect(Unit) {
        EventBus.getDefault().register(observer)
        onDispose {
            EventBus.getDefault().unregister(observer)
            activeDevice?.disconnectDevice()
        }
    }
    val scan = {
        devices = emptyList()
        scanning = true
        status = "Searching for nearby provisioning devices…"
        manager.searchBleEspDevices(object : BleScanListener {
            override fun scanStartFailed() {
                scanning = false
                status = "Bluetooth is off or scanning could not start"
            }
            override fun onPeripheralFound(device: BluetoothDevice, scanResult: ScanResult) {
                val services = scanResult.scanRecord?.serviceUuids.orEmpty()
                val provisioningService = services.firstOrNull {
                    it.uuid.toString().equals(ESP_PROVISIONING_SERVICE_UUID, ignoreCase = true)
                } ?: return
                val uuid = provisioningService.uuid.toString()
                val found = BleProvisionTarget(device, uuid)
                if (devices.none { it.device.address == device.address }) devices = devices + found
            }
            override fun scanCompleted() {
                scanning = false
                if (devices.isEmpty()) status = "No provisioning device found. Check power and try again."
                else status = "Select the UWB device to connect."
            }
            override fun onFailure(e: Exception) {
                scanning = false
                status = "BLE scan failed: " + (e.localizedMessage ?: "unknown error")
            }
        })
    }
    LaunchedEffect(Unit) { scan() }

    Dialog(onDismissRequest = ::dismissDialog, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(.94f).heightIn(max = 760.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Device Wi-Fi", color = Ink, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = ::dismissDialog) { Text("Close") }
                }
                Text("Use a Wi-Fi network shared by your phone and UWB devices. If using your phone's hotspot, keep it enabled during setup.",
                    color = Muted, fontSize = 14.sp)
                Text("1. Choose a UWB device", color = Ink, style = MaterialTheme.typography.titleSmall)
                OutlinedButton(shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(disabledContentColor = Muted), onClick = scan, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (scanning) "Scanning…" else "Scan for UWB devices")
                }
                if (devices.isNotEmpty()) {
                    LazyColumn(Modifier.height(128.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(devices) { target ->
                            val deviceLabel = try { target.device.name ?: target.device.address }
                                catch (_: SecurityException) { "UWB device (Bluetooth permission unavailable)" }
                            OutlinedButton(shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(disabledContentColor = Muted), onClick = {
                                bleConnected = false
                                status = "Connecting to " + deviceLabel + "…"
                                val esp = manager.createESPDevice(ESPConstants.TransportType.TRANSPORT_BLE,
                                    ESPConstants.SecurityType.SECURITY_1)
                                esp.setProofOfPossession("")
                                esp.setBluetoothDevice(target.device)
                                esp.setPrimaryServiceUuid(target.serviceUuid)
                                activeDevice = esp
                                esp.connectBLEDevice(target.device, target.serviceUuid)
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(deviceLabel, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text("2. Connect it to Wi-Fi", color = Ink, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = ssid, onValueChange = { ssid = it }, label = { Text("Wi-Fi network name") },
                    shape = MaterialTheme.shapes.small, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Wi-Fi password") },
                    shape = MaterialTheme.shapes.small, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.semantics {
                            contentDescription = if (showPassword) "Hide Wi-Fi password" else "Show Wi-Fi password"
                        }) { InstrumentIcon(if (showPassword) "eyeOff" else "eye", Muted) }
                    },
                    modifier = Modifier.fillMaxWidth())
                FeedbackMessage(status, busy = scanning || sending)
                if (!bleConnected) Text("Connect to a nearby UWB device to enable credential transfer", color = Muted, fontSize = 14.sp)
                Button(shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContentColor = Muted), onClick = {
                    focusManager.clearFocus()
                    LastWifiCredentialStore.save(context, ssid, password)
                    val esp = activeDevice
                    if (esp == null || !bleConnected || ssid.isBlank()) {
                        status = if (esp == null || !bleConnected) "Select and connect to a device first" else "Enter the Wi-Fi network name"
                        return@Button
                    }
                    if (ssid.trim().toByteArray(Charsets.UTF_8).size !in 1..32) {
                        status = "Wi-Fi network name must be 1 to 32 bytes"
                        return@Button
                    }
                    val passwordBytes = password.toByteArray(Charsets.UTF_8)
                    if (passwordBytes.isNotEmpty() && passwordBytes.size !in 8..63 &&
                        !(passwordBytes.size == 64 && password.all { it.digitToIntOrNull(16) != null })) {
                        status = "Use an open network, an 8-63 character password, or a 64-digit hexadecimal key"
                        return@Button
                    }
                    sending = true
                    status = "Sending credentials and waiting for Wi-Fi…"
                    esp.provision(ssid.trim(), password, object : ProvisionListener {
                        override fun createSessionFailed(e: Exception) {
                            sending = false; status = "Could not create secure session: " + (e.localizedMessage ?: "")
                        }
                        override fun wifiConfigSent() { status = "Credentials sent securely to the device" }
                        override fun wifiConfigFailed(e: Exception) {
                            sending = false; status = "Could not send credentials: " + (e.localizedMessage ?: "")
                        }
                        override fun wifiConfigApplied() { status = "Credentials received; testing the Wi-Fi connection…" }
                        override fun wifiConfigApplyFailed(e: Exception) {
                            sending = false; status = "Wi-Fi connection failed. Device attempted to restore its previous network; verify its connection."
                        }
                        override fun provisioningFailedFromDevice(failureReason: ESPConstants.ProvisionFailureReason) {
                            sending = false; status = "Wi-Fi connection failed (" + failureReason + "). Device attempted to restore its previous network; verify its connection."
                        }
                        override fun deviceProvisioningSuccess() {
                            sending = false; status = "Wi-Fi connected. Device setup is complete."
                        }
                        override fun onProvisioningFailed(e: Exception) {
                            sending = false; status = "Provisioning failed: " + (e.localizedMessage ?: "unknown error")
                        }
                    })
                }, enabled = !sending && bleConnected && ssid.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (sending) "Connecting…" else "Send Wi-Fi credentials")
                }
            }
        }
    }
}

@Composable
private fun MapLegend() {
    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MapLegendItem(false, "Online anchor", Positive)
        MapLegendItem(false, "Offline anchor", Muted)
        MapLegendItem(true, "Tag", Accent)
    }
}

@Composable
private fun MapLegendItem(tag: Boolean, label: String, tint: Color, modifier: Modifier = Modifier) {
    val ink = Ink
    val muted = Muted
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp)) {
            if (tag) {
                drawCircle(tint, radius = size.minDimension * .34f)
                drawCircle(ink, radius = size.minDimension * .34f, style = Stroke(1.dp.toPx()))
            } else {
                val triangle = Path().apply {
                    moveTo(size.width / 2, 1.dp.toPx())
                    lineTo(1.dp.toPx(), size.height - 1.dp.toPx())
                    lineTo(size.width - 1.dp.toPx(), size.height - 1.dp.toPx())
                    close()
                }
                drawPath(triangle, tint)
                drawPath(triangle, ink, style = Stroke(1.dp.toPx()))
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(label, color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun ConfigurationDeviceRow(device: DeviceRecord, name: String, state: DeviceState?, onEdit: () -> Unit,
                                   onDelete: () -> Unit, onApplyRole: () -> Unit) {
    var actionsOpen by remember(device.key) { mutableStateOf(false) }
    val receivedAt = state?.sample?.optLong("_received_at", 0L) ?: 0L
    val ageMs = (System.currentTimeMillis() - receivedAt).coerceAtLeast(0L)
    val presence = when {
        state?.online == true -> "Online"
        receivedAt > 0L && ageMs <= 15_000L -> "Stale"
        else -> "Offline"
    }
    val presenceColor = when (presence) {
        "Online" -> Positive
        "Stale" -> Warning
        else -> Muted
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, color = Ink, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Surface(color = if (device.role == "anchor") MaterialTheme.colorScheme.positiveContainer else MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraSmall) {
                    Text(device.role.replaceFirstChar(Char::uppercaseChar),
                        color = if (device.role == "anchor") MaterialTheme.colorScheme.onPositiveContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Text(presence, color = presenceColor, fontSize = 13.sp)
                Box {
                    IconButton(onClick = { actionsOpen = true },
                        modifier = Modifier.semantics { contentDescription = "Actions for $name" }) {
                        InstrumentIcon("menu", Muted)
                    }
                    DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                        DropdownMenuItem(leadingIcon = { InstrumentIcon("edit", Muted) }, text = { Text("Edit device") }, onClick = { actionsOpen = false; onEdit() })
                        DropdownMenuItem(leadingIcon = { InstrumentIcon("tune", Muted) }, text = { Text("Apply role") }, onClick = { actionsOpen = false; onApplyRole() })
                        DropdownMenuItem(leadingIcon = { InstrumentIcon("delete", Danger) }, text = { Text("Remove device", color = Danger) }, onClick = { actionsOpen = false; onDelete() })
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer {
                    Text(device.mac.ifBlank { device.ip }, color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                if (device.ip.isNotBlank() && device.mac.isNotBlank()) {
                    SelectionContainer { Text(device.ip, color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("X" to device.x, "Y" to device.y, "Z" to device.z).forEach { (axis, value) ->
                    Text("$axis ${value.fmt()} m", color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
            val freshness = when {
                receivedAt <= 0L -> "No telemetry yet"
                ageMs < 1_000L -> "Updated just now"
                else -> "Updated ${ageMs / 1_000L}s ago"
            }
            Text(freshness, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InstrumentIcon("info", Muted, Modifier.size(24.dp))
        Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private fun cirHeatColor(value: Double, maximum: Double): Color {
    val level = sqrt((value / maximum.coerceAtLeast(1.0)).coerceIn(0.0, 1.0))
    val rgb = when {
        level < .38 -> {
            val t = level / .38
            Triple(3, (22 + 66 * t).toInt(), (54 + 105 * t).toInt())
        }
        level < .72 -> {
            val t = (level - .38) / .34
            Triple((4 + 20 * t).toInt(), (88 + 160 * t).toInt(), (159 - 135 * t).toInt())
        }
        else -> {
            val t = (level - .72) / .28
            Triple((24 + 231 * t).toInt(), (248 - 218 * t).toInt(), (24 - 14 * t).toInt())
        }
    }
    return Color(rgb.first / 255f, rgb.second / 255f, rgb.third / 255f)
}

@Composable
private fun CirPlot(data: JSONObject, history: List<CirFrame>, modifier: Modifier = Modifier) {
    val iq = remember(data) {
        val array = data.optJSONArray("iq")
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { i ->
            val pair = array.optJSONArray(i) ?: return@mapNotNull null
            if (pair.length() < 2) null else kotlin.math.hypot(pair.optDouble(0), pair.optDouble(1))
        }
    }
    if (iq.isEmpty()) { EmptyHint("CIR capture is disabled or not valid yet."); return }
    val sampleStart = data.optInt("start_index")
    val peakOffset = iq.indices.maxByOrNull { iq[it] } ?: return
    val peakIndex = (sampleStart + peakOffset).coerceIn(0, 1015)
    val peakPadding = min(100, min(peakIndex, 1015 - peakIndex)).coerceAtLeast(1)
    val windowStart = peakIndex - peakPadding
    val windowEnd = peakIndex + peakPadding
    val windowSpan = max(1, windowEnd - windowStart)
    val firstPath = data.optInt("first_path_index") / 64.0
    val firstPathVisible = firstPath in windowStart.toDouble()..windowEnd.toDouble()
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B2330))) {
        val left = 40.dp.toPx()
        val right = size.width - 9.dp.toPx()
        val top = 22.dp.toPx()
        val traceBottom = size.height * .36f
        val heatTop = traceBottom + 34.dp.toPx()
        val heatBottom = size.height - 27.dp.toPx()
        drawRect(Color(0xFF1B2330))

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(226, 231, 239)
            textSize = 12.sp.toPx()
            isAntiAlias = true
        }
        drawIntoCanvas { it.nativeCanvas.drawText("CIR magnitude", left, 13.dp.toPx(), textPaint) }

        val amplitudeMax = max(iq.maxOrNull() ?: 1.0, 1.0)
        repeat(4) { tick ->
            val fraction = tick / 3f
            val y = traceBottom - fraction * (traceBottom - top)
            drawLine(Color(0xFF3C4755), androidx.compose.ui.geometry.Offset(left, y),
                androidx.compose.ui.geometry.Offset(right, y), 1f)
        }
        val trace = Path()
        val area = Path()
        var started = false
        iq.forEachIndexed { offset, value ->
            val sampleIndex = sampleStart + offset
            if (sampleIndex in windowStart..windowEnd) {
                val x = left + (sampleIndex - windowStart).toFloat() / windowSpan * (right - left)
                val y = traceBottom - (traceBottom - top) * (value / (amplitudeMax * 1.08)).toFloat()
                if (!started) {
                    trace.moveTo(x, y)
                    area.moveTo(x, traceBottom)
                    area.lineTo(x, y)
                    started = true
                } else {
                    trace.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
        }
        if (started) {
            area.lineTo(left + (sampleStart + iq.lastIndex - windowStart).coerceIn(0, windowSpan).toFloat() /
                windowSpan * (right - left), traceBottom)
            area.close()
            drawPath(area, Color(0xFF25364B))
            drawPath(trace, Color(0xFF94B5D8), style = Stroke(1.5.dp.toPx()))
        }
        if (firstPathVisible) {
            val x = left + ((firstPath - windowStart) / windowSpan).toFloat() * (right - left)
            drawLine(Color(0xFFE8AA76), androidx.compose.ui.geometry.Offset(x, top),
                androidx.compose.ui.geometry.Offset(x, traceBottom), 1.dp.toPx())
        }

        drawIntoCanvas {
            textPaint.color = android.graphics.Color.rgb(226, 231, 239)
            it.nativeCanvas.drawText("CIR waterfall \u00b7 oldest to newest \u00b7 ${history.size}",
                left, heatTop - 7.dp.toPx(), textPaint)
        }
        drawRect(Color(0xFF202C3C), androidx.compose.ui.geometry.Offset(left, heatTop),
            androidx.compose.ui.geometry.Size(right - left, heatBottom - heatTop))
        val strongest = max(history.maxOfOrNull { it.magnitude.maxOrNull() ?: 0.0 } ?: 0.0, 1.0)
        if (history.isNotEmpty() && heatBottom > heatTop) {
            val rowHeight = (heatBottom - heatTop) / history.size
            history.forEachIndexed { row, frame ->
                val y = heatTop + row * rowHeight
                frame.magnitude.forEachIndexed { offset, magnitude ->
                    val sampleIndex = frame.startIndex + offset
                    if (sampleIndex in windowStart..windowEnd) {
                        val x0 = left + (sampleIndex - windowStart).toFloat() / windowSpan * (right - left)
                        val x1 = left + (sampleIndex + 1 - windowStart).toFloat() / windowSpan * (right - left)
                        drawRect(cirHeatColor(magnitude, strongest),
                            androidx.compose.ui.geometry.Offset(x0, y),
                            androidx.compose.ui.geometry.Size(max(1f, x1 - x0 + .4f), rowHeight + .35f))
                    }
                }
            }
        }
        if (firstPathVisible) {
            val x = left + ((firstPath - windowStart) / windowSpan).toFloat() * (right - left)
            drawLine(Color(0xFFE8AA76), androidx.compose.ui.geometry.Offset(x, heatTop),
                androidx.compose.ui.geometry.Offset(x, heatBottom), 1.dp.toPx())
        }
        drawIntoCanvas { canvas ->
            textPaint.textSize = 12.sp.toPx()
            textPaint.color = android.graphics.Color.rgb(186, 197, 211)
            listOf(0f, .5f, 1f).forEach { fraction ->
                val x = left + fraction * (right - left)
                val label = "${(windowStart + fraction * windowSpan).toInt()}"
                canvas.nativeCanvas.drawText(label, x - textPaint.measureText(label) / 2,
                    size.height - 8.dp.toPx(), textPaint)
            }
            if (firstPathVisible) {
                textPaint.color = android.graphics.Color.rgb(232, 170, 118)
                val label = "FP ${"%.1f".format(Locale.US, firstPath)}"
                canvas.nativeCanvas.drawText(label, left + 3.dp.toPx(), top + 10.dp.toPx(), textPaint)
            }
        }
    }
}

@Composable
private fun ImuChart(title: String, history: List<ImuReading>, acceleration: Boolean,
                     modifier: Modifier = Modifier) {
    val axisColors = listOf(Danger, Positive, MaterialTheme.colorScheme.axisZ)
    val gridColor = Grid
    val mutedColor = Muted
    val series = if (acceleration) {
        listOf(history.map { it.ax }, history.map { it.ay }, history.map { it.az })
    } else {
        listOf(history.map { it.gx }, history.map { it.gy }, history.map { it.gz })
    }
    val latest = history.lastOrNull()
    val current = if (latest == null) emptyList() else if (acceleration)
        listOf(latest.ax, latest.ay, latest.az) else listOf(latest.gx, latest.gy, latest.gz)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Muted, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            listOf("X", "Y", "Z").forEachIndexed { i, axis ->
                Text(axis, color = axisColors[i], fontWeight = FontWeight.Medium,
                    fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "$title. Recent X, Y and Z readings" }.padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)) {
            val left = 52.dp.toPx()
            val right = size.width - 7.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 14.dp.toPx()
            val minimum = if (acceleration) 1.2 else 0.5
            val maximum = series.flatten().maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
            val rawPeak = max(minimum, maximum * 1.15)
            val base = Math.pow(10.0, floor(log10(rawPeak)))
            val normalized = rawPeak / base
            val nice = listOf(1.0, 1.25, 1.5, 2.0, 2.5, 5.0, 7.5, 10.0)
                .first { normalized <= it }
            val peak = base * nice
            val center = (top + bottom) / 2
            listOf(0f, .5f, 1f).forEach { fraction ->
                val y = top + (bottom - top) * fraction
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(left, y),
                    androidx.compose.ui.geometry.Offset(right, y), 1f)
            }
            series.forEachIndexed { axis, values ->
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = left + (right - left) * index / max(values.lastIndex, 1)
                    val y = center - (value / peak).toFloat() * (bottom - top) / 2
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (values.size == 1) {
                    val y = center - (values[0] / peak).toFloat() * (bottom - top) / 2
                    drawCircle(axisColors[axis], 2.5f,
                        androidx.compose.ui.geometry.Offset(left, y))
                } else if (values.size > 1) drawPath(path, axisColors[axis], style = Stroke(2f))
            }
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = mutedColor.toArgb()
                    textSize = 12.sp.toPx()
                    textAlign = android.graphics.Paint.Align.RIGHT
                    isAntiAlias = true
                }
                val labelX = left - 5.dp.toPx()
                canvas.nativeCanvas.drawText("+${formatAxisTick(peak)}", labelX, top + paint.textSize, paint)
                canvas.nativeCanvas.drawText("0", labelX, center + paint.textSize / 3, paint)
                canvas.nativeCanvas.drawText("-${formatAxisTick(peak)}", labelX, bottom, paint)
            }
        }
        if (current.isNotEmpty()) {
            Text(current.mapIndexed { i, value -> "${listOf("X", "Y", "Z")[i]} ${String.format(Locale.US, "%+.2f", value)}" }
                .joinToString("    "), color = Ink, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, headerAction: (@Composable () -> Unit)? = null,
                        content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp, modifier = Modifier.weight(1f))
                headerAction?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DeviceEditorDialog(device: DeviceRecord?, onDismiss: () -> Unit,
                               initialSecurity: DeviceSecurity,
                               onSave: (DeviceRecord, String) -> Unit) {
    var mac by remember(device) { mutableStateOf(device?.mac.orEmpty()) }
    var ip by remember(device) { mutableStateOf(device?.ip.orEmpty()) }
    var role by remember(device) { mutableStateOf(device?.role ?: "anchor") }
    var x by remember(device) { mutableStateOf(device?.x?.toString() ?: "0") }
    var y by remember(device) { mutableStateOf(device?.y?.toString() ?: "0") }
    var z by remember(device) { mutableStateOf(device?.z?.toString() ?: "0") }
    var yaw by remember(device) { mutableStateOf(device?.yawOffset?.toString() ?: "0") }
    var tlsFingerprint by remember(device) { mutableStateOf(initialSecurity.tlsFingerprint) }
    var error by remember { mutableStateOf("") }
    fun record(): DeviceRecord? {
        val values = listOf(x.toDoubleOrNull(), y.toDoubleOrNull(), z.toDoubleOrNull(), yaw.toDoubleOrNull())
        if (values.any { it == null }) { error = "Enter valid numeric coordinates"; return null }
        return DeviceRecord(mac, ip, role, values[0]!!, values[1]!!, values[2]!!, values[3]!!)
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (device == null) "Add UWB device" else "Edit UWB device") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(mac, { mac = it }, label = { Text("MAC address") }, shape = MaterialTheme.shapes.small, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ip, { ip = it }, label = { Text("IPv4 address") }, shape = MaterialTheme.shapes.small, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Device role", color = Ink, style = MaterialTheme.typography.titleSmall)
                PreferenceChoices(listOf("anchor", "tag"), role) { role = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumericField("X (m)", x, { x = it }, Modifier.weight(1f))
                    NumericField("Y (m)", y, { y = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumericField("Z (m)", z, { z = it }, Modifier.weight(1f))
                    NumericField("Yaw offset°", yaw, { yaw = it }, Modifier.weight(1f))
                }
                OutlinedTextField(tlsFingerprint, { tlsFingerprint = it },
                    label = { Text("TLS fingerprint (optional)") },
                    supportingText = { Text("Leave blank to use local HTTP") }, shape = MaterialTheme.shapes.small, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = {
            val fingerprint = tlsFingerprint.filterNot { it == ':' || it.isWhitespace() }
            if (fingerprint.isNotBlank() && (fingerprint.length != 64 || fingerprint.any { it.digitToIntOrNull(16) == null })) {
                error = "Fingerprint must be blank or 64 hexadecimal characters"; return@TextButton
            }
            record()?.let { onSave(it, fingerprint.uppercase(Locale.ROOT)) }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun NumericField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 14.sp) }, modifier = modifier,
        shape = MaterialTheme.shapes.small, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

private class MapViewport {
    val zoom = mutableStateOf(1f)
    val pan = mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    var lastFitRequest: Int? = null
}

@Composable
private fun LocalizationMap(devices: List<DeviceRecord>, positions: Map<String, Position>, names: Map<String, String>,
                            states: Map<String, DeviceState>, fitRequest: Int, modifier: Modifier = Modifier, viewport: MapViewport? = null) {
    val ink = Ink
    val muted = Muted
    val green = Positive
    val cyan = Accent
    val axisX = Danger
    val axisY = Positive
    val grid = Grid
    val mapSurface = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val anchors = devices.filter { it.role == "anchor" }
    val points = anchors.map { it.x to it.y } + positions.values.map { it.x to it.y }
    if (points.isEmpty()) return
    val view = viewport ?: remember { MapViewport() }
    var zoom by view.zoom
    var pan by view.pan
    LaunchedEffect(fitRequest) {
        if (view.lastFitRequest != fitRequest) {
            zoom = 1f
            pan = androidx.compose.ui.geometry.Offset.Zero
            view.lastFitRequest = fitRequest
        }
    }
    val gestures = Modifier
        .pointerInput(Unit) {
            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                val nextZoom = (zoom * zoomChange).coerceIn(.45f, 2.5f)
                val factor = nextZoom / zoom
                val fromCenter = centroid - androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                pan = (pan - fromCenter) * factor + fromCenter + panChange
                zoom = nextZoom
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = {
                zoom = 1f
                pan = androidx.compose.ui.geometry.Offset.Zero
            })
        }
    Canvas(modifier.background(mapSurface).semantics { contentDescription = "Localization map. ${anchors.size} anchors and ${positions.size} located tags. Coordinates in meters. Pinch to zoom, drag to pan, double tap to fit." }.then(gestures)) {
        val margin = 32f
        val topMargin = 20f
        var minX = points.minOf { it.first }; var maxX = points.maxOf { it.first }
        var minY = points.minOf { it.second }; var maxY = points.maxOf { it.second }
        val pad = max(.05, max(maxX - minX, maxY - minY) * .15)
        minX -= pad; maxX += pad; minY -= pad; maxY += pad
        val spanX = max(maxX - minX, .1)
        val spanY = max(maxY - minY, .1)
        val aspect = (max(size.width - margin * 2, 1f) / max(size.height - margin - topMargin, 1f)).toDouble()
        if (spanX / spanY < aspect) {
            val extra = (spanY * aspect - spanX) / 2
            minX -= extra; maxX += extra
        } else {
            val extra = (spanX / aspect - spanY) / 2
            minY -= extra; maxY += extra
        }
        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2
        val halfX = (maxX - minX) / (2 * zoom)
        val halfY = (maxY - minY) / (2 * zoom)
        minX = centerX - halfX; maxX = centerX + halfX
        minY = centerY - halfY; maxY = centerY + halfY
        val scale = (size.width - margin * 2) / (maxX - minX)
        fun screen(x: Double, y: Double): androidx.compose.ui.geometry.Offset {
            return androidx.compose.ui.geometry.Offset(
                margin + ((x - minX) * scale).toFloat() + pan.x,
                size.height - margin - ((y - minY) * scale).toFloat() + pan.y)
        }
        val raw = max(min(maxX - minX, maxY - minY) / 10, .01)
        val base = Math.pow(10.0, floor(log10(raw)))
        val step = base * when { raw / base > 2 -> 5; raw / base > 1 -> 2; else -> 1 }
        val scalePerUnit = scale.toDouble()
        val visibleMinX = minX - (pan.x.toDouble() + margin.toDouble()) / scalePerUnit
        val visibleMaxX = minX + (size.width.toDouble() - margin.toDouble() - pan.x.toDouble()) / scalePerUnit
        var gx = ceil(visibleMinX / step) * step
        while (gx <= visibleMaxX) {
            val x = screen(gx, 0.0).x
            drawLine(grid, androidx.compose.ui.geometry.Offset(x, 0f),
                androidx.compose.ui.geometry.Offset(x, size.height), 1f)
            gx += step
        }
        val visibleMinY = minY + (pan.y.toDouble() - margin.toDouble()) / scalePerUnit
        val visibleMaxY = minY + (size.height.toDouble() - margin.toDouble() + pan.y.toDouble()) / scalePerUnit
        var gy = ceil(visibleMinY / step) * step
        while (gy <= visibleMaxY) {
            val y = screen(0.0, gy).y
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y),
                androidx.compose.ui.geometry.Offset(size.width, y), 1f)
            gy += step
        }
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = muted.toArgb(); textSize = 11.sp.toPx()
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val paper = android.graphics.Paint().apply { color = mapSurface.toArgb() }
            fun tick(label: String, x: Float, baseline: Float) {
                val width = paint.measureText(label)
                canvas.nativeCanvas.drawRect(x-2, baseline+paint.fontMetrics.ascent-2,
                    x+width+2, baseline+paint.fontMetrics.descent+2, paper)
                canvas.nativeCanvas.drawText(label,x,baseline,paint)
            }
            var xTick = ceil(visibleMinX / step) * step
            while (xTick <= visibleMaxX) {
                val x = screen(xTick, 0.0).x
                if (x > 40.dp.toPx() && x < size.width - 40.dp.toPx())
                    tick(formatAxisTick(xTick), x, size.height - 4.dp.toPx())
                xTick += step * 2
            }
            var yTick = ceil(visibleMinY / step) * step
            while (yTick <= visibleMaxY) {
                val y = screen(0.0, yTick).y
                if (y > 24.dp.toPx() && y < size.height - 24.dp.toPx())
                    tick(formatAxisTick(yTick), 4.dp.toPx(), y)
                yTick += step * 2
            }
        }
        val yAxisVisible = 0.0 in visibleMinX..visibleMaxX
        val xAxisVisible = 0.0 in visibleMinY..visibleMaxY
        val yAxisX = screen(0.0, centerY).x
        val xAxisY = screen(centerX, 0.0).y
        val yAxisColor = axisY.copy(alpha = .8f)
        val xAxisColor = axisX.copy(alpha = .8f)
        if (yAxisVisible) {
            drawLine(yAxisColor, androidx.compose.ui.geometry.Offset(yAxisX, margin),
                androidx.compose.ui.geometry.Offset(yAxisX, size.height - margin), .75.dp.toPx())
        }
        if (xAxisVisible) {
            drawLine(xAxisColor, androidx.compose.ui.geometry.Offset(margin, xAxisY),
                androidx.compose.ui.geometry.Offset(size.width - margin, xAxisY), .75.dp.toPx())
        }
        if (yAxisVisible || xAxisVisible) drawIntoCanvas { canvas ->
            val axisLabelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 13.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            if (yAxisVisible) {
                axisLabelPaint.color = axisY.toArgb()
                canvas.nativeCanvas.drawText("Y (m)", yAxisX + 4.dp.toPx(), margin + 12.dp.toPx(), axisLabelPaint)
            }
            if (xAxisVisible) {
                axisLabelPaint.color = axisX.toArgb()
                canvas.nativeCanvas.drawText("X (m)", size.width - margin - 40.dp.toPx(), xAxisY - 4.dp.toPx(), axisLabelPaint)
            }
        }
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = ink.toArgb()
                textSize = 13.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val backgroundPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = mapSurface.toArgb()
                style = android.graphics.Paint.Style.FILL
            }
            val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.dp.toPx()
            }
            val labelMetrics = labelPaint.fontMetrics
            val labelHeight = labelMetrics.descent - labelMetrics.ascent
            val labelPaddingX = 5.dp.toPx()
            val labelPaddingY = 3.dp.toPx()
            val labelGap = 4.dp.toPx()
            val placedLabels = mutableListOf<android.graphics.RectF>()
            // Keep marker labels out of the axis-unit labels, including at the origin.
            fun reserveAxisLabel(text: String, left: Float, baseline: Float) {
                val padding = 4.dp.toPx()
                placedLabels += android.graphics.RectF(left - padding, baseline + labelMetrics.ascent - padding,
                    left + labelPaint.measureText(text) + padding, baseline + labelMetrics.descent + padding)
            }
            if (yAxisVisible) reserveAxisLabel("Y (m)", yAxisX + 4.dp.toPx(), margin + 12.dp.toPx())
            if (xAxisVisible) reserveAxisLabel("X (m)", size.width - margin - 40.dp.toPx(), xAxisY - 4.dp.toPx())

            fun drawPointLabel(text: String, center: androidx.compose.ui.geometry.Offset, markerRadius: Float, accent: Int) {
                val width = labelPaint.measureText(text) + labelPaddingX * 2
                val height = labelHeight + labelPaddingY * 2
                val sideOffset = markerRadius + labelGap
                val candidates = mutableListOf<Pair<Float, Float>>()
                // Try the four nearest corners first, then move labels outward in
                // small steps when several devices occupy the same map region.
                for (row in 0..5) {
                    val vertical = row * (height + labelGap)
                    candidates += (center.x + sideOffset) to (center.y - height - labelGap - vertical)
                    candidates += (center.x - sideOffset - width) to (center.y - height - labelGap - vertical)
                    candidates += (center.x + sideOffset) to (center.y + labelGap + vertical)
                    candidates += (center.x - sideOffset - width) to (center.y + labelGap + vertical)
                }
                val bounds = android.graphics.RectF(0f, 0f, size.width, size.height)
                val chosen = candidates.firstOrNull { (left, top) ->
                    val rect = android.graphics.RectF(left, top, left + width, top + height)
                    bounds.contains(rect) && placedLabels.none { android.graphics.RectF.intersects(it, rect) }
                } ?: candidates.firstOrNull { (left, top) ->
                    val clampedLeft = left.coerceIn(2f, max(2f, size.width - width - 2f))
                    val clampedTop = top.coerceIn(2f, max(2f, size.height - height - 2f))
                    val rect = android.graphics.RectF(clampedLeft, clampedTop, clampedLeft + width, clampedTop + height)
                    placedLabels.none { android.graphics.RectF.intersects(it, rect) }
                } ?: (center.x.coerceIn(2f, max(2f, size.width - width - 2f)) to
                    center.y.coerceIn(2f, max(2f, size.height - height - 2f)))
                val left = chosen.first.coerceIn(2f, max(2f, size.width - width - 2f))
                val top = chosen.second.coerceIn(2f, max(2f, size.height - height - 2f))
                val rect = android.graphics.RectF(left, top, left + width, top + height)
                placedLabels += rect

                backgroundPaint.alpha = 242
                borderPaint.color = accent
                nativeCanvas.drawRoundRect(rect, 6.dp.toPx(), 6.dp.toPx(), backgroundPaint)
                nativeCanvas.drawRoundRect(rect, 6.dp.toPx(), 6.dp.toPx(), borderPaint)
                labelPaint.color = ink.toArgb()
                nativeCanvas.drawText(text, left + labelPaddingX, top + labelPaddingY - labelMetrics.ascent, labelPaint)
            }

            positions.forEach { (tag, p) ->
                p.anchors.forEach { id -> anchors.firstOrNull { it.mac == id }?.let { drawLine(cyan.copy(alpha = .28f), screen(it.x, it.y), screen(p.x, p.y), 1.5f) } }
            }
            anchors.forEachIndexed { i, a ->
                val center = screen(a.x, a.y); val color = if (states[a.key]?.online == true) green else muted
                val tri = Path().apply {
                    moveTo(center.x, center.y - 8.dp.toPx())
                    lineTo(center.x - 7.dp.toPx(), center.y + 6.dp.toPx())
                    lineTo(center.x + 7.dp.toPx(), center.y + 6.dp.toPx())
                    close()
                }
                drawPath(tri, color)
                drawPath(tri, mapSurface, style = Stroke(3f))
                drawPath(tri, ink, style = Stroke(1.5f))
                drawPointLabel("${names[a.key] ?: "A${i + 1}"} ${a.x.fmt()}, ${a.y.fmt()}", center, 8.dp.toPx(),
                    if (states[a.key]?.online == true) green.toArgb() else muted.toArgb())
            }
            positions.forEach { (tag, p) ->
                val center = screen(p.x, p.y)
                drawCircle(cyan.copy(alpha = .35f), max(7f, (p.error * scale).toFloat()), center, style = Stroke(2f))
                drawCircle(cyan, 5.dp.toPx(), center)
                drawCircle(mapSurface, 5.dp.toPx(), center, style = Stroke(2.5f))
                drawCircle(ink, 5.dp.toPx(), center, style = Stroke(1f))
                drawCircle(mapSurface, 1.5.dp.toPx(), center)
                drawPointLabel("${names[tag] ?: "T"} ${p.x.fmt()}, ${p.y.fmt()}", center, 5.dp.toPx(), cyan.toArgb())
            }
        }
    }
}

private fun formatAxisTick(value: Double): String = when {
    kotlin.math.abs(value) >= 100 -> String.format(Locale.US, "%.0f", value)
    kotlin.math.abs(value) >= 10 -> String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
    else -> String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}
private fun Double.fmt(): String = String.format(Locale.US, "%.2f", this)
