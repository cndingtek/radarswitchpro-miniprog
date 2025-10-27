package com.dingtek.radarlinkpro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0c1f37")
        window.navigationBarColor = android.graphics.Color.parseColor("#0c1f37")
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            RadarLinkTheme {
                Surface(Modifier.fillMaxSize()) { RadarLinkApp() }
            }
        }
    }
}

sealed class TopTab(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String) {
    object Scan : TopTab(Icons.Filled.Bluetooth, "蓝牙")
    object Devices : TopTab(Icons.Filled.List, "设备")
    object Params : TopTab(Icons.Filled.GraphicEq, "参数")
    object Settings : TopTab(Icons.Filled.Settings, "设置")
}

data class Device(val name: String, val mac: String, val rssi: Int)

data class PairedDevice(val name: String, val mac: String, val online: Boolean, val lastConnectedMs: Long)

data class RssiPoint(val t: Long, val rssi: Int)

data class DistancePoint(val t: Long, val meters: Float?)

fun tr(lang: String, zh: String, en: String): String = if (lang == "en") en else zh

@Composable
fun RadarLinkApp() {
    var tab: TopTab by remember { mutableStateOf<TopTab>(TopTab.Scan) }
    val pairedDevices = remember { mutableStateListOf<PairedDevice>() }
    var selectedPairedIndex by remember { mutableStateOf(0) }
    val rssiSeries = remember { mutableStateListOf<RssiPoint>() }
    val distanceSeries = remember { mutableStateListOf<DistancePoint>() }
    val eventLogs = remember { mutableStateListOf<String>() }
    val rawBleLines = remember { mutableStateListOf<String>() }
    var currentGatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE) }
    val bluetoothManager = remember { context.getSystemService(BluetoothManager::class.java) }
    val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    val autoConnections = remember { mutableStateMapOf<String, BluetoothGatt?>() }
    var appLang by remember { mutableStateOf(sp.getString("language", "zh") ?: "zh") }

    fun loadPairedFromPrefs() {
        try {
            val sp = context.getSharedPreferences("radarlink", Context.MODE_PRIVATE)
            val json = sp.getString("paired_devices_json", "[]") ?: "[]"
            val arr = org.json.JSONArray(json)
            pairedDevices.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val mac = o.optString("mac")
                val last = o.optLong("last", 0L)
                if (mac.isNotEmpty()) pairedDevices.add(PairedDevice(name, mac, online = false, lastConnectedMs = last))
            }
        } catch (_: Exception) {}
    }
    fun savePairedToPrefs() {
        try {
            val sp = context.getSharedPreferences("radarlink", Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            pairedDevices.forEach { pd ->
                val o = org.json.JSONObject()
                o.put("name", pd.name)
                o.put("mac", pd.mac)
                o.put("last", pd.lastConnectedMs)
                arr.put(o)
            }
            sp.edit().putString("paired_devices_json", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    LaunchedEffect(Unit) { loadPairedFromPrefs() }

    // 后台自动重连：在任意页面都尝试连接已配对设备（无需手动扫描/点击）
    LaunchedEffect(pairedDevices.map { it.mac }) {
        if (!hasBlePermissions(context)) return@LaunchedEffect
        pairedDevices.forEach { pd ->
            if (!autoConnections.containsKey(pd.mac)) {
                val dev: BluetoothDevice? = try { adapter?.getRemoteDevice(pd.mac) } catch (_: Exception) { null }
                val gatt = dev?.connectGatt(context, /*autoConnect*/ true, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            currentGatt = gatt
                            try { gatt.discoverServices() } catch (_: Exception) {}
                            val idx = pairedDevices.indexOfFirst { it.mac == gatt.device.address }
                            if (idx >= 0) {
                                val p0 = pairedDevices[idx]
                                pairedDevices[idx] = p0.copy(online = true, lastConnectedMs = System.currentTimeMillis())
                                savePairedToPrefs()
                            }
                        }
                        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            if (currentGatt?.device?.address == gatt.device.address) currentGatt = null
                            val idx = pairedDevices.indexOfFirst { it.mac == gatt.device.address }
                            if (idx >= 0) {
                                val p0 = pairedDevices[idx]
                                pairedDevices[idx] = p0.copy(online = false, lastConnectedMs = System.currentTimeMillis())
                                savePairedToPrefs()
                            }
                            autoConnections.remove(gatt.device.address)
                            try { gatt.close() } catch (_: Exception) {}
                        }
                    }
                })
                if (gatt != null) autoConnections[pd.mac] = gatt
            }
        }
    }

    LaunchedEffect(currentGatt) {
        rssiSeries.clear()
        if (currentGatt != null) {
            while (currentGatt != null) {
                try { currentGatt?.readRemoteRssi() } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0f2745), Color(0xFF0c1f37))))
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            AppHeader(lang = appLang)
            TopNav(tab = tab, onChange = { tab = it })
            Spacer(Modifier.height(12.dp))
            when (tab) {
                is TopTab.Scan -> ScanScreen(
                    lang = appLang,
                    autoConnectMacs = pairedDevices.map { it.mac },
                    onPaired = { pd ->
                        val idx = pairedDevices.indexOfFirst { it.mac == pd.mac }
                        if (idx < 0) pairedDevices.add(pd) else pairedDevices[idx] = pd
                        savePairedToPrefs()
                    },
                    onGattChanged = { g -> currentGatt = g },
                    onRssi = { v -> rssiSeries.add(RssiPoint(System.currentTimeMillis(), v)) },
                    onLogEvent = { msg ->
                        eventLogs.add(0, msg)
                        val limit = try { sp.getInt("log_limit", 5) } catch (_: Exception) { 5 }
                        while (eventLogs.size > limit) eventLogs.removeLast()
                    },
                    onRawBle = { raw ->
                        val t = raw.trim()
                        if (t.isNotEmpty()) {
                            // 避免同一瞬间重复的原始行（例如设备多个特征或重复通知）
                            val last = rawBleLines.firstOrNull()?.trim()
                            val isDup = last != null && last.equals(t, ignoreCase = true)
                            if (!isDup) rawBleLines.add(0, t)
                            val limit = try { sp.getInt("log_limit", 5) } catch (_: Exception) { 5 }
                            while (rawBleLines.size > limit) rawBleLines.removeLast()
                        }
                    },
                    onDistance = { m ->
                        val now = System.currentTimeMillis()
                        distanceSeries.add(DistancePoint(now, m))
                        // 按设置的时间窗口保留数据，避免过度裁剪
                        val winSec = try { sp.getInt("rssi_window", 180) } catch (_: Exception) { 180 }
                        val cutoff = now - winSec * 1000L
                        while (distanceSeries.isNotEmpty() && distanceSeries.first().t < cutoff) {
                            distanceSeries.removeAt(0)
                        }
                        // 轻度裁剪，避免无限增长
                        if (distanceSeries.size > 2000) {
                            repeat(distanceSeries.size - 2000) { distanceSeries.removeAt(0) }
                        }
                    }
                )
                is TopTab.Devices -> DevicesScreen(lang = appLang, paired = pairedDevices,
                    onOpen = { pd ->
                        val idx = pairedDevices.indexOfFirst { it.mac == pd.mac }
                        if (idx >= 0) { selectedPairedIndex = idx; tab = TopTab.Params }
                    }
                ) { pd ->
                    // 解除配对并移除
                    val bm = context.getSystemService(BluetoothManager::class.java)
                    val adapter = bm?.adapter
                    val dev = try { adapter?.getRemoteDevice(pd.mac) } catch (_: Exception) { null }
                    try { val m = dev?.javaClass?.getMethod("removeBond"); m?.invoke(dev) } catch (_: Exception) {}
                    // 若存在自动连接，先断开并移除
                    try { autoConnections[pd.mac]?.close() } catch (_: Exception) {}
                    autoConnections.remove(pd.mac)
                    val idx = pairedDevices.indexOfFirst { it.mac == pd.mac }
                    if (idx >= 0) { pairedDevices.removeAt(idx); savePairedToPrefs() }
                }
                is TopTab.Params -> ParamsScreen(
                    lang = appLang,
                    pairedDevices = pairedDevices,
                    selectedIndex = selectedPairedIndex,
                    onSelectedChange = { selectedPairedIndex = it },
                    distanceSeries = distanceSeries,
                    eventLogs = eventLogs,
                    rawBle = rawBleLines
                )
                is TopTab.Settings -> SettingsScreen(lang = appLang, onLanguageChanged = { code -> appLang = code; try { sp.edit().putString("language", code).apply() } catch (_: Exception) {} })
            }
        }
    }
}

@Composable
fun AppHeader(lang: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0f2745)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2d7bf3)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color.White) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("RadarLink Pro", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(tr(lang, "雷达开关智能控制器", "Radar Switch Smart Controller"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun TopNav(tab: TopTab, onChange: (TopTab) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(28.dp), clip = false),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC142a49)),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(TopTab.Scan, TopTab.Devices, TopTab.Params, TopTab.Settings).forEach { t ->
                val selected = tab::class == t::class
                val bgBrush = if (selected) Brush.verticalGradient(listOf(Color(0xFF1F3A65), Color(0xFF172D4F))) else null
                val tint = if (selected) Color(0xFFDFE8F5) else Color(0xFF9BB3D6)
                val itemMod = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable { onChange(t) }.padding(vertical = 12.dp)
                Box(modifier = if (bgBrush != null) itemMod.background(bgBrush) else itemMod, contentAlignment = Alignment.Center) { Icon(t.icon, contentDescription = t.label, tint = tint) }
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF142a49)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (trailing != null) trailing()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun DeviceList(
    lang: String,
    list: List<Device>,
    connectedMac: String?,
    onConnect: (Device) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = rememberLazyListState()
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        items(list) { d ->
            val connected = connectedMac == d.mac
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0f2340)), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(d.name, color = Color.White); Text("${d.mac} • RSSI ${d.rssi}", color = Color(0xFF9bb3d6), fontSize = 12.sp) }
                    if (connected) {
                        val chipColors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = MaterialTheme.colorScheme.primary,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text(tr(lang, "已连接", "Connected")) },
                            colors = chipColors,
                            shape = RoundedCornerShape(18.dp)
                        )
                    } else {
                        OutlinedButton(
                            onClick = { onConnect(d) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) { Text(tr(lang, "连接", "Connect")) }
                    }
                }
            }
        }
    }
}

@Composable
fun DevicesScreen(lang: String, paired: List<PairedDevice>, onOpen: (PairedDevice) -> Unit, onRemove: (PairedDevice) -> Unit) {
    SectionCard(title = tr(lang, "我的设备", "My Devices")) {
        if (paired.isEmpty()) {
            Text(tr(lang, "暂无已配对设备", "No paired devices"), color = Color(0xFF9bb3d6))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                paired.forEach { p ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0f2340)), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp).clickable { onOpen(p) }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(p.name, color = Color.White)
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (p.online) Color(0xFF2DBE60) else Color(0xFF7A889E)).padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(if (p.online) tr(lang, "在线", "Online") else tr(lang, "离线", "Offline"), color = Color.White, fontSize = 12.sp)
                                    }
                                }
                                val timeStr = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(p.lastConnectedMs))
                                Text("${p.mac} • " + tr(lang, "最近连接 ${timeStr}", "Last ${timeStr}"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
                            }
                            IconButton(onClick = { onRemove(p) }) { Icon(Icons.Default.Delete, contentDescription = tr(lang, "移除", "Remove"), tint = Color(0xFF9bb3d6)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(lang: String, onLanguageChanged: (String) -> Unit) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE) }
    var autoReconnect by remember { mutableStateOf(sp.getBoolean("auto_reconnect", true)) }
    var rssiWindow by remember { mutableStateOf(sp.getInt("rssi_window", 180)) }
    var logLimit by remember { mutableStateOf(sp.getInt("log_limit", 5)) }
    var language by remember { mutableStateOf(sp.getString("language", "zh") ?: "zh") }
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = Color.Transparent,
        labelColor = MaterialTheme.colorScheme.primary,
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
    )
    var fwSel by remember { mutableStateOf(0) }
    fun saveSettings() {
        try {
            sp.edit()
                .putBoolean("auto_reconnect", autoReconnect)
                .putInt("rssi_window", rssiWindow)
                .putInt("log_limit", logLimit)
                .apply()
        } catch (_: Exception) {}
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    SectionCard(title = tr(lang, "应用设置", "App Settings")) {
        // 语言切换
        Text(tr(lang, "语言 / Language", "Language"), color = Color.White)
        Spacer(Modifier.height(6.dp))
        val langs = listOf("简体中文" to "zh", "English" to "en")
        var langExpanded by remember { mutableStateOf(false) }
        val langLabel = langs.firstOrNull { it.second == language }?.first ?: "简体中文"
        ExposedDropdownMenuBox(expanded = langExpanded, onExpandedChange = { langExpanded = it }) {
            TextField(
                readOnly = true,
                value = langLabel,
                onValueChange = {},
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                colors = TextFieldDefaults.textFieldColors(containerColor = Color(0xFF0f2340))
            )
            ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                langs.forEach { (label, code) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        language = code
                        try { sp.edit().putString("language", language).apply() } catch (_: Exception) {}
                        onLanguageChanged(code)
                        langExpanded = false
                    })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // 移除“日志显示”右侧的开关，避免不必要的控制项
        Spacer(Modifier.height(8.dp))
        Text(tr(lang, "图表时间窗口", "Chart Time Window"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60, 180, 300).forEach { s ->
                val sel = rssiWindow == s
                FilterChip(
                    selected = sel,
                    onClick = { rssiWindow = s; saveSettings() },
                    label = { Text("${s}s") },
                    colors = chipColors,
                    shape = RoundedCornerShape(20.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(tr(lang, "日志条数上限", "Log Count Limit"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 20).forEach { n ->
                val sel = logLimit == n
                FilterChip(
                    selected = sel,
                    onClick = { logLimit = n; saveSettings() },
                    label = { Text("$n") },
                    colors = chipColors,
                    shape = RoundedCornerShape(20.dp)
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionCard(title = tr(lang, "固件升级", "Firmware Update")) {
        Text(tr(lang, "当前设备固件版本：读取后显示", "Current device firmware: read to display"), color = Color.White)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sel0 = fwSel == 0
            val sel1 = fwSel == 1
            FilterChip(
                selected = sel0,
                onClick = { fwSel = 0; /* TODO: 接入在线升级服务 */ },
                label = { Text(tr(lang, "检查更新", "Check Update")) },
                colors = chipColors,
                shape = RoundedCornerShape(20.dp)
            )
            FilterChip(
                selected = sel1,
                onClick = { fwSel = 1; /* TODO: 本地升级 */ },
                label = { Text(tr(lang, "从文件安装", "Install from file")) },
                colors = chipColors,
                shape = RoundedCornerShape(20.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(tr(lang, "提示：升级功能暂未接入，后续版本提供。", "Note: Update feature is not integrated yet."), color = Color(0xFF9bb3d6), fontSize = 12.sp)
    }

    Spacer(Modifier.height(12.dp))
    SectionCard(title = tr(lang, "关于", "About")) {
        val pm = context.packageManager
        val pkg = try { pm.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        val ver = pkg?.versionName ?: "未知版本"
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("RadarLink Pro", color = Color.White)
            Text(tr(lang, "版本：$ver", "Version: $ver"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
            val devZh = "深圳鼎恒泰物联科技有限公司"
            val devEn = "Shenzhen Dingtek IoT Technology Corp.,Ltd."
            if (lang == "en") {
                Text("Developer: $devEn", color = Color(0xFF9bb3d6), fontSize = 12.sp)
            } else {
                Text("开发者：$devZh", color = Color(0xFF9bb3d6), fontSize = 12.sp)
            }
        }
    }
    }
}

fun hasBlePermissions(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun ScanScreen(
    lang: String,
    autoConnectMacs: List<String>,
    onPaired: (PairedDevice) -> Unit,
    onGattChanged: (BluetoothGatt?) -> Unit,
    onRssi: (Int) -> Unit,
    onLogEvent: (String) -> Unit,
    onRawBle: (String) -> Unit,
    onDistance: (Float?) -> Unit
) {
    val context = LocalContext.current
    val bluetoothManager = remember { context.getSystemService(BluetoothManager::class.java) }
    val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner

    var scanning by remember { mutableStateOf(false) }
    val devices = remember { mutableStateListOf<Device>() }
    var connectedMac by remember { mutableStateOf<String?>(null) }
    var permissionError by remember { mutableStateOf<String?>(null) }

    var incomingBuf by remember { mutableStateOf("") }
    var lastRangeMm by remember { mutableStateOf<Int?>(null) }
    var pendingOn by remember { mutableStateOf(false) }
    var loggedOff by remember { mutableStateOf(false) }
    var loggedOn by remember { mutableStateOf(false) }

    fun handleLine(line: String) {
        val t = line.trim(); if (t.isEmpty()) return
        if (t.equals("OFF", true)) {
            pendingOn = false
            if (!loggedOff) { onLogEvent(tr(lang, "未检测到运动", "No motion detected")); loggedOff = true; loggedOn = false }
            onDistance(null)
            return
        }
        if (t.equals("ON", true)) {
            val mm = lastRangeMm
            if (!loggedOn) {
                if (mm != null) { val m = mm / 1000f; onLogEvent(tr(lang, "检测到运动，距离 ${String.format("%.2f", m)} 米。", "Motion detected, distance ${String.format("%.2f", m)} m.")); loggedOn = true; loggedOff = false; pendingOn = false }
                else pendingOn = true
            }
            return
        }
        val rangePrefix = "Range "
        if (t.startsWith(rangePrefix)) {
            val numStr = t.removePrefix(rangePrefix).trim().takeWhile { it.isDigit() }
            val mmParsed = numStr.toIntOrNull()
            if (mmParsed != null) {
                val previous = lastRangeMm
                lastRangeMm = mmParsed
                val m = mmParsed / 1000f
                onDistance(m)
                if (pendingOn && !loggedOn) { onLogEvent(tr(lang, "检测到运动，距离 ${String.format("%.2f", m)} 米。", "Motion detected, distance ${String.format("%.2f", m)} m.")); loggedOn = true; loggedOff = false; pendingOn = false }
                // 若一直处于运动状态，距离变化也记录日志
                if (loggedOn && previous != null && previous != mmParsed) {
                    onLogEvent(tr(lang, "目标距离变化至 ${String.format("%.2f", m)} 米。", "Target distance changed to ${String.format("%.2f", m)} m."))
                }
            }
        }
    }

    val permissions = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) }
    var scanCb by remember { mutableStateOf<ScanCallback?>(null) }

    fun connectToDevice(nameGuess: String, mac: String) {
        val dev: BluetoothDevice? = adapter?.getRemoteDevice(mac)
        dev?.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedMac = gatt.device.address
                    onGattChanged(gatt)
                    try { gatt.discoverServices() } catch (_: Exception) {}
                    onPaired(PairedDevice(nameGuess, mac, online = true, lastConnectedMs = System.currentTimeMillis()))
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (connectedMac == gatt.device.address) connectedMac = null
                    onGattChanged(null)
                    onPaired(PairedDevice(nameGuess, mac, online = false, lastConnectedMs = System.currentTimeMillis()))
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    try {
                        gatt.services?.forEach { svc ->
                            svc.characteristics?.forEach { ch ->
                                val props = ch.properties
                                val notify = (props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                                val indicate = (props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                                if (notify || indicate) {
                                    try {
                                        gatt.setCharacteristicNotification(ch, true)
                                        val ccc = ch.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                        if (ccc != null) {
                                            ccc.value = if (notify) android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                            gatt.writeDescriptor(ccc)
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) { onRssi(rssi) }
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic) {
                val chunk = try { String(characteristic.value ?: ByteArray(0)) } catch (_: Exception) { "" }
                if (chunk.isNotEmpty()) onRawBle(chunk)
                incomingBuf += chunk
                var idx = incomingBuf.indexOf("\r\n")
                while (idx >= 0) { val line = incomingBuf.substring(0, idx); handleLine(line); incomingBuf = incomingBuf.substring(idx + 2); idx = incomingBuf.indexOf("\r\n") }
            }
        })
    }

    LaunchedEffect(autoConnectMacs) {
        // 自动连接已配对设备（不需再次扫描）
        autoConnectMacs.forEach { mac -> connectToDevice("已配对设备", mac) }
    }

    fun startScanInternal() {
        if (adapter == null || !adapter.isEnabled || scanner == null) { permissionError = "设备蓝牙不可用或未开启"; return }
        devices.clear(); scanning = true
        scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = dev?.name ?: result.scanRecord?.deviceName ?: "未知设备"
                val mac = dev?.address ?: ""
                val rssi = result.rssi
                if (mac.isNotEmpty()) {
                    val idx = devices.indexOfFirst { it.mac == mac }
                    val d = Device(name ?: "未知设备", mac, rssi)
                    if (idx < 0) devices.add(d) else devices[idx] = d
                }
            }
            override fun onBatchScanResults(results: List<ScanResult>) { results.forEach { r -> onScanResult(0, r) } }
            override fun onScanFailed(errorCode: Int) { permissionError = "扫描失败: $errorCode"; scanning = false }
        }
        scanner?.startScan(scanCb)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = res.all { it.value }
        if (ok) { permissionError = null; startScanInternal() } else { permissionError = "蓝牙权限未授权，无法进行扫描" }
    }

    fun startScan() { if (!hasBlePermissions(context)) permLauncher.launch(permissions) else startScanInternal() }
    fun stopScan() { scanning = false; scanCb?.let { scanner?.stopScan(it) }; scanCb = null }

    Column(Modifier.fillMaxSize()) {
    SectionCard(title = tr(lang, "蓝牙设备扫描", "Bluetooth Scan"), modifier = Modifier.weight(1f), trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (scanning) { Text(tr(lang, "扫描中...", "Scanning..."), color = Color(0xFF9bb3d6), fontSize = 12.sp); Spacer(Modifier.width(8.dp)); CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp)) }
            Spacer(Modifier.width(8.dp))
            if (scanning) {
                Button(
                    onClick = { stopScan() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) { Text(tr(lang, "停止扫描", "Stop")) }
            } else {
                OutlinedButton(
                    onClick = { startScan() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) { Text(tr(lang, "开始扫描", "Start")) }
            }
        }
    }) {
        Text(tr(lang, "搜索附近的雷达开关设备", "Search nearby radar switch devices"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (permissionError != null) { Text(permissionError!!, color = Color(0xFFFF6B6B), fontSize = 12.sp); Spacer(Modifier.height(8.dp)) }
        DeviceList(
            lang = lang,
            list = devices,
            connectedMac = connectedMac,
            onConnect = { d -> connectToDevice(d.name, d.mac) },
            modifier = Modifier.fillMaxSize()
        )
    }
    }

}

@Composable
fun ParamsScreen(
    lang: String,
    pairedDevices: List<PairedDevice>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    distanceSeries: List<DistancePoint>,
    eventLogs: List<String>,
    rawBle: List<String>
) {
    var seg by remember { mutableStateOf(0) } // 0: 参数配置, 1: 日志与监控
    // 顶部设备信息（支持横向滑动选择）
    SectionCard(title = tr(lang, "设备信息", "Device Info")) {
        if (pairedDevices.isEmpty()) {
            Text(tr(lang, "暂无已配对设备", "No paired devices"), color = Color(0xFF9bb3d6))
        } else {
            val count = pairedDevices.size
            val initPage = selectedIndex.coerceIn(0, count - 1)
            val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = initPage, pageCount = { count })
            LaunchedEffect(pagerState.currentPage) { onSelectedChange(pagerState.currentPage) }
            androidx.compose.foundation.pager.HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().height(84.dp)) { page ->
                val p = pairedDevices[page]
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(p.name, color = Color.White)
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (p.online) Color(0xFF2DBE60) else Color(0xFF7A889E)).padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (p.online) tr(lang, "在线", "Online") else tr(lang, "离线", "Offline"), color = Color.White, fontSize = 12.sp)
                            }
                        }
                        Text("MAC: ${p.mac}", color = Color(0xFF9bb3d6), fontSize = 12.sp)
                    }
                    // 简单页码显示
                    Text("${page + 1}/${count}", color = Color(0xFF9bb3d6), fontSize = 12.sp)
                }
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC142a49)),
        shape = RoundedCornerShape(24.dp)
    ) {
        val chipColors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.primary,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val sel0 = seg == 0; val sel1 = seg == 1
            FilterChip(
                selected = sel0,
                onClick = { seg = 0 },
                label = { Text(tr(lang, "参数配置", "Params")) },
                colors = chipColors,
                shape = RoundedCornerShape(20.dp)
            )
            FilterChip(
                selected = sel1,
                onClick = { seg = 1 },
                label = { Text(tr(lang, "日志与监控", "Logs & Monitor")) },
                colors = chipColors,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (seg == 0) {
            ParamsConfigContent(lang = lang)
        } else {
            SectionCard(title = tr(lang, "日志与监控", "Logs & Monitor")) {
                val sel = pairedDevices.getOrNull(selectedIndex)
                if (sel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${sel.name} • MAC: ${sel.mac}", color = Color.White)
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel.online) Color(0xFF2DBE60) else Color(0xFF7A889E)).padding(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text(if (sel.online) tr(lang, "在线", "Online") else tr(lang, "离线", "Offline"), color = Color.White, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(tr(lang, "当前无在线设备", "No online device"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                LogsContent(eventLogs = eventLogs, rawBle = rawBle, lang = lang)
                Spacer(Modifier.height(12.dp))
                Text(tr(lang, "目标距离监控(米)", "Target Distance (m)"), color = Color.White)
                DistanceChart(points = distanceSeries)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamsConfigContent(lang: String) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE) }
    var readOnly by remember { mutableStateOf(sp.getBoolean("readonly_mode", false)) }
    var maxDist by remember { mutableStateOf(sp.getFloat("max_distance", 5.0f)) }
    var minDist by remember { mutableStateOf(sp.getFloat("min_distance", 0.5f)) }
    var sensitivity by remember { mutableStateOf(sp.getInt("sensitivity", 70)) }
    var delaySec by remember { mutableStateOf(sp.getInt("delay_sec", 30)) }
    var workModeIdx by remember { mutableStateOf(sp.getInt("work_mode", 0)) }
    var installModeIdx by remember { mutableStateOf(sp.getInt("install_mode", 0)) }

    fun saveAll() {
        try {
            sp.edit()
                .putBoolean("readonly_mode", readOnly)
                .putFloat("max_distance", maxDist)
                .putFloat("min_distance", minDist)
                .putInt("sensitivity", sensitivity)
                .putInt("delay_sec", delaySec)
                .putInt("work_mode", workModeIdx)
                .putInt("install_mode", installModeIdx)
                .apply()
        } catch (_: Exception) {}
    }
    fun resetDefaults() {
        maxDist = 5.0f; minDist = 0.5f; sensitivity = 70; delaySec = 30; workModeIdx = 0; installModeIdx = 0; saveAll()
    }

    // 只读模式
    SectionCard(title = tr(lang, "只读模式", "Read-only Mode"), trailing = {
        val chipColors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.primary,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val selRO = readOnly
            FilterChip(
                selected = selRO,
                onClick = { readOnly = true; saveAll() },
                label = { Text(tr(lang, "只读", "Locked")) },
                colors = chipColors,
                shape = RoundedCornerShape(18.dp)
            )
            FilterChip(
                selected = !selRO,
                onClick = { readOnly = false; saveAll() },
                label = { Text(tr(lang, "可编辑", "Editable")) },
                colors = chipColors,
                shape = RoundedCornerShape(18.dp)
            )
        }
    }) {
        val tipColor = Color(0xFF9bb3d6)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF0f2340)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (readOnly) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null, tint = Color.White)
                    Text(if (readOnly) tr(lang, "已锁定", "Locked") else tr(lang, "未锁定", "Unlocked"), color = Color.White, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(if (readOnly) tr(lang, "当前仅可查看参数，无法修改", "View only, cannot edit") else tr(lang, "当前可修改参数", "Editable now"), color = tipColor, fontSize = 12.sp)
        }
    }

    Spacer(Modifier.height(12.dp))
    // 感应距离设置
    SectionCard(title = tr(lang, "感应距离设置", "Sensing Distance")) {
        Column(Modifier.fillMaxWidth().then(if (readOnly) Modifier.alpha(0.5f) else Modifier)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(lang, "最大感应距离", "Max Distance"), color = Color.White, modifier = Modifier.weight(1f))
            Text(String.format("%.1f", maxDist), color = Color(0xFF9bb3d6))
        }
        Slider(
            value = maxDist,
            onValueChange = { if (!readOnly) maxDist = it },
            valueRange = 0.5f..12f,
            steps = 115,
            enabled = !readOnly
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(lang, "最小感应距离", "Min Distance"), color = Color.White, modifier = Modifier.weight(1f))
            Text(String.format("%.1f", minDist), color = Color(0xFF9bb3d6))
        }
        Slider(
            value = minDist,
            onValueChange = { if (!readOnly) minDist = it },
            valueRange = 0.5f..12f,
            steps = 115,
            enabled = !readOnly
        )
        }
    }

    Spacer(Modifier.height(12.dp))
    // 灵敏度与延时
    SectionCard(title = tr(lang, "灵敏度与延时", "Sensitivity & Delay")) {
        Column(Modifier.fillMaxWidth().then(if (readOnly) Modifier.alpha(0.5f) else Modifier)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(lang, "触发灵敏度", "Trigger Sensitivity"), color = Color.White, modifier = Modifier.weight(1f))
            Text("${sensitivity}", color = Color(0xFF9bb3d6))
        }
        Slider(value = sensitivity.toFloat(), onValueChange = { if (!readOnly) sensitivity = it.toInt() }, valueRange = 0f..100f, steps = 99, enabled = !readOnly)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(lang, "感应延时", "Sensing Delay"), color = Color.White, modifier = Modifier.weight(1f))
            Text("${delaySec}" + tr(lang, "秒", "s"), color = Color(0xFF9bb3d6))
        }
        Slider(value = delaySec.toFloat(), onValueChange = { if (!readOnly) delaySec = it.toInt() }, valueRange = 0f..60f, steps = 60, enabled = !readOnly)
        }
    }

    Spacer(Modifier.height(12.dp))
    // 工作模式
    SectionCard(title = tr(lang, "工作模式", "Work Mode")) {
        val workModes = if (lang == "en") listOf("Normally Open", "Normally Closed", "Delay Off") else listOf("常开模式", "常闭模式", "延时关闭")
        val installModes = if (lang == "en") listOf("Ceiling Mount", "Wall Mount") else listOf("顶装", "壁装")

        var workExpanded by remember { mutableStateOf(false) }
        var installExpanded by remember { mutableStateOf(false) }
        val workLabel = workModes.getOrElse(workModeIdx) { workModes.first() }
        val installLabel = installModes.getOrElse(installModeIdx) { installModes.first() }

        Text(tr(lang, "工作模式", "Work Mode"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
        ExposedDropdownMenuBox(expanded = workExpanded, onExpandedChange = { if (!readOnly) workExpanded = it }) {
            TextField(
                readOnly = true,
                value = workLabel,
                onValueChange = {},
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                enabled = !readOnly,
                colors = TextFieldDefaults.textFieldColors(containerColor = Color(0xFF0f2340))
            )
            ExposedDropdownMenu(expanded = workExpanded, onDismissRequest = { workExpanded = false }) {
                workModes.forEachIndexed { idx, label ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { workModeIdx = idx; workExpanded = false })
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(tr(lang, "安装模式", "Install Mode"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
        ExposedDropdownMenuBox(expanded = installExpanded, onExpandedChange = { if (!readOnly) installExpanded = it }) {
            TextField(
                readOnly = true,
                value = installLabel,
                onValueChange = {},
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                enabled = !readOnly,
                colors = TextFieldDefaults.textFieldColors(containerColor = Color(0xFF0f2340))
            )
            ExposedDropdownMenu(expanded = installExpanded, onDismissRequest = { installExpanded = false }) {
                installModes.forEachIndexed { idx, label ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { installModeIdx = idx; installExpanded = false })
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { if (!readOnly) saveAll() }, enabled = !readOnly, modifier = Modifier.weight(1f)) { Text(tr(lang, "保存配置", "Save")) }
        OutlinedButton(onClick = { if (!readOnly) resetDefaults() }, enabled = !readOnly, modifier = Modifier.weight(1f)) { Text(tr(lang, "恢复默认", "Reset")) }
    }
}

@Composable
fun LogsContent(eventLogs: List<String>, rawBle: List<String>, lang: String) {
    val context = LocalContext.current
    val limit = try { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE).getInt("log_limit", 5) } catch (_: Exception) { 5 }
    val eventState = rememberLazyListState(); val rawState = rememberLazyListState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(tr(lang, "事件日志(${limit})", "Event Logs(${limit})"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
            LazyColumn(state = eventState, modifier = Modifier.height(140.dp)) { items(eventLogs.take(limit)) { Text(it, color = Color.White) } }
        }
        Column(Modifier.weight(1f)) {
            Text(tr(lang, "原始日志(${limit})", "Raw Logs(${limit})"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
            val filtered = rawBle.filter { it.trim().isNotEmpty() }
            LazyColumn(state = rawState, modifier = Modifier.height(140.dp)) { items(filtered.take(limit)) { Text(it, color = Color.White) } }
        }
    }
}

@Composable
fun RssiChart(points: List<RssiPoint>) {
    val context = LocalContext.current
    val secondsWindow = try { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE).getInt("rssi_window", 180) } catch (_: Exception) { 180 }
    val now = System.currentTimeMillis()
    val show = points.filter { now - it.t <= secondsWindow * 1000 }
    val minR = (show.minOfOrNull { it.rssi } ?: -100)
    val maxR = (show.maxOfOrNull { it.rssi } ?: -30)
    val range = (maxR - minR).let { if (it == 0) 1 else it }
    val chartHeight = 160.dp

    Row(Modifier.fillMaxWidth().height(chartHeight)) {
        // Y轴刻度
        Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            val ticks = 4
            for (i in 0..ticks) {
                val v = maxR - i * (range / ticks.toFloat())
                Text(String.format("%.0f", v), color = Color(0xFF9bb3d6), fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.width(6.dp))
        // 曲线与网格
        Canvas(modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0f2340))) {
            val h = size.height; val w = size.width
            val stepY = h / 4f
            repeat(5) { i -> drawLine(color = Color(0x223B5B7E), start = Offset(0f, i * stepY), end = Offset(w, i * stepY)) }
            if (show.size >= 2) {
                val dx = w / (show.size - 1).coerceAtLeast(1)
                val path = Path()
                show.forEachIndexed { i, p ->
                    val x = i * dx
                    val y = h * (maxR - p.rssi) / range
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = Color(0xFF2d7bf3), style = Stroke(width = 3f))
            }
        }
    }
    // X轴刻度（时间）
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        val tick = (secondsWindow / 3).coerceAtLeast(1)
        listOf(0, tick, tick * 2, secondsWindow).forEach { Text("${it}s", color = Color(0xFF9bb3d6), fontSize = 10.sp) }
    }
}
@Composable
fun DistanceChart(points: List<DistancePoint>) {
    val context = LocalContext.current
    val secondsWindow = try { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE).getInt("rssi_window", 180) } catch (_: Exception) { 180 }
    val now = System.currentTimeMillis()
    val show = points.filter { now - it.t <= secondsWindow * 1000 }
    // 固定监控Y轴范围为 [0m, 8m]
    val minD = 0f
    val maxD = 8f
    val range = (maxD - minD)
    val chartHeight = 160.dp

    Row(Modifier.fillMaxWidth().height(chartHeight)) {
        // Y轴刻度（米）
        Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            val ticks = 4
            for (i in 0..ticks) {
                val v = maxD - i * (range / ticks.toFloat())
                Text(String.format("%.1f", v), color = Color(0xFF9bb3d6), fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.width(6.dp))
        // 曲线与网格
        Canvas(modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0f2340))) {
            val h = size.height; val w = size.width
            val stepY = h / 4f
            repeat(5) { i -> drawLine(color = Color(0x223B5B7E), start = Offset(0f, i * stepY), end = Offset(w, i * stepY)) }
            if (show.size >= 2) {
                val dx = w / (show.size - 1).coerceAtLeast(1)
                var lastPoint: Float? = null
                var lastX = 0f
                var lastY = 0f
                show.forEachIndexed { i, p ->
                    val x = i * dx
                    val m = p.meters
                    if (m != null) {
                        // 超出范围的值进行钳制，确保绘制在图内
                        val mc = m.coerceIn(minD, maxD)
                        val y = h * (maxD - mc) / range
                        if (lastPoint != null) {
                            drawLine(color = Color(0xFF2d7bf3), start = Offset(lastX, lastY), end = Offset(x, y), strokeWidth = 3f)
                        }
                        lastPoint = mc
                        lastX = x
                        lastY = y
                    } else {
                        lastPoint = null
                    }
                }
            }
        }
    }
    // X轴刻度（时间）：将 0s 起点相对左侧距离刻度线右移（44dp 刻度列 + 6dp 间隔 + 8dp 额外偏移）
    Row(Modifier.fillMaxWidth().padding(start = 58.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        val tick = (secondsWindow / 3).coerceAtLeast(1)
        listOf(0, tick, tick * 2, secondsWindow).forEach { Text("${it}s", color = Color(0xFF9bb3d6), fontSize = 10.sp) }
    }
}