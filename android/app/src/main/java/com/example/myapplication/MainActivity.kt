package com.example.myapplication

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val Ink = Color(0xFF102A3A)
private val Muted = Color(0xFF547080)
private val Cyan = Color(0xFF007F9F)
private val Green = Color(0xFF087A4A)
private val Amber = Color(0xFFA55B00)
private val Red = Color(0xFFC3344F)
private val Page = Color(0xFFEAF0F3)
private val Grid = Color(0xFFD5E0E5)

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
    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(repository.csv()) }
        }.onFailure { repository.setStatus("Could not save CSV: ${it.message}") }
    }
    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) repository.manualScan()
        else repository.setStatus("Allow local network access to discover and connect to UWB devices")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = DeviceRepository(this) { dashboard.value = it }
        dashboard.value = repository.snapshot()
        requestLocalNetworkIfNeeded()
        setContent {
            val snapshot by remember { dashboard }
            MyApplicationTheme {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(1550)
                    showSplash = false
                }
                Crossfade(targetState = showSplash, animationSpec = tween(350), label = "startup") { splash ->
                    if (splash) SenseSplash() else DashboardApp(snapshot, repository,
                        onSaveCsv = { createCsv.launch("uwb_localization_${System.currentTimeMillis()}.csv") },
                        onScan = { if (requestLocalNetworkIfNeeded()) repository.manualScan() })
                }
            }
        }
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
private fun SenseSplash() {
    val scale = remember { Animatable(0.78f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
            launch { alpha.animateTo(1f, tween(600)) }
        }
    }
    Box(Modifier.fillMaxSize().background(Page), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.sense_logo), contentDescription = "Sense logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(240.dp).height(98.dp).graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                })
            Spacer(Modifier.height(14.dp))
            Text("UWB LOCALIZATION", color = Ink, fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardApp(snapshot: DashboardSnapshot, repo: DeviceRepository, onSaveCsv: () -> Unit, onScan: () -> Unit) {
    var editing by remember { mutableStateOf<DeviceRecord?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var configOpen by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var cirData by remember { mutableStateOf<JSONObject?>(null) }
    var cirError by remember { mutableStateOf("") }
    var cirLoading by remember { mutableStateOf(false) }
    var cirDeviceKey by remember { mutableStateOf("") }
    var selectedKey by remember { mutableStateOf("") }
    var fitMapRequest by remember { mutableStateOf(0) }
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
    val tracking = snapshot.states.values.any { it.online && it.sample.optBoolean("streaming", true) }
    val imuHistory = remember(chosen?.key) { mutableStateListOf<ImuReading>() }
    val imuStamp = sample?.optLong("imu_timestamp_ms", sample.optLong("timestamp_ms", 0)) ?: 0L
    val imuSequence = sample?.optLong("imu_sequence", imuStamp) ?: imuStamp
    fun requestCir(deviceKey: String, ip: String) {
        if (cirLoading) return
        cirLoading = true
        repo.getCir(ip) { data, error ->
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
            .joinToString(":") { current.opt(it).toString() }
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
    LaunchedEffect(chosen?.key, imuSequence) {
        if (sample?.optBoolean("imu_valid") == true) {
            val accel = sample.optJSONArray("accel")
            val gyro = sample.optJSONArray("gyro")
            if (accel != null && accel.length() >= 3 && gyro != null && gyro.length() >= 3 &&
                imuHistory.lastOrNull()?.sequence != imuSequence) {
                imuHistory.add(ImuReading(
                    imuSequence, imuStamp,
                    accel.optDouble(0), accel.optDouble(1), accel.optDouble(2),
                    gyro.optDouble(0), gyro.optDouble(1), gyro.optDouble(2)))
                if (imuHistory.size > 120) imuHistory.removeAt(0)
            }
        }
    }
    Scaffold(containerColor = Page) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.sense_logo), contentDescription = "SE UWB",
                                contentScale = ContentScale.Fit, modifier = Modifier.width(76.dp).height(31.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("UWB LOCALIZATION", color = Ink, fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                Text("${snapshot.states.values.count { it.online }} online · ${snapshot.positions.size} tags",
                                    color = Muted, fontSize = 9.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            Surface(color = Color(0xFFEAF7F8), shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.width(52.dp).height(34.dp).clickable { repo.setStreaming(!tracking) }
                                    .semantics { contentDescription = if (tracking) "Pause device data flow" else "Start device data flow" }) {
                                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center) {
                                    Text(if (tracking) "Ⅱ" else "▶", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(3.dp))
                                    Text(if (tracking) "Pause" else "Start", color = Ink, fontSize = 8.sp, maxLines = 1)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            HeaderAction("⚙", "Devices", Modifier.weight(1f), onClick = { configOpen = true })
                            HeaderAction(if (snapshot.calibrationProgress.isBlank()) "⚑" else "×", "Calibrate", Modifier.weight(1f), onClick = {
                                repo.startOrCancelCalibration()?.let(repo::setStatus)
                            })
                            HeaderAction(if (snapshot.collecting) "■" else "⇩", if (snapshot.collecting) "Stop / CSV" else "CSV",
                                Modifier.weight(1f), onClick = {
                                    if (snapshot.collecting) { repo.setCollecting(false); onSaveCsv() }
                                    else repo.setCollecting(true)
                                })
                        }
                    }
                }
                Text(snapshot.status, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, top = 5.dp))
                if (snapshot.calibrationProgress.isNotBlank()) Text(snapshot.calibrationProgress, color = Amber, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
            }
            item {
                SectionCard("2D LOCALIZATION MAP", headerAction = {
                    TextButton(onClick = { fitMapRequest++ },
                        modifier = Modifier.size(30.dp).semantics { contentDescription = "Fit all anchors and tags" },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("⌂", color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }) {
                    if (snapshot.devices.none { it.role == "anchor" }) {
                        EmptyHint("Add three or more anchors in ⚙ Configuration to start mapping.")
                    } else {
                        LocalizationMap(snapshot.devices, snapshot.positions, names, snapshot.states, fitMapRequest,
                            Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(12.dp)))
                    }
                    snapshot.positions.entries.firstOrNull()?.let { (tag, p) ->
                        Text("${names[tag] ?: "Tag"}   X ${String.format(Locale.US, "%+.2f", p.x)} m   Y ${String.format(Locale.US, "%+.2f", p.y)} m   RMS ${p.error.fmt()} m",
                            color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            item {
                DevicePicker(snapshot.devices, chosen, names, snapshot.states, onSelect = { selectedKey = it })
            }
            item {
                SectionCard("IMU DATA · ${chosen?.let { names[it.key] } ?: "NO DEVICE"}") {
                    when {
                        chosen == null -> EmptyHint("Add or select a device to view IMU telemetry.")
                        sample == null -> EmptyHint("Waiting for telemetry from ${names[chosen.key]}. Check that it is online.")
                        snapshot.states[chosen.key]?.online != true -> EmptyHint("${names[chosen.key]} is offline. Reconnect it to Wi-Fi to receive IMU telemetry.")
                        !sample.optBoolean("imu_valid") -> EmptyHint(
                            if (!sample.optBoolean("imu_enabled", true))
                                "IMU is disabled in this device's firmware. Enable the MPU6050 and flash the device."
                            else "Waiting for a valid MPU6050 sample. Check sensor wiring and the device boot log.")
                        else -> {
                            MetricRow("ACCEL · g", vector(sample, "accel"), "GYRO · °/s", vector(sample, "gyro"))
                            ImuChart("ACCELEROMETER · g", imuHistory, acceleration = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                            ImuChart("GYROSCOPE · °/s", imuHistory, acceleration = false,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }
            }
            item {
                SectionCard("CIR DATA MAP") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(chosen?.let { "${names[it.key] ?: "Device"} ? ${it.mac.ifBlank { it.ip }}" } ?: "Select a device",
                            color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (chosen == null || chosenIp.isBlank()) cirError = "This device has no IP address"
                            else {
                                cirError = "Loading CIR..."
                                cirDeviceKey = chosen.key
                                requestCir(chosen.key, chosenIp)
                            }
                        }) { Text("Refresh") }
                    }
                    val activeCir = cirData.takeIf { cirDeviceKey == chosen?.key }
                    if (activeCir?.optBoolean("valid") == true) {
                        CirPlot(activeCir, cirHistory, Modifier.fillMaxWidth().height(260.dp))
                        Text("${activeCir.optInt("sample_count")} samples ? seq ${activeCir.optLong("sequence")} ? age ${activeCir.optLong("age_ms")} ms",
                            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    } else EmptyHint(cirError.ifBlank {
                        when {
                            activeCir?.optBoolean("enabled") == false -> "CIR is disabled in this device's firmware."
                            activeCir != null -> "CIR is enabled; waiting for a UWB packet from another device."
                            else -> "Waiting for automatic CIR updates from the selected device."
                        }
                    })
                }
            }
            item { Text("${snapshot.devices.size} configured devices · ${snapshot.discovered.size} discovered", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 16.dp)) }
        }
    }
    if (configOpen) ConfigurationDialog(snapshot, names, repo,
        onScan = onScan,
        onAdd = { editing = null; configOpen = false; editorOpen = true },
        onEdit = { editing = it; configOpen = false; editorOpen = true },
        onClear = { clearConfirm = true },
        onDismiss = { configOpen = false })
    if (editorOpen) DeviceEditorDialog(editing, onDismiss = { editorOpen = false; configOpen = true }, onSave = { record ->
        val error = repo.upsert(record, editing?.key)
        if (error == null) { editorOpen = false; configOpen = true } else repo.setStatus(error)
    }, onApplyRole = { record ->
        val error = repo.upsert(record, editing?.key)
        if (error == null) {
            if (record.ip.isNotBlank()) repo.postControl(record.ip, JSONObject().put("role", record.role))
            editorOpen = false; configOpen = true
        } else repo.setStatus(error)
    })
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("Clear configured devices?") },
        text = { Text("This removes the saved device map and localization configuration.") },
        confirmButton = { TextButton(onClick = { repo.clear(); clearConfirm = false }) { Text("Clear", color = Red) } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Cancel") } })
}

@Composable
private fun HeaderAction(icon: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.height(44.dp).semantics { contentDescription = label },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(icon, color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Ink, fontSize = 9.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DevicePicker(devices: List<DeviceRecord>, chosen: DeviceRecord?, names: Map<String, String>,
                         states: Map<String, DeviceState>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard("DEVICE SELECTION") {
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (chosen?.let { states[it.key]?.online } == true) Green else Muted))
                    Spacer(Modifier.width(8.dp))
                    Text(chosen?.let { "${names[it.key] ?: "Device"} · ${it.mac.ifBlank { it.ip }}" } ?: "Choose a device",
                        color = Ink, modifier = Modifier.weight(1f))
                    Text("⌄", color = Cyan, fontSize = 18.sp)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(.88f)) {
                devices.forEach { device ->
                    DropdownMenuItem(text = {
                        Column {
                            Text("${names[device.key] ?: "Device"} · ${device.role.uppercase()}", color = Ink)
                            Text(device.mac.ifBlank { device.ip }, color = Muted, fontSize = 11.sp)
                        }
                    }, onClick = { onSelect(device.key); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun ConfigurationDialog(snapshot: DashboardSnapshot, names: Map<String, String>, repo: DeviceRepository,
                                onScan: () -> Unit, onAdd: () -> Unit, onEdit: (DeviceRecord) -> Unit,
                                onClear: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxSize(.88f), shape = RoundedCornerShape(22.dp), color = Page) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DEVICE CONFIGURATION", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        Text("Manage your localization network", color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text("Add devices, assign roles, and set measured X/Y/Z map coordinates.", color = Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Scan network", maxLines = 1) }
                    Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Text("+  Add device", maxLines = 1) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Cyan))
                    Text(repo.snapshot().status, color = Muted, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    TextButton(onClick = onClear) { Text("Clear all", color = Red, fontSize = 11.sp) }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (snapshot.devices.isNotEmpty()) item {
                        Text("SAVED DEVICES", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                    }
                    items(snapshot.devices, key = { it.key }) { device ->
                        ConfigurationDeviceRow(device, names[device.key] ?: "Device", snapshot.states[device.key],
                            onEdit = { onEdit(device) }, onDelete = { repo.remove(device.key) },
                            onApplyRole = {
                                if (device.ip.isBlank()) repo.setStatus("Scan or add an IP before applying the role")
                                else repo.postControl(device.ip, JSONObject().put("role", device.role))
                            })
                    }
                    val available = snapshot.discovered.filter { found ->
                        snapshot.devices.none { device ->
                            if (found.mac.isNotBlank() && device.mac.isNotBlank())
                                found.mac.equals(device.mac, ignoreCase = true)
                            else device.ip == found.ip
                        }
                    }
                    if (available.isNotEmpty()) item {
                        Text("DISCOVERED ON NETWORK", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    items(available, key = { "found-${it.mac}" }) { found ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(found.mac, color = Ink, fontWeight = FontWeight.SemiBold)
                                    Text("${found.ip} · ${found.role.uppercase()}", color = Muted, fontSize = 11.sp)
                                }
                                Surface(color = Color(0xFFE8F5EE), shape = RoundedCornerShape(20.dp)) {
                                    Text("ACTIVE", color = Green, fontWeight = FontWeight.Bold, fontSize = 9.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
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

@Composable
private fun ConfigurationDeviceRow(device: DeviceRecord, name: String, state: DeviceState?, onEdit: () -> Unit,
                                   onDelete: () -> Unit, onApplyRole: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(device.mac.ifBlank { device.ip }, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(color = Color(0xFFEAF7F8), shape = RoundedCornerShape(20.dp)) {
                    Text(device.role.uppercase(), color = Cyan, fontWeight = FontWeight.Bold, fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
                TextButton(onClick = onEdit) { Text("Edit", fontSize = 11.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("X ${device.x.fmt()}   Y ${device.y.fmt()}   Z ${device.z.fmt()} m", color = Muted, fontSize = 10.sp,
                    maxLines = 1, modifier = Modifier.weight(1f))
                val active = state?.online == true
                Surface(color = if (active) Color(0xFFE8F5EE) else Color(0xFFF1F3F4), shape = RoundedCornerShape(20.dp)) {
                    Text(if (active) "ACTIVE" else "OFFLINE", color = if (active) Green else Muted,
                        fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
                TextButton(onClick = onApplyRole) { Text("Apply role", fontSize = 11.sp) }
                TextButton(onClick = onDelete) { Text("Remove", color = Red, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
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
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF05080C))) {
        val left = 40.dp.toPx()
        val right = size.width - 9.dp.toPx()
        val top = 22.dp.toPx()
        val traceBottom = size.height * .36f
        val heatTop = traceBottom + 34.dp.toPx()
        val heatBottom = size.height - 27.dp.toPx()
        drawRect(Color(0xFF05080C))

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(213, 231, 239)
            textSize = 9.sp.toPx()
            isAntiAlias = true
        }
        drawIntoCanvas { it.nativeCanvas.drawText("CIR MAGNITUDE  ·  LIVE", left, 13.dp.toPx(), textPaint) }

        val amplitudeMax = max(iq.maxOrNull() ?: 1.0, 1.0)
        repeat(4) { tick ->
            val fraction = tick / 3f
            val y = traceBottom - fraction * (traceBottom - top)
            drawLine(Color(0xFF27313A), androidx.compose.ui.geometry.Offset(left, y),
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
            drawPath(area, Color(0xFF10313B))
            drawPath(trace, Color(0xFF55D7E8), style = Stroke(1.5.dp.toPx()))
        }
        if (firstPathVisible) {
            val x = left + ((firstPath - windowStart) / windowSpan).toFloat() * (right - left)
            drawLine(Color(0xFFF4B84A), androidx.compose.ui.geometry.Offset(x, top),
                androidx.compose.ui.geometry.Offset(x, traceBottom), 1.dp.toPx())
        }

        drawIntoCanvas {
            textPaint.color = android.graphics.Color.rgb(213, 231, 239)
            it.nativeCanvas.drawText("CIR WATERFALL  ·  OLDEST → NEWEST  ·  ${history.size}",
                left, heatTop - 7.dp.toPx(), textPaint)
        }
        drawRect(Color(0xFF041A3B), androidx.compose.ui.geometry.Offset(left, heatTop),
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
            drawLine(Color(0xFFF4B84A), androidx.compose.ui.geometry.Offset(x, heatTop),
                androidx.compose.ui.geometry.Offset(x, heatBottom), 1.dp.toPx())
        }
        drawIntoCanvas { canvas ->
            textPaint.textSize = 8.sp.toPx()
            textPaint.color = android.graphics.Color.rgb(181, 195, 204)
            listOf(0f, .5f, 1f).forEach { fraction ->
                val x = left + fraction * (right - left)
                val label = "${(windowStart + fraction * windowSpan).toInt()}"
                canvas.nativeCanvas.drawText(label, x - textPaint.measureText(label) / 2,
                    size.height - 8.dp.toPx(), textPaint)
            }
            if (firstPathVisible) {
                textPaint.color = android.graphics.Color.rgb(244, 184, 74)
                val label = "FP ${"%.1f".format(Locale.US, firstPath)}"
                canvas.nativeCanvas.drawText(label, left + 3.dp.toPx(), top + 10.dp.toPx(), textPaint)
            }
        }
    }
}

@Composable
private fun ImuChart(title: String, history: List<ImuReading>, acceleration: Boolean,
                     modifier: Modifier = Modifier) {
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
            Text(title, color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                modifier = Modifier.weight(1f))
            listOf("X", "Y", "Z").forEachIndexed { i, axis ->
                Text(axis, color = listOf(Red, Green, Cyan)[i], fontWeight = FontWeight.Bold,
                    fontSize = 9.sp, modifier = Modifier.padding(start = 7.dp))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(96.dp).padding(top = 3.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF8FAFB))) {
            val left = 36.dp.toPx()
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
                drawLine(Grid, androidx.compose.ui.geometry.Offset(left, y),
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
                    drawCircle(listOf(Red, Green, Cyan)[axis], 2.5f,
                        androidx.compose.ui.geometry.Offset(left, y))
                } else if (values.size > 1) drawPath(path, listOf(Red, Green, Cyan)[axis], style = Stroke(2f))
            }
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = Muted.toArgb()
                    textSize = 8.sp.toPx()
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
                .joinToString("    "), color = Ink, fontSize = 9.sp,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, headerAction: (@Composable () -> Unit)? = null,
                        content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = .7.sp, modifier = Modifier.weight(1f))
                headerAction?.invoke()
            }
            Spacer(Modifier.height(9.dp))
            content()
        }
    }
}

@Composable
private fun DeviceEditorDialog(device: DeviceRecord?, onDismiss: () -> Unit,
                               onSave: (DeviceRecord) -> Unit, onApplyRole: (DeviceRecord) -> Unit) {
    var mac by remember(device) { mutableStateOf(device?.mac.orEmpty()) }
    var ip by remember(device) { mutableStateOf(device?.ip.orEmpty()) }
    var role by remember(device) { mutableStateOf(device?.role ?: "anchor") }
    var x by remember(device) { mutableStateOf(device?.x?.toString() ?: "0") }
    var y by remember(device) { mutableStateOf(device?.y?.toString() ?: "0") }
    var z by remember(device) { mutableStateOf(device?.z?.toString() ?: "0") }
    var yaw by remember(device) { mutableStateOf(device?.yawOffset?.toString() ?: "0") }
    var error by remember { mutableStateOf("") }
    fun record(): DeviceRecord? {
        val values = listOf(x.toDoubleOrNull(), y.toDoubleOrNull(), z.toDoubleOrNull(), yaw.toDoubleOrNull())
        if (values.any { it == null }) { error = "Enter valid numeric coordinates"; return null }
        return DeviceRecord(mac, ip, role, values[0]!!, values[1]!!, values[2]!!, values[3]!!)
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (device == null) "Add UWB device" else "Edit UWB device") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedTextField(mac, { mac = it }, label = { Text("MAC address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ip, { ip = it }, label = { Text("IPv4 address") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(role == "anchor", { role = "anchor" }); Text("Anchor")
                    Spacer(Modifier.width(10.dp)); RadioButton(role == "tag", { role = "tag" }); Text("Tag")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumericField("X (m)", x, { x = it }, Modifier.weight(1f))
                    NumericField("Y (m)", y, { y = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumericField("Z (m)", z, { z = it }, Modifier.weight(1f))
                    NumericField("Yaw offset°", yaw, { yaw = it }, Modifier.weight(1f))
                }
                if (error.isNotBlank()) Text(error, color = Red, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { record()?.let(onSave) }) { Text("Save") } },
        dismissButton = { Row {
            TextButton(onClick = { record()?.let(onApplyRole) }) { Text("Save + apply role") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        } })
}

@Composable
private fun NumericField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 11.sp) }, modifier = modifier,
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun MetricRow(label1: String, value1: String, label2: String, value2: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column { Text(label1, color = Muted, fontSize = 11.sp); Text(value1, color = Ink, fontSize = 12.sp) }
        Column { Text(label2, color = Muted, fontSize = 11.sp); Text(value2, color = Ink, fontSize = 12.sp) }
    }
}

@Composable
private fun LocalizationMap(devices: List<DeviceRecord>, positions: Map<String, Position>, names: Map<String, String>,
                            states: Map<String, DeviceState>, fitRequest: Int, modifier: Modifier = Modifier) {
    val anchors = devices.filter { it.role == "anchor" }
    val points = anchors.map { it.x to it.y } + positions.values.map { it.x to it.y }
    if (points.isEmpty()) return
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(fitRequest) {
        zoom = 1f
        pan = androidx.compose.ui.geometry.Offset.Zero
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
    Canvas(modifier.background(Color(0xFFF9FBFC)).then(gestures)) {
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
            drawLine(Color(0xFFCBD9DF), androidx.compose.ui.geometry.Offset(x, 0f),
                androidx.compose.ui.geometry.Offset(x, size.height), 1f)
            gx += step
        }
        val visibleMinY = minY + (pan.y.toDouble() - margin.toDouble()) / scalePerUnit
        val visibleMaxY = minY + (size.height.toDouble() - margin.toDouble() + pan.y.toDouble()) / scalePerUnit
        var gy = ceil(visibleMinY / step) * step
        while (gy <= visibleMaxY) {
            val y = screen(0.0, gy).y
            drawLine(Color(0xFFCBD9DF), androidx.compose.ui.geometry.Offset(0f, y),
                androidx.compose.ui.geometry.Offset(size.width, y), 1f)
            gy += step
        }
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(7, 24, 34)
                textSize = 10.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val backgroundPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
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
                labelPaint.color = android.graphics.Color.rgb(7, 24, 34)
                nativeCanvas.drawText(text, left + labelPaddingX, top + labelPaddingY - labelMetrics.ascent, labelPaint)
            }

            positions.forEach { (tag, p) ->
                p.anchors.forEach { id -> anchors.firstOrNull { it.mac == id }?.let { drawLine(Color(0xFFACD9DF), screen(it.x, it.y), screen(p.x, p.y), 1.5f) } }
            }
            anchors.forEachIndexed { i, a ->
                val center = screen(a.x, a.y); val color = if (states[a.key]?.online == true) Green else Muted
                val tri = Path().apply {
                    moveTo(center.x, center.y - 20)
                    lineTo(center.x - 18, center.y + 16)
                    lineTo(center.x + 18, center.y + 16)
                    close()
                }
                drawPath(tri, color)
                drawPath(tri, Color.White, style = Stroke(3f))
                drawPath(tri, Ink, style = Stroke(1.5f))
                drawPointLabel("${names[a.key] ?: "A${i + 1}"} ${a.x.fmt()}, ${a.y.fmt()}", center, 20f,
                    if (states[a.key]?.online == true) android.graphics.Color.rgb(19, 139, 91) else android.graphics.Color.rgb(95, 117, 128))
            }
            positions.forEach { (tag, p) ->
                val center = screen(p.x, p.y)
                drawCircle(Color(0x558CD0DB), max(7f, (p.error * scale).toFloat()), center, style = Stroke(2f))
                drawCircle(Cyan, 11f, center)
                drawCircle(Color.White, 11f, center, style = Stroke(2.5f))
                drawCircle(Ink, 11f, center, style = Stroke(1f))
                drawCircle(Color.White, 3f, center)
                drawPointLabel("${names[tag] ?: "T"} ${p.x.fmt()}, ${p.y.fmt()}", center, 11f, android.graphics.Color.rgb(0, 127, 159))
            }
        }
    }
}

private fun vector(sample: JSONObject, field: String): String {
    val values = sample.optJSONArray(field) ?: return "—"
    return (0 until values.length()).joinToString("  ") { values.optDouble(it).fmt() }
}
private fun formatAxisTick(value: Double): String = when {
    kotlin.math.abs(value) >= 100 -> String.format(Locale.US, "%.0f", value)
    kotlin.math.abs(value) >= 10 -> String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
    else -> String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}
private fun Double.fmt(): String = String.format(Locale.US, "%.2f", this)
