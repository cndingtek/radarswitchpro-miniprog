package com.dingtek.radarlinkpro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.util.Log
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlin.math.roundToInt

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

data class Device(val name: String, val mac: String, val rssi: Int, val kind: String)

data class PairedDevice(val name: String, val mac: String, val online: Boolean, val lastConnectedMs: Long)

data class RssiPoint(val t: Long, val rssi: Int)

data class DistancePoint(val t: Long, val meters: Float?)

fun tr(lang: String, zh: String, en: String): String = if (lang == "en") en else zh

@Composable
fun RadarLinkApp() {
    val bleChannel = remember { Channel<String>(Channel.UNLIMITED) }
    var tab: TopTab by remember { mutableStateOf<TopTab>(TopTab.Scan) }
    val pairedDevices = remember { mutableStateListOf<PairedDevice>() }
    var selectedPairedIndex by remember { mutableStateOf(0) }
    val rssiSeries = remember { mutableStateListOf<RssiPoint>() }
    val distanceSeries = remember { mutableStateListOf<DistancePoint>() }
    val eventLogs = remember { mutableStateListOf<String>() }
    val rawBleLines = remember { mutableStateListOf<String>() }
    var rawBuf by remember { mutableStateOf("") }
    var rawBleTick by remember { mutableStateOf(0) }
    var currentGatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var currentMtu by remember { mutableStateOf(23) }
    // SPP: 记录各设备 Socket，并为当前选择的设备提供快速访问
    val sppSockets = remember { mutableStateMapOf<String, BluetoothSocket?>() }
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE) }
    val bluetoothManager = remember { context.getSystemService(BluetoothManager::class.java) }
    val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    val autoConnections = remember { mutableStateMapOf<String, BluetoothGatt?>() }
    var appLang by remember { mutableStateOf(sp.getString("language", "zh") ?: "zh") }

    // 统一原始数据推送：按 CRLF 行边界、去重、限制条数，并发送到 bleChannel
    fun pushRaw(raw: String) {
        rawBuf += raw
        rawBuf = rawBuf.replace("\r\n", "\n").replace('\r', '\n')
        var idx = rawBuf.indexOf('\n')
        var addedCount = 0
        while (idx >= 0) {
            val line = rawBuf.substring(0, idx)
            rawBuf = rawBuf.substring(idx + 1)
            val t = line.trim()
            if (t.isNotEmpty()) {
                val entry = "$t\r\n"
                val last = rawBleLines.firstOrNull()?.trim()
                if (last != t) { rawBleLines.add(0, entry); addedCount++; bleChannel.trySend(t) }
            }
            idx = rawBuf.indexOf('\n')
        }
        val limit = try { sp.getInt("log_limit", 5) } catch (_: Exception) { 5 }
        try { while (rawBleLines.size > limit) rawBleLines.removeLast() } catch (_: Exception) {}
        if (addedCount > 0) rawBleTick += addedCount
    }

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
                            try { gatt.requestMtu(185) } catch (_: Exception) {}
                            try { gatt.discoverServices() } catch (_: Exception) {}
                            val idx = pairedDevices.indexOfFirst { it.mac == gatt.device.address }
                            if (idx >= 0) {
                                val p0 = pairedDevices[idx]
                                val realName = try { gatt.device.name } catch (_: Exception) { null }
                                val useName = if (!realName.isNullOrBlank()) realName else p0.name
                                pairedDevices[idx] = p0.copy(name = useName, online = true, lastConnectedMs = System.currentTimeMillis())
                                savePairedToPrefs()
                            }
                        }
                        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            if (currentGatt?.device?.address == gatt.device.address) currentGatt = null
                            val idx = pairedDevices.indexOfFirst { it.mac == gatt.device.address }
                            if (idx >= 0) {
                                val p0 = pairedDevices[idx]
                                val realName = try { gatt.device.name } catch (_: Exception) { null }
                                val useName = if (!realName.isNullOrBlank()) realName else p0.name
                                // 仅当同 MAC 没有处于连接的 SPP Socket 时，才将在线置为 false，避免 BLE 断开覆盖 SPP 在线
                                val stillSppOnline = try { sppSockets[gatt.device.address] != null } catch (_: Exception) { false }
                                val effectiveOnline = if (stillSppOnline) true else false
                                pairedDevices[idx] = p0.copy(name = useName, online = effectiveOnline, lastConnectedMs = System.currentTimeMillis())
                                savePairedToPrefs()
                            }
                            autoConnections.remove(gatt.device.address)
                            try { gatt.close() } catch (_: Exception) {}
                        }
                    }
                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            try { sp.edit().putInt("current_mtu", mtu).apply() } catch (_: Exception) {}
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
                    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic) {
                        // 避免与当前会话的 GATT 重复记录原始BLE，统一由 ScanScreen 的回调处理
                        return
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
                    autoConnectMacs = try {
                        val bm = context.getSystemService(BluetoothManager::class.java)
                        val ad = bm?.adapter
                        ad?.bondedDevices?.map { it.address } ?: emptyList()
                    } catch (_: Exception) { emptyList() },
                    onPaired = { pd ->
                        // 更新或新增配对设备
                        val idx = pairedDevices.indexOfFirst { it.mac == pd.mac }
                        if (idx < 0) {
                            pairedDevices.add(pd)
                        } else {
                            pairedDevices[idx] = pd
                        }
                        // 若为“在线”事件，则联动选中索引到当前设备，避免用户看到其它设备的离线状态
                        if (pd.online) {
                            val cur = pairedDevices.indexOfFirst { it.mac == pd.mac }
                            if (cur >= 0) selectedPairedIndex = cur
                        }
                        savePairedToPrefs()
                    },
                    onGattChanged = { g -> currentGatt = g },
                    onRssi = { v -> rssiSeries.add(RssiPoint(System.currentTimeMillis(), v)) },
                    onLogEvent = { msg ->
                        try { Log.d("RadarLinkPro", msg) } catch (_: Exception) {}
                        // 统一入口简单去重：避免连续重复文案刷屏
                        if (eventLogs.firstOrNull() != msg) eventLogs.add(0, msg)
                        val limit = try { sp.getInt("log_limit", 5) } catch (_: Exception) { 5 }
                        try {
                            while (eventLogs.size > limit) eventLogs.removeLast()
                        } catch (_: Exception) {}
                    },
                    onRawBle = { raw -> pushRaw(raw) },
                    onDistance = { m ->
                        val now = System.currentTimeMillis()
                        distanceSeries.add(DistancePoint(now, m))
                        // 按设置的时间窗口保留数据，避免过度裁剪
                        val winSec = try { sp.getInt("rssi_window", 180) } catch (_: Exception) { 180 }
                        val cutoff = now - winSec * 1000L
                        try {
                            while (distanceSeries.isNotEmpty() && distanceSeries.first().t < cutoff) {
                                distanceSeries.removeAt(0)
                            }
                        } catch (_: Exception) {}
                        // 轻度裁剪，避免无限增长
                        try {
                            if (distanceSeries.size > 2000) {
                                repeat(distanceSeries.size - 2000) { distanceSeries.removeAt(0) }
                            }
                        } catch (_: Exception) {}
                    },
                    onSppSocket = { mac, sock ->
                        sppSockets[mac] = sock
                        // 同步更新已配对设备的在线状态，避免“已连接但显示离线”
                        val idx = pairedDevices.indexOfFirst { it.mac == mac }
                        if (idx >= 0) {
                            val p0 = pairedDevices[idx]
                            pairedDevices[idx] = p0.copy(online = sock != null, lastConnectedMs = System.currentTimeMillis())
                            savePairedToPrefs()
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
                    // 关闭并清除 SPP Socket
                    try { sppSockets[pd.mac]?.close() } catch (_: Exception) {}
                    sppSockets.remove(pd.mac)
                    val idx = pairedDevices.indexOfFirst { it.mac == pd.mac }
                    try {
                        if (idx >= 0) { pairedDevices.removeAt(idx); savePairedToPrefs() }
                    } catch (_: Exception) {}
                }
                is TopTab.Params -> ParamsScreen(
                    lang = appLang,
                    bleChannel = bleChannel,
                    pairedDevices = pairedDevices,
                    selectedIndex = selectedPairedIndex,
                    onSelectedChange = { selectedPairedIndex = it },
                    distanceSeries = distanceSeries,
                    eventLogs = eventLogs,
                    rawBleTick = rawBleTick,
                    rawBle = rawBleLines,
                    gatt = currentGatt,
                    socket = pairedDevices.getOrNull(selectedPairedIndex)?.let { sppSockets[it.mac] },
                    onLogEvent = { msg ->
                        try { Log.d("RadarLinkPro", msg) } catch (_: Exception) {}
                        eventLogs.add(0, msg)
                        val limit = try { sp.getInt("log_limit", 5) } catch (_: Exception) { 5 }
                        try {
                            while (eventLogs.size > limit) eventLogs.removeLast()
                        } catch (_: Exception) {}
                    },
                    onDistance = { m ->
                        val now = System.currentTimeMillis()
                        distanceSeries.add(DistancePoint(now, m))
                        val winSec = try { sp.getInt("rssi_window", 180) } catch (_: Exception) { 180 }
                        val cutoff = now - winSec * 1000L
                        try {
                            while (distanceSeries.isNotEmpty() && distanceSeries.first().t < cutoff) {
                                distanceSeries.removeAt(0)
                            }
                        } catch (_: Exception) {}
                        try {
                            if (distanceSeries.size > 2000) {
                                repeat(distanceSeries.size - 2000) { distanceSeries.removeAt(0) }
                            }
                        } catch (_: Exception) {}
                    }
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
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = Color.White)
                        val kindLabel = if (d.kind == "CLASSIC") "SPP" else "BLE"
                        Text("${d.mac} • ${kindLabel} • RSSI ${d.rssi}", color = Color(0xFF9bb3d6), fontSize = 12.sp)
                    }
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
    var logLimit by remember { mutableStateOf(sp.getInt("log_limit", 20)) }
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
                Text("Website: www.dingtek.com", color = Color(0xFF9bb3d6), fontSize = 12.sp)
                Text("Email: service@dingtek.com", color = Color(0xFF9bb3d6), fontSize = 12.sp)
            } else {
                Text("开发者：$devZh", color = Color(0xFF9bb3d6), fontSize = 12.sp)
                Text("网站：www.dingtek.com.cn", color = Color(0xFF9bb3d6), fontSize = 12.sp)
                Text("邮箱：service@dingtek.com", color = Color(0xFF9bb3d6), fontSize = 12.sp)
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
    onDistance: (Float?) -> Unit,
    onSppSocket: (String, BluetoothSocket?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE) }
    val bluetoothManager = remember { context.getSystemService(BluetoothManager::class.java) }
    val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner

    var scanning by remember { mutableStateOf(false) }
    val devices = remember { mutableStateListOf<Device>() }
    var connectedMac by remember { mutableStateOf<String?>(null) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    var classicReceiver by remember { mutableStateOf<BroadcastReceiver?>(null) }

    var incomingBuf by remember { mutableStateOf("") }
    var lastRangeMm by remember { mutableStateOf<Int?>(null) }
    var pendingOn by remember { mutableStateOf(false) }
    var loggedOff by remember { mutableStateOf(false) }
    var loggedOn by remember { mutableStateOf(false) }
    // 运动状态与单次断线标记：仅在由“有运动”切换到“无运动”时插入一次空点以断开曲线
    var motionActive by remember { mutableStateOf(false) }
    var gapAdded by remember { mutableStateOf(false) }

    fun handleLine(line: String) {
        val t = line.trim(); if (t.isEmpty()) return
        if (t.equals("OFF", true)) {
            // 按运动状态切换判定无运动，避免依赖 loggedOff 标记导致漏记
            pendingOn = false
            if (motionActive) {
                onLogEvent(tr(lang, "未检测到运动", "No motion detected"))
                if (!gapAdded) { onDistance(null); gapAdded = true }
            }
            motionActive = false
            loggedOn = false
            loggedOff = true
            return
        }
        if (t.equals("ON", true)) {
            val mm = lastRangeMm
            if (!loggedOn) {
                if (mm != null) { val m = mm / 1000f; onLogEvent(tr(lang, "检测到运动，距离 ${String.format("%.2f", m)} 米。", "Motion detected, distance ${String.format("%.2f", m)} m.")); loggedOn = true; loggedOff = false; pendingOn = false }
                else pendingOn = true
            }
            motionActive = true
            gapAdded = false
            loggedOff = false
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
                if (motionActive) onDistance(m)
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
                    try { gatt.requestMtu(185) } catch (_: Exception) {}
                    try { gatt.discoverServices() } catch (_: Exception) {}
                    val realName = try { gatt.device.name } catch (_: Exception) { null }
                    val useName = if (!realName.isNullOrBlank()) realName!! else nameGuess
                    onPaired(PairedDevice(useName, mac, online = true, lastConnectedMs = System.currentTimeMillis()))
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (connectedMac == gatt.device.address) connectedMac = null
                    onGattChanged(null)
                    val realName = try { gatt.device.name } catch (_: Exception) { null }
                    val useName = if (!realName.isNullOrBlank()) realName!! else nameGuess
                    onPaired(PairedDevice(useName, mac, online = false, lastConnectedMs = System.currentTimeMillis()))
                }
            }
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    try { sp.edit().putInt("current_mtu", mtu).apply() } catch (_: Exception) {}
                    onLogEvent(tr(lang, "MTU 已协商为 ${mtu}", "MTU negotiated to ${mtu}"))
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
                // 暂停运动/距离解析：只记录原始BLE，不解析ON/OFF/Range
                val paused = try { sp.getBoolean("pause_motion", false) } catch (_: Exception) { false }
                // 如需清空接收缓冲区，执行一次
                val needClear = try { sp.getBoolean("clear_incoming", false) } catch (_: Exception) { false }
                if (needClear) {
                    incomingBuf = ""
                    try { sp.edit().putBoolean("clear_incoming", false).apply() } catch (_: Exception) {}
                }
                if (paused) return
                incomingBuf += chunk
                var idx = incomingBuf.indexOf("\r\n")
                while (idx >= 0) { val line = incomingBuf.substring(0, idx); handleLine(line); incomingBuf = incomingBuf.substring(idx + 2); idx = incomingBuf.indexOf("\r\n") }
            }
        })
    }

    // SPP 连接与读取循环（优先使用 SPP，失败时回退到 BLE）
    fun connectSpp(nameGuess: String, mac: String) {
        // 优先解析可用于 SPP 的经典/双模设备，避免仅 LE 设备地址导致连接失败
        val fromMac: BluetoothDevice? = try { adapter?.getRemoteDevice(mac) } catch (_: Exception) { null }
        val bonded = try { adapter?.bondedDevices?.toList() } catch (_: Exception) { null } ?: emptyList()
        val devCandidates = mutableListOf<BluetoothDevice>()
        // 1) 精确地址命中（系统配对列表中）
        bonded.firstOrNull { it.address.equals(mac, ignoreCase = true) }?.let { devCandidates.add(it) }
        // 2) 如果从 MAC 获取到设备且不是纯 LE，则加入候选
        if (fromMac != null && fromMac.type != BluetoothDevice.DEVICE_TYPE_LE) {
            if (devCandidates.none { it.address == fromMac.address }) devCandidates.add(fromMac)
        }
        // 2.5) 针对双模设备：尝试按 BLE->SPP 偏移的地址（最后字节 ±1）
        run {
            val parts = mac.split(":")
            if (parts.size == 6) {
                val lastVal = try { parts[5].toInt(16) } catch (_: Exception) { null }
                fun joinWithLast(v: Int): String {
                    val s = v.toString(16).uppercase().padStart(2, '0')
                    return parts.take(5).joinToString(":") + ":" + s
                }
                val plusAddr = lastVal?.let { if (it < 0xFF) joinWithLast(it + 1) else null }
                val minusAddr = lastVal?.let { if (it > 0x00) joinWithLast(it - 1) else null }
                val derived = listOfNotNull(plusAddr, minusAddr)
                if (derived.isNotEmpty()) {
                    // 移除 SPP 候选地址的调试输出
                }
                derived.forEach { a ->
                    bonded.firstOrNull { it.address.equals(a, ignoreCase = true) }?.let { d ->
                        if (devCandidates.none { it.address == d.address }) devCandidates.add(d)
                    }
                    val dm = try { adapter?.getRemoteDevice(a) } catch (_: Exception) { null }
                    if (dm != null && dm.type != BluetoothDevice.DEVICE_TYPE_LE) {
                        if (devCandidates.none { it.address == dm.address }) devCandidates.add(dm)
                    }
                }
            }
        }
        // 3) 按名称匹配（处理 BLE 与经典地址不同的设备）
        if (devCandidates.isEmpty()) {
            bonded.filter { (it.name ?: "") == nameGuess }.forEach { d ->
                if (devCandidates.none { it.address == d.address }) devCandidates.add(d)
            }
        }
        // 4) 最后兜底：使用原始地址对应的设备对象（可能是 LE，仅用于反射尝试，不一定可连）
        if (devCandidates.isEmpty() && fromMac != null) devCandidates.add(fromMac)
        if (devCandidates.isEmpty()) {
            // 移除 SPP 未找到候选设备的调试输出
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val defaultSpp = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                var sock: BluetoothSocket? = null
                var connected = false
                var lastErr: Exception? = null
                var usedDevice: BluetoothDevice? = null

                // 逐个候选设备尝试：优先使用标准 SPP UUID，再回退到反射通道
                for (dev in devCandidates) {
                    val typeStr = when (dev.type) {
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
                        BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
                        BluetoothDevice.DEVICE_TYPE_LE -> "LE"
                        else -> dev.type.toString()
                    }
                    // 移除 SPP 目标设备类型的调试输出

                    // 拉取 SDP UUID（可选），但始终将标准 SPP UUID 放在首位
                    val uuids = mutableListOf<java.util.UUID>()
                    uuids.add(defaultSpp)
                    try { dev.fetchUuidsWithSdp() } catch (_: Exception) {}
                    try { kotlinx.coroutines.delay(500) } catch (_: Exception) {}
                    val sdp = try { dev.uuids?.mapNotNull { it?.uuid } ?: emptyList() } catch (_: Exception) { emptyList() }
                    sdp.filter { it != defaultSpp }.forEach { uuids.add(it) }
                    // 移除 SPP UUID 尝试的调试输出

                    for (u in uuids) {
                        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
                        // 先尝试安全 RFCOMM
                        try {
                            val s1 = dev.createRfcommSocketToServiceRecord(u)
                            s1.connect()
                            sock = s1
                            connected = true
                            usedDevice = dev
                            break
                        } catch (e1: Exception) {
                            lastErr = e1
                            try { Log.e("RadarLinkPro", "Secure RFCOMM connect failed for ${u}: ${e1.message}") } catch (_: Exception) {}
                            try { sock?.close() } catch (_: Exception) {}
                            // 非安全 RFCOMM 重试
                            try {
                                val s2 = dev.createInsecureRfcommSocketToServiceRecord(u)
                                try { adapter?.cancelDiscovery() } catch (_: Exception) {}
                                s2.connect()
                                sock = s2
                                connected = true
                                usedDevice = dev
                                break
                            } catch (e2: Exception) {
                                lastErr = e2
                                try { Log.e("RadarLinkPro", "Insecure RFCOMM connect failed for ${u}: ${e2.message}") } catch (_: Exception) {}
                                try { sock?.close() } catch (_: Exception) {}
                            }
                        }
                    }
                    if (connected && sock != null) break
                    // 为提升稳定性，禁用 RFCOMM 反射通道扫描，仅使用标准/SDP UUID 的安全/不安全连接
                    if (connected && sock != null) break
                }
                if (!connected || sock == null) throw (lastErr ?: java.lang.Exception("SPP connect failed: no UUID connectable"))
                val usedMac = usedDevice?.address ?: mac
                connectedMac = usedMac
                onSppSocket(usedMac, sock)
                val realName = try { usedDevice?.name } catch (_: Exception) { null }
                val useName = if (!realName.isNullOrBlank()) realName!! else nameGuess
                onPaired(PairedDevice(useName, usedMac, online = true, lastConnectedMs = System.currentTimeMillis()))
                // 移除 SPP 已连接的调试输出
                // 主动发送握手序列，避免设备因未收到首包而关闭会话
                try {
                    val out = sock.outputStream
                    // 仅发送 AT，移除 AA
                    out.write("AT\r\n".toByteArray())
                    out.flush()
                    try { kotlinx.coroutines.delay(50) } catch (_: Exception) {}
                } catch (ehs: Exception) {
                    // 移除 SPP 握手失败的调试输出
                }
                // 读取循环
                val ins = sock.inputStream
                val buf = ByteArray(1024)
                while (sock.isConnected) {
                    val n = try { ins.read(buf) } catch (_: Exception) { -1 }
                    if (n == -1) break
                    if (n > 0) {
                        val s = try { String(buf, 0, n) } catch (_: Exception) { null }
                        if (!s.isNullOrEmpty()) {
                            // 原始流保留（用于参数解析）
                            onRawBle(s!!)
                            // 将 SPP 数据也走统一的 ON/OFF/Range 解析管道，修复目标距离监控不刷新
                            try {
                                val paused = try { sp.getBoolean("pause_motion", false) } catch (_: Exception) { false }
                                val needClear = try { sp.getBoolean("clear_incoming", false) } catch (_: Exception) { false }
                                if (needClear) { incomingBuf = ""; try { sp.edit().putBoolean("clear_incoming", false).apply() } catch (_: Exception) {} }
                                if (!paused) {
                                    incomingBuf += s
                                    var idx = incomingBuf.indexOf("\r\n")
                                    while (idx >= 0) {
                                        val line = incomingBuf.substring(0, idx)
                                        handleLine(line)
                                        incomingBuf = incomingBuf.substring(idx + 2)
                                        idx = incomingBuf.indexOf("\r\n")
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                // 断开处理（移除 SPP 已断开的调试输出）
                onSppSocket(usedMac, null)
                if (connectedMac == usedMac) connectedMac = null
                val realName2 = try { usedDevice?.name } catch (_: Exception) { null }
                val useName2 = if (!realName2.isNullOrBlank()) realName2!! else nameGuess
                onPaired(PairedDevice(useName2, usedMac, online = false, lastConnectedMs = System.currentTimeMillis()))
            } catch (e: Exception) {
                // 移除 SPP 连接失败的调试输出
                onSppSocket(mac, null)
                // 回退到 BLE
                connectToDevice(nameGuess, mac)
            }
        }
    }

    // 已移除自动 SPP 连接，避免扫描页出现未经点击的配对请求弹窗

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
                    val d = Device(name ?: "未知设备", mac, rssi, "BLE")
                    if (idx < 0) devices.add(d) else devices[idx] = d
                }
            }
            override fun onBatchScanResults(results: List<ScanResult>) { results.forEach { r -> onScanResult(0, r) } }
            override fun onScanFailed(errorCode: Int) { permissionError = "扫描失败: $errorCode"; scanning = false }
        }
        scanner?.startScan(scanCb)
        // 同步启动经典蓝牙设备发现，接收 ACTION_FOUND 广播，将设备加入列表（kind=CLASSIC）
        classicReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                    val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val name = dev?.name ?: "未知设备"
                    val mac = dev?.address ?: ""
                    val rssi = try { intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt() } catch (_: Exception) { 0 }
                    if (mac.isNotEmpty()) {
                        val idx = devices.indexOfFirst { it.mac == mac }
                        val d = Device(name, mac, if (rssi == Short.MIN_VALUE.toInt()) 0 else rssi, "CLASSIC")
                        if (idx < 0) devices.add(d) else devices[idx] = d
                    }
                }
            }
        }
        try { context.registerReceiver(classicReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND)) } catch (_: Exception) {}
        try { adapter?.startDiscovery() } catch (_: Exception) {}
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = res.all { it.value }
        if (ok) { permissionError = null; startScanInternal() } else { permissionError = "蓝牙权限未授权，无法进行扫描" }
    }

    fun startScan() { if (!hasBlePermissions(context)) permLauncher.launch(permissions) else startScanInternal() }
    fun stopScan() {
        scanning = false
        scanCb?.let { scanner?.stopScan(it) }
        scanCb = null
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        classicReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        classicReceiver = null
    }

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
            onConnect = { d -> if (d.kind == "CLASSIC") connectSpp(d.name, d.mac) else connectToDevice(d.name, d.mac) },
            modifier = Modifier.fillMaxSize()
        )
    }
    }

}

@Composable
fun ParamsScreen(
    lang: String,
    bleChannel: Channel<String>,
    pairedDevices: List<PairedDevice>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    distanceSeries: List<DistancePoint>,
    eventLogs: List<String>,
    rawBleTick: Int,
    rawBle: List<String>,
    gatt: BluetoothGatt?,
    socket: BluetoothSocket?,
    onLogEvent: (String) -> Unit,
    onDistance: (Float?) -> Unit
) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE) }
    var seg by remember { mutableStateOf(0) } // 0: 参数配置, 1: 日志与监控
    // 进入“日志与监控”时恢复运动/记录解析
    LaunchedEffect(seg) {
        if (seg == 1) {
            try { sp.edit().putBoolean("pause_motion", false).apply() } catch (_: Exception) {}
        }
    }
    // 顶部设备信息（支持横向滑动选择）
    SectionCard(title = tr(lang, "设备信息", "Device Info")) {
        if (pairedDevices.isEmpty()) {
            Text(tr(lang, "暂无已配对设备", "No paired devices"), color = Color(0xFF9bb3d6))
        } else {
            val count = pairedDevices.size
            val initPage = selectedIndex.coerceIn(0, count - 1)
            val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = initPage, pageCount = { count })
            // Pager -> 选中索引：滑动时更新选中索引
            LaunchedEffect(pagerState.currentPage) { onSelectedChange(pagerState.currentPage) }
            // 选中索引 -> Pager：当连接事件触发我们改变 selectedIndex 时，让 Pager 跟随到该页
            LaunchedEffect(selectedIndex) {
                val target = selectedIndex.coerceIn(0, count - 1)
                try { pagerState.scrollToPage(target) } catch (_: Exception) {}
            }
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
            ParamsConfigContent(lang = lang, gatt = gatt, socket = socket, rawBle = rawBle, rawBleTick = rawBleTick, onLogEvent = onLogEvent, bleChannel = bleChannel)
        } else {
            SectionCard(title = tr(lang, "日志与监控", "Logs & Monitor")) {
                val sel = pairedDevices.getOrNull(selectedIndex)
                if (sel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${sel.name} • MAC: ${sel.mac}", color = Color.White)
                        val bleOnline = try { gatt?.device?.address == sel.mac } catch (_: Exception) { false }
                        val sppOnline = socket != null
                        val effectiveOnline = bleOnline || sppOnline || sel.online
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (effectiveOnline) Color(0xFF2DBE60) else Color(0xFF7A889E)).padding(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text(if (effectiveOnline) tr(lang, "在线", "Online") else tr(lang, "离线", "Offline"), color = Color.White, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 设备名驱动的运动/距离解析（DC590/DC591 解析 Range/ON/OFF，其它解析 0 或 1,114cm,103）
                    var monBuf by remember { mutableStateOf("") }
                    var lastTick by remember { mutableStateOf(0) }
                    var lastRangeMm by remember { mutableStateOf<Int?>(null) }
                    var pendingOn by remember { mutableStateOf(false) }
                    var loggedOff by remember { mutableStateOf(false) }
                    var loggedOn by remember { mutableStateOf(false) }
                    // 运动状态与单次断线标记：切到无运动时仅插入一次空点以断开曲线
                    var motionActive by remember { mutableStateOf(false) }
                    var gapAdded by remember { mutableStateOf(false) }
                    // 最近一次检测到的目标类型：1 运动目标，2 微动目标；用于记录类型切换事件
                    var lastTargetType by remember { mutableStateOf<Int?>(null) }

                    fun addEvent(msg: String) { onLogEvent(msg) }

                    fun handleMonLine(line: String) {
                        val t = line.trim(); if (t.isEmpty()) return
                        val lower = t.lowercase()

                        // 统一解析：同时支持 DC590/DC591 的 ON/OFF/Range 以及其它设备的 no alarm / 新旧格式
                        // 1) 无目标："no alarm" 或旧格式 "0"
                        if (lower.startsWith("no alarm") || t == "0") {
                            if (motionActive) {
                                addEvent(tr(lang, "未检测到目标", "No target detected"))
                                if (!gapAdded) { onDistance(null); gapAdded = true }
                            }
                            motionActive = false
                            loggedOn = false
                            loggedOff = true
                            lastTargetType = null
                            return
                        }

                        // 2) 新格式：{目标类型},R:{距离值}cm,P:{能量值}
                        run {
                            val rxNew = Regex("(?i)^\\s*([12])\\s*,\\s*R:\\s*([0-9]+)\\s*cm\\s*,\\s*P:\\s*([0-9]+)")
                            val mNew = rxNew.find(t)
                            if (mNew != null) {
                                val type = mNew.groupValues.getOrNull(1)?.toIntOrNull()
                                val cm = mNew.groupValues.getOrNull(2)?.toIntOrNull()
                                if (cm != null) {
                                    lastRangeMm = cm * 10
                                    val meters = cm / 100f
                                    motionActive = true
                                    gapAdded = false
                                    onDistance(meters)
                                    val prevType = lastTargetType
                                    val typeLabelCn = if (type == 2) "微动目标" else "运动目标"
                                    val typeLabelEn = if (type == 2) "micro-motion target" else "moving target"
                                    if (prevType != null && type != null && prevType != type) {
                                        addEvent(tr(lang, "目标类型切换为${typeLabelCn}", "Target type switched to ${typeLabelEn}"))
                                    }
                                    lastTargetType = type
                                    if (!loggedOn) {
                                        addEvent(tr(lang, "检测到${typeLabelCn}，距离 ${String.format("%.2f", meters)} 米。", "Detected ${typeLabelEn}, distance ${String.format("%.2f", meters)} m."))
                                        loggedOn = true; loggedOff = false; pendingOn = false
                                    } else {
                                        addEvent(tr(lang, "目标距离变化至 ${String.format("%.2f", meters)} 米。", "Target distance changed to ${String.format("%.2f", meters)} m."))
                                    }
                                    loggedOff = false
                                }
                                return
                            }
                        }

                        // 3) 兼容旧格式：1,114cm,103
                        run {
                            val rxOld = Regex("^1,\\s*([0-9]+)cm,\\s*([0-9]+)")
                            val mOld = rxOld.find(t)
                            if (mOld != null) {
                                val cm = mOld.groupValues.getOrNull(1)?.toIntOrNull()
                                if (cm != null) {
                                    lastRangeMm = cm * 10
                                    val meters = cm / 100f
                                    motionActive = true
                                    gapAdded = false
                                    onDistance(meters)
                                    val prevType = lastTargetType
                                    val newType = 1
                                    if (prevType != null && prevType != newType) {
                                        addEvent(tr(lang, "目标类型切换为运动目标", "Target type switched to moving target"))
                                    }
                                    lastTargetType = newType
                                    if (!loggedOn) { addEvent(tr(lang, "检测到运动目标，距离 ${String.format("%.2f", meters)} 米。", "Detected moving target, distance ${String.format("%.2f", meters)} m.")); loggedOn = true; loggedOff = false; pendingOn = false }
                                    else { addEvent(tr(lang, "目标距离变化至 ${String.format("%.2f", meters)} 米。", "Target distance changed to ${String.format("%.2f", meters)} m.")) }
                                    loggedOff = false
                                }
                                return
                            }
                        }

                        // 4) DC 风格：OFF/ON/Range mm
                        if (t.equals("OFF", true)) {
                            pendingOn = false
                            if (motionActive) {
                                addEvent(tr(lang, "未检测到运动", "No motion detected"))
                                if (!gapAdded) { onDistance(null); gapAdded = true }
                            }
                            motionActive = false
                            loggedOn = false
                            loggedOff = true
                            return
                        }
                        if (t.equals("ON", true)) {
                            val mm = lastRangeMm
                            if (!loggedOn) {
                                if (mm != null) { val m = mm / 1000f; addEvent(tr(lang, "检测到运动，距离 ${String.format("%.2f", m)} 米。", "Motion detected, distance ${String.format("%.2f", m)} m.")); loggedOn = true; loggedOff = false; pendingOn = false }
                                else pendingOn = true
                            }
                            motionActive = true
                            gapAdded = false
                            loggedOff = false
                            return
                        }
                        if (t.startsWith("Range ")) {
                            val numStr = t.removePrefix("Range ").trim().takeWhile { it.isDigit() }
                            val mmParsed = numStr.toIntOrNull()
                            if (mmParsed != null) {
                                val previous = lastRangeMm
                                lastRangeMm = mmParsed
                                val m = mmParsed / 1000f
                                if (motionActive) onDistance(m)
                                if (pendingOn && !loggedOn) { addEvent(tr(lang, "检测到运动，距离 ${String.format("%.2f", m)} 米。", "Motion detected, distance ${String.format("%.2f", m)} m.")); loggedOn = true; loggedOff = false; pendingOn = false }
                                if (loggedOn && previous != null && previous != mmParsed) { addEvent(tr(lang, "目标距离变化至 ${String.format("%.2f", m)} 米。", "Target distance changed to ${String.format("%.2f", m)} m.")) }
                            }
                            return
                        }
                        // 5) 其它："have alarm" 直接跳过
                        if (lower.startsWith("have alarm")) return
                    }

                    LaunchedEffect(seg, selectedIndex, rawBleTick) {
                        if (seg != 1) return@LaunchedEffect
                        // 支持“只读”暂停/清空
                        val paused = try { sp.getBoolean("pause_motion", false) } catch (_: Exception) { false }
                        val needClear = try { sp.getBoolean("clear_incoming", false) } catch (_: Exception) { false }
                        if (needClear) {
                            monBuf = ""
                            lastTick = rawBleTick
                            try { sp.edit().putBoolean("clear_incoming", false).apply() } catch (_: Exception) {}
                        }
                        if (paused) return@LaunchedEffect
                        // 合并新增原始BLE块，并按 CRLF 行解析
                        // 注意：通过 rawBleTick 可靠获知新增行数量（即使列表大小不变也能触发）
                        val newTick = rawBleTick
                        val toRead = (newTick - lastTick).coerceAtLeast(0)
                        val added = if (toRead > 0) rawBle.take(toRead).joinToString("") else ""
                        if (added.isNotEmpty()) {
                            monBuf += added
                            lastTick = newTick
                            var idx = monBuf.indexOf("\r\n")
                            while (idx >= 0) {
                                val line = monBuf.substring(0, idx)
                                handleMonLine(line)
                                monBuf = monBuf.substring(idx + 2)
                                idx = monBuf.indexOf("\r\n")
                            }
                        }
                    }
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

enum class HandshakeState { IDLE, WAITING_FOR_STOP, SENDING_COMMANDS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
    fun ParamsConfigContent(lang: String, gatt: BluetoothGatt?, socket: BluetoothSocket?, rawBle: List<String>, rawBleTick: Int, onLogEvent: (String) -> Unit, bleChannel: Channel<String>) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var readOnly by remember { mutableStateOf(sp.getBoolean("readonly_mode", true)) }
    var awaitingReset by remember { mutableStateOf(false) }
    var resetStartTick by remember { mutableStateOf(0) }
    var handshakeState by remember { mutableStateOf(HandshakeState.IDLE) }
    var commandQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var handshakeInitiatedTick by remember { mutableStateOf(0) }
    // 累积等待解析的键值，避免日志裁剪导致丢失
    var pendingHoldFrame by remember { mutableStateOf<Int?>(null) }
    var pendingMr1Cm by remember { mutableStateOf<Int?>(null) }
    var pendingMr2Cm by remember { mutableStateOf<Int?>(null) }
    var pendingMr3Cm by remember { mutableStateOf<Int?>(null) }
    var pendingRange1Cm by remember { mutableStateOf<Int?>(null) }
    var pendingRange2Cm by remember { mutableStateOf<Int?>(null) }
    var pendingRange3Cm by remember { mutableStateOf<Int?>(null) }
    var pendingTrith by remember { mutableStateOf<Int?>(null) }
    // 六项距离（米）：运动 MRange1/2/3 与存在 Range1/2/3
    var mRange1 by remember { mutableStateOf(sp.getFloat("mrange1", 2.0f)) }
    var mRange2 by remember { mutableStateOf(sp.getFloat("mrange2", 4.0f)) }
    var mRange3 by remember { mutableStateOf(sp.getFloat("mrange3", 6.0f)) }
    var range1 by remember { mutableStateOf(sp.getFloat("range1", 1.5f)) }
    var range2 by remember { mutableStateOf(sp.getFloat("range2", 3.0f)) }
    var range3 by remember { mutableStateOf(sp.getFloat("range3", 4.5f)) }
    var sensitivity by remember { mutableStateOf(sp.getInt("sensitivity", 2)) }
    var delaySec by remember { mutableStateOf(sp.getInt("delay_sec", 30)) }
    var workModeIdx by remember { mutableStateOf(sp.getInt("work_mode", 0)) }
    var installModeIdx by remember { mutableStateOf(sp.getInt("install_mode", 0)) }
    // 用于常态解析 Range 响应（不依赖等待状态），只处理新增数据
    var lastRangeTick by remember { mutableStateOf(0) }

    fun saveAll() {
        try {
            sp.edit()
                .putBoolean("readonly_mode", readOnly)
                .putFloat("mrange1", mRange1)
                .putFloat("mrange2", mRange2)
                .putFloat("mrange3", mRange3)
                .putFloat("range1", range1)
                .putFloat("range2", range2)
                .putFloat("range3", range3)
                .putInt("sensitivity", sensitivity)
                .putInt("delay_sec", delaySec)
                .putInt("work_mode", workModeIdx)
                .putInt("install_mode", installModeIdx)
                .apply()
        } catch (_: Exception) {}
    }
    fun findWritableCharacteristic(g: BluetoothGatt?): android.bluetooth.BluetoothGattCharacteristic? {
        if (g == null) return null
        return try {
            var candidate: android.bluetooth.BluetoothGattCharacteristic? = null
            g.services?.forEach { svc ->
                val hasNotify = (svc.characteristics?.any { ch ->
                    val props = ch.properties
                    ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) ||
                            ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                } ?: false)
                val writable = svc.characteristics?.firstOrNull { ch ->
                    val props = ch.properties
                    ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) ||
                            ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                }
                // 优先选择同时包含通知/指示与可写特性的服务（通常是透传串口服务）
                if (hasNotify && writable != null) return writable
                if (candidate == null && writable != null) candidate = writable
            }
            candidate
        } catch (_: Exception) { null }
    }

    fun sendAtCommands(g: BluetoothGatt?, lines: List<String>) {
        try {
            // 优先使用 SPP 写入
            val sock = socket
            if (sock != null && sock.isConnected) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val out = sock.outputStream
                        lines.forEach { line ->
                            val payload = (line + "\r\n").toByteArray()
                            try { out.write(payload) } catch (_: Exception) {}
                            try { out.flush() } catch (_: Exception) {}
                            kotlinx.coroutines.delay(120)
                        }
                    } catch (_: Exception) {
                        // 移除 SPP 写入失败的调试输出；回退将继续执行 BLE 逻辑
                    }
                }
                return
            }
            var ch = findWritableCharacteristic(g)
            if (ch != null) {
                // 逐条发送，添加 CRLF
                scope.launch {
                    lines.forEach { line ->
                        val payload = (line + "\r\n").toByteArray()
                        val limit = try { kotlin.math.max(20, sp.getInt("current_mtu", 23) - 3) } catch (_: Exception) { 20 }
                        var offset = 0
                        while (offset < payload.size) {
                            val sliceLen = kotlin.math.min(limit, payload.size - offset)
                            val slice = payload.copyOfRange(offset, offset + sliceLen)
                            try { ch!!.value = slice } catch (_: Exception) {}
                            try {
                                ch!!.writeType = if ((ch!!.properties and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                                    android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                else android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            } catch (_: Exception) {}
                            try { g?.writeCharacteristic(ch) } catch (_: Exception) {}
                            kotlinx.coroutines.delay(25)
                            offset += sliceLen
                        }
                        // 每条指令之间保留间隔，避免设备串口缓冲溢出
                        kotlinx.coroutines.delay(120)
                    }
                }
            } else {
                // 未找到可写特性：触发服务发现，稍后重试一次
                try { g?.discoverServices() } catch (_: Exception) {}
                scope.launch {
                    kotlinx.coroutines.delay(500)
                    val retry = findWritableCharacteristic(g)
                    if (retry != null) {
                        lines.forEach { line ->
                            val payload = (line + "\r\n").toByteArray()
                            val limit = try { kotlin.math.max(20, sp.getInt("current_mtu", 23) - 3) } catch (_: Exception) { 20 }
                            var offset = 0
                            while (offset < payload.size) {
                                val sliceLen = kotlin.math.min(limit, payload.size - offset)
                                val slice = payload.copyOfRange(offset, offset + sliceLen)
                                try { retry.value = slice } catch (_: Exception) {}
                                try {
                                    retry.writeType = if ((retry.properties and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                                        android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                    else android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                } catch (_: Exception) {}
                                try { g?.writeCharacteristic(retry) } catch (_: Exception) {}
                                kotlinx.coroutines.delay(25)
                                offset += sliceLen
                            }
                            kotlinx.coroutines.delay(120)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
    fun startHandshake(commands: List<String>) {
        if (handshakeState != HandshakeState.IDLE) return
        commandQueue = commands
        // 在按钮操作场景下，无论 BLE 还是 SPP，都保持 AA 握手与 STOP/TOP 等待
        handshakeState = HandshakeState.WAITING_FOR_STOP
        handshakeInitiatedTick = rawBleTick
        // Drain any previous BLE lines to avoid false STOP/TOP triggers
        while (true) {
            val r = bleChannel.tryReceive()
            if (!r.isSuccess) break
        }
        sendAtCommands(gatt, listOf("AA"))
        onLogEvent(tr(lang, "发送 AA 指令并等待 STOP...", "Sending AA and waiting for STOP..."))
    }

    LaunchedEffect(handshakeState) {
        if (handshakeState == HandshakeState.WAITING_FOR_STOP) {
            delay(5000) // 5s timeout
            if (handshakeState == HandshakeState.WAITING_FOR_STOP) {
                handshakeState = HandshakeState.IDLE
                onLogEvent(tr(lang, "握手超时", "Handshake timed out"))
            }
        }
    }



    LaunchedEffect(handshakeState, bleChannel) {
        if (handshakeState != HandshakeState.WAITING_FOR_STOP) return@LaunchedEffect

        for (line in bleChannel) {
            val stream = line.uppercase()
            if (stream.contains("STOP") || stream.contains("TOP")) {
                onLogEvent(tr(lang, "收到应答，发送实际指令...", "Received ACK, sending actual commands..."))
                handshakeState = HandshakeState.SENDING_COMMANDS
                sendAtCommands(gatt, commandQueue)
                // 指令发送后，很快重置状态
                scope.launch {
                    delay(500)
                    handshakeState = HandshakeState.IDLE
                    commandQueue = emptyList()
                }
                break // Exit the loop after handling the response
            }
        }
    }

    fun resetDefaults() {
        // 六项距离默认值（单位：米）：M 2/4/6，R 1.5/3/4.5
        mRange1 = 2.0f; mRange2 = 4.0f; mRange3 = 6.0f
        range1 = 1.5f; range2 = 3.0f; range3 = 4.5f
        sensitivity = 2; delaySec = 20; workModeIdx = 0; installModeIdx = 0; saveAll()
        // 发送 AT+INIT 指令（使用通用发送函数，自动选择写入类型并在需要时触发服务发现）
        startHandshake(listOf("AT+INIT"))
        onLogEvent(tr(lang, "等待设备响应…", "Awaiting device response…"))
        awaitingReset = true
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
                onClick = {
                    readOnly = true
                    saveAll()
                    onLogEvent(tr(lang, "切换为只读模式，发送 AT+RESET 并等待响应…", "Switched to read-only, sent AT+RESET and awaiting response…"))
                    startHandshake(listOf("AT+RESET"))
                    awaitingReset = true
                    // 点击只读后：暂停运动/距离解析，并清空接收缓冲区
                    try { sp.edit().putBoolean("pause_motion", true).putBoolean("clear_incoming", true).apply() } catch (_: Exception) {}
                },
                label = { Text(tr(lang, "只读", "Locked")) },
                colors = chipColors,
                shape = RoundedCornerShape(18.dp)
            )
            FilterChip(
                selected = !selRO,
                onClick = { readOnly = false; saveAll(); onLogEvent(tr(lang, "切换为可编辑模式", "Switched to editable mode")) },
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
    // 感应距离设置：运动与存在六项
    SectionCard(title = tr(lang, "感应距离设置", "Sensing Distance")) {
        Column(Modifier.fillMaxWidth().then(if (readOnly) Modifier.alpha(0.5f) else Modifier)) {
            // 运动（MRange）
            Text(tr(lang, "运动感应距离", "Motion Sensing"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(lang, "远段运动感应距离", "Far Motion Distance"), color = Color.White, modifier = Modifier.weight(1f))
                Text(String.format("%.1f", mRange3), color = Color(0xFF9bb3d6))
            }
            Slider(value = mRange3, onValueChange = { if (!readOnly) mRange3 = it.coerceIn(0f, 6f) }, valueRange = 0f..6f, steps = 60, enabled = !readOnly)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(lang, "中段运动感应距离", "Mid Motion Distance"), color = Color.White, modifier = Modifier.weight(1f))
                Text(String.format("%.1f", mRange2), color = Color(0xFF9bb3d6))
            }
            Slider(value = mRange2, onValueChange = { if (!readOnly) mRange2 = it.coerceIn(0f, 6f) }, valueRange = 0f..6f, steps = 60, enabled = !readOnly)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(lang, "近端运动感应距离", "Near Motion Distance"), color = Color.White, modifier = Modifier.weight(1f))
                Text(String.format("%.1f", mRange1), color = Color(0xFF9bb3d6))
            }
            Slider(value = mRange1, onValueChange = { if (!readOnly) mRange1 = it.coerceIn(0f, 6f) }, valueRange = 0f..6f, steps = 60, enabled = !readOnly)

            Spacer(Modifier.height(12.dp))
            // 存在（Range）
            Text(tr(lang, "存在感应距离", "Presence Sensing"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(lang, "远段存在感应距离", "Far Presence Distance"), color = Color.White, modifier = Modifier.weight(1f))
                Text(String.format("%.1f", range3), color = Color(0xFF9bb3d6))
            }
            Slider(value = range3, onValueChange = { if (!readOnly) range3 = it.coerceIn(0f, 6f) }, valueRange = 0f..6f, steps = 60, enabled = !readOnly)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(lang, "中段存在感应距离", "Mid Presence Distance"), color = Color.White, modifier = Modifier.weight(1f))
                Text(String.format("%.1f", range2), color = Color(0xFF9bb3d6))
            }
            Slider(value = range2, onValueChange = { if (!readOnly) range2 = it.coerceIn(0f, 6f) }, valueRange = 0f..6f, steps = 60, enabled = !readOnly)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(lang, "近端存在感应距离", "Near Presence Distance"), color = Color.White, modifier = Modifier.weight(1f))
                Text(String.format("%.1f", range1), color = Color(0xFF9bb3d6))
            }
            Slider(value = range1, onValueChange = { if (!readOnly) range1 = it.coerceIn(0f, 6f) }, valueRange = 0f..6f, steps = 60, enabled = !readOnly)
        }
    }

    // 当进入等待状态时，清空累积的待解析值
    LaunchedEffect(awaitingReset) {
        if (awaitingReset) {
            resetStartTick = rawBleTick
            pendingHoldFrame = null
            pendingMr1Cm = null
            pendingMr2Cm = null
            pendingMr3Cm = null
            pendingRange1Cm = null
            pendingRange2Cm = null
            pendingRange3Cm = null
            pendingTrith = null
        }
    }

    LaunchedEffect(awaitingReset, rawBleTick) {
        if (!awaitingReset) return@LaunchedEffect

        // 将最近原始块拼接为连续文本；同时支持基于正则的令牌扫描，不依赖行边界
        val toRead = (rawBleTick - resetStartTick).coerceAtLeast(0)
        // 读取最后 toRead 条新增行，兼容 rawBle 列表裁剪
        val start = (rawBle.size - toRead).coerceAtLeast(0)
        val newLines = if (toRead > 0) rawBle.drop(start) else emptyList()
        val stream = newLines.joinToString(separator = "")

        fun findIntTokenLatest(pattern: Regex): Int? = pattern.findAll(stream).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
        fun findRangeCmTokenLatest(pattern: Regex): Int? {
            val m = pattern.findAll(stream).lastOrNull() ?: return null
            val num = m.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
            val unit = m.groupValues.getOrNull(2)?.lowercase()
            return when (unit) {
                "cm" -> num.roundToInt()
                "m", null, "" -> (num * 100f).roundToInt()
                else -> (num * 100f).roundToInt()
            }
        }

        val rxHold = Regex("(?i)\\bholdframe\\s*=\\s*(\\d+)")
        val rxTrith = Regex("(?i)\\btrith\\s*=\\s*(\\d+)")
        // 兼容返回格式：Range1/Range2/Range3、MRange1/MRange3、MR1/MR3
        val rxRange1 = Regex("(?i)\\brange1\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxRange2 = Regex("(?i)\\brange2\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxRange3 = Regex("(?i)\\brange3\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr1 = Regex("(?i)\\bmrange1\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr2 = Regex("(?i)\\bmrange2\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr3 = Regex("(?i)\\bmrange3\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr1Short = Regex("(?i)\\bmr1\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr2Short = Regex("(?i)\\bmr2\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr3Short = Regex("(?i)\\bmr3\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")

        // 令牌级解析（即使行被拆分也能匹配）
        val holdLatest = findIntTokenLatest(rxHold)
        val trithLatest = findIntTokenLatest(rxTrith)
        val range1Latest = findRangeCmTokenLatest(rxRange1)
        val range2Latest = findRangeCmTokenLatest(rxRange2)
        val range3Latest = findRangeCmTokenLatest(rxRange3)
        val mr1TokenLatest = findRangeCmTokenLatest(rxMr1Short) ?: findRangeCmTokenLatest(rxMr1)
        val mr2TokenLatest = findRangeCmTokenLatest(rxMr2Short) ?: findRangeCmTokenLatest(rxMr2)
        val mr3TokenLatest = findRangeCmTokenLatest(rxMr3Short) ?: findRangeCmTokenLatest(rxMr3)

        pendingHoldFrame = holdLatest ?: pendingHoldFrame
        pendingTrith = trithLatest ?: pendingTrith
        // 分开记录 Range 与 MR 令牌，避免混用导致错误计算
        pendingRange1Cm = range1Latest ?: pendingRange1Cm
        pendingRange2Cm = range2Latest ?: pendingRange2Cm
        pendingRange3Cm = range3Latest ?: pendingRange3Cm
        pendingMr1Cm = mr1TokenLatest ?: pendingMr1Cm
        pendingMr2Cm = mr2TokenLatest ?: pendingMr2Cm
        pendingMr3Cm = mr3TokenLatest ?: pendingMr3Cm

        var changed = false
        // 只要发现任意目标令牌，即认为已收到设备响应（即使值未变化）
        val ackFound = (holdLatest != null) || (trithLatest != null) ||
                (range1Latest != null) || (range2Latest != null) || (range3Latest != null) ||
                (mr1TokenLatest != null) || (mr2TokenLatest != null) || (mr3TokenLatest != null)
        // 单独更新：TRITH
        if (pendingTrith != null) {
            val sensitivityNew = pendingTrith!!.coerceIn(1, 5)
            if (sensitivity != sensitivityNew) { sensitivity = sensitivityNew; changed = true }
        }
        // 单独更新：HoldFrame
        if (pendingHoldFrame != null) {
            val hold_time = (pendingHoldFrame!! / 10)
            if (delaySec != hold_time) { delaySec = hold_time; changed = true }
        }
        // 更新六项距离的UI值（单位：米）
        if (pendingMr1Cm != null) { val v = (pendingMr1Cm!! / 100f).coerceIn(0f, 6f); if (mRange1 != v) { mRange1 = v; changed = true } }
        if (pendingMr2Cm != null) { val v = (pendingMr2Cm!! / 100f).coerceIn(0f, 6f); if (mRange2 != v) { mRange2 = v; changed = true } }
        if (pendingMr3Cm != null) { val v = (pendingMr3Cm!! / 100f).coerceIn(0f, 6f); if (mRange3 != v) { mRange3 = v; changed = true } }
        if (pendingRange1Cm != null) { val v = (pendingRange1Cm!! / 100f).coerceIn(0f, 6f); if (range1 != v) { range1 = v; changed = true } }
        if (pendingRange2Cm != null) { val v = (pendingRange2Cm!! / 100f).coerceIn(0f, 6f); if (range2 != v) { range2 = v; changed = true } }
        if (pendingRange3Cm != null) { val v = (pendingRange3Cm!! / 100f).coerceIn(0f, 6f); if (range3 != v) { range3 = v; changed = true } }

        // 已收到设备响应：保存更新并结束等待
        if (ackFound) {
            if (changed) { saveAll() }
            // 结束等待状态以恢复按钮样式
            awaitingReset = false
            onLogEvent(tr(lang, "收到设备响应，结束等待", "Received response; ending wait"))
        }
    }

    // 常态解析：每当有新增原始BLE数据时，解析 Range1/2/3 与 MR1/2/3 并更新六项与最小/最大距离
    LaunchedEffect(rawBleTick) {
        val toRead = (rawBleTick - lastRangeTick).coerceAtLeast(0)
        if (toRead <= 0) return@LaunchedEffect
        // 读取最后 toRead 条新增行，避免因列表裁剪导致 drop 超界
        val start = (rawBle.size - toRead).coerceAtLeast(0)
        val newLines = rawBle.drop(start)
        val stream = newLines.joinToString(separator = "")
        lastRangeTick = rawBleTick

        fun findRangeCmTokenLatest(pattern: Regex): Int? {
            val m = pattern.findAll(stream).lastOrNull() ?: return null
            val num = m.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
            val unit = m.groupValues.getOrNull(2)?.lowercase()
            return when (unit) {
                "cm" -> num.roundToInt()
                "m", null, "" -> (num * 100f).roundToInt()
                else -> (num * 100f).roundToInt()
            }
        }

        val rxRange1 = Regex("(?i)\\brange1\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxRange2 = Regex("(?i)\\brange2\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxRange3 = Regex("(?i)\\brange3\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr1 = Regex("(?i)\\bmrange1\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr2 = Regex("(?i)\\bmrange2\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr3 = Regex("(?i)\\bmrange3\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr1Short = Regex("(?i)\\bmr1\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr2Short = Regex("(?i)\\bmr2\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val rxMr3Short = Regex("(?i)\\bmr3\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|m)?")
        val r1Cm = findRangeCmTokenLatest(rxRange1)
        val r2Cm = findRangeCmTokenLatest(rxRange2)
        val r3Cm = findRangeCmTokenLatest(rxRange3)
        val mr1Cm = findRangeCmTokenLatest(rxMr1Short) ?: findRangeCmTokenLatest(rxMr1)
        val mr2Cm = findRangeCmTokenLatest(rxMr2Short) ?: findRangeCmTokenLatest(rxMr2)
        val mr3Cm = findRangeCmTokenLatest(rxMr3Short) ?: findRangeCmTokenLatest(rxMr3)

        var changed = false
        if (mr1Cm != null) { val v = (mr1Cm / 100f).coerceIn(0f, 6f); if (mRange1 != v) { mRange1 = v; changed = true } }
        if (mr2Cm != null) { val v = (mr2Cm / 100f).coerceIn(0f, 6f); if (mRange2 != v) { mRange2 = v; changed = true } }
        if (mr3Cm != null) { val v = (mr3Cm / 100f).coerceIn(0f, 6f); if (mRange3 != v) { mRange3 = v; changed = true } }
        if (r1Cm != null) { val v = (r1Cm / 100f).coerceIn(0f, 6f); if (range1 != v) { range1 = v; changed = true } }
        if (r2Cm != null) { val v = (r2Cm / 100f).coerceIn(0f, 6f); if (range2 != v) { range2 = v; changed = true } }
        if (r3Cm != null) { val v = (r3Cm / 100f).coerceIn(0f, 6f); if (range3 != v) { range3 = v; changed = true } }
        // 移除旧版 max/min 兼容逻辑
        if (changed) saveAll()
    }

    Spacer(Modifier.height(12.dp))
    // 灵敏度与延时
    SectionCard(title = tr(lang, "灵敏度与延时", "Sensitivity & Delay")) {
        Column(Modifier.fillMaxWidth().then(if (readOnly) Modifier.alpha(0.5f) else Modifier)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(lang, "触发灵敏度", "Trigger Sensitivity"), color = Color.White, modifier = Modifier.weight(1f))
            Text("${sensitivity}", color = Color(0xFF9bb3d6))
        }
        Slider(value = sensitivity.toFloat(), onValueChange = { if (!readOnly) sensitivity = it.toInt().coerceIn(1, 5) }, valueRange = 1f..5f, steps = 4, enabled = !readOnly)
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
        val resetSelected = awaitingReset
        if (resetSelected) {
            OutlinedButton(onClick = {
                if (!readOnly) {
                    // 保存到本地
                    saveAll()
                    // 计算并发送 AT 指令：基于六项距离（米）转厘米
                    val mr1 = kotlin.math.round(mRange1 * 100f).toInt()
                    val mr2 = kotlin.math.round(mRange2 * 100f).toInt()
                    val mr3 = kotlin.math.round(mRange3 * 100f).toInt()
                    val r1 = kotlin.math.round(range1 * 100f).toInt()
                    val r2 = kotlin.math.round(range2 * 100f).toInt()
                    val r3 = kotlin.math.round(range3 * 100f).toInt()
                    val hold = 10 * delaySec
                    val cmds = listOf(
                        "AT+MR1=${mr1}",
                        "AT+MR2=${mr2}",
                        "AT+MR3=${mr3}",
                        "AT+R1=${r1}",
                        "AT+R2=${r2}",
                        "AT+R3=${r3}",
                        "AT+TRITH=${sensitivity}",
                        "AT+HOLD=${hold}"
                    )
                    startHandshake(cmds)
                }
            }, enabled = !readOnly, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) { Text(tr(lang, "保存配置", "Save")) }
        } else {
            Button(onClick = {
                if (!readOnly) {
                    // 保存到本地
                    saveAll()
                    // 计算并发送 AT 指令：基于六项距离（米）转厘米
                    val mr1 = kotlin.math.round(mRange1 * 100f).toInt()
                    val mr2 = kotlin.math.round(mRange2 * 100f).toInt()
                    val mr3 = kotlin.math.round(mRange3 * 100f).toInt()
                    val r1 = kotlin.math.round(range1 * 100f).toInt()
                    val r2 = kotlin.math.round(range2 * 100f).toInt()
                    val r3 = kotlin.math.round(range3 * 100f).toInt()
                    val hold = 10 * delaySec
                    val cmds = listOf(
                        "AT+MR1=${mr1}",
                        "AT+MR2=${mr2}",
                        "AT+MR3=${mr3}",
                        "AT+R1=${r1}",
                        "AT+R2=${r2}",
                        "AT+R3=${r3}",
                        "AT+TRITH=${sensitivity}",
                        "AT+HOLD=${hold}"
                    )
                    startHandshake(cmds)
                }
            }, enabled = !readOnly, modifier = Modifier.weight(1f)) { Text(tr(lang, "保存配置", "Save")) }
        }
        if (resetSelected) {
            Button(
                onClick = { if (!readOnly) resetDefaults() },
                enabled = !readOnly,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text(tr(lang, "恢复默认", "Reset")) }
        } else {
            OutlinedButton(
                onClick = { if (!readOnly) resetDefaults() },
                enabled = !readOnly,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) { Text(tr(lang, "恢复默认", "Reset")) }
        }
    }
}

@Composable
fun LogsContent(eventLogs: List<String>, rawBle: List<String>, lang: String) {
    val context = LocalContext.current
    val limit = try { context.getSharedPreferences("radarlink", Context.MODE_PRIVATE).getInt("log_limit", 20) } catch (_: Exception) { 20 }
    val eventState = rememberLazyListState(); val rawState = rememberLazyListState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(tr(lang, "事件日志(${limit})", "Event Logs(${limit})"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
            LazyColumn(state = eventState, modifier = Modifier.height(140.dp)) { items(eventLogs.take(limit)) { Text(it, color = Color.White) } }
        }
        Column(Modifier.weight(1f)) {
            Text(tr(lang, "原始日志(${limit})", "Raw Logs(${limit})"), color = Color(0xFF9bb3d6), fontSize = 12.sp)
            val filtered = rawBle.filter { it.trim().isNotEmpty() }
            LazyColumn(state = rawState, modifier = Modifier.height(140.dp)) { items(filtered.take(limit)) { Text(it.trim(), color = Color.White) } }
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
            if (show.isNotEmpty()) {
                // 以可见区域内的第一个有效事件作为 0s 起点
                val startT = show.firstOrNull { it.meters != null }?.t ?: show.first().t
                val windowMs = secondsWindow * 1000f

                // 分段：遇到 null 断开，避免跨越缺失值
                var segment = mutableListOf<Offset>()
                fun flushSegment() {
                    if (segment.isEmpty()) return
                    if (segment.size == 1) {
                        drawCircle(color = Color(0xFF2d7bf3), radius = 4f, center = segment[0])
                        segment.clear(); return
                    }
                    // 使用二次贝塞尔的中点法绘制平滑曲线
                    val path = Path()
                    path.moveTo(segment[0].x, segment[0].y)
                    for (i in 1 until segment.size) {
                        val prev = segment[i - 1]
                        val curr = segment[i]
                        val mid = Offset((prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
                        path.quadraticBezierTo(prev.x, prev.y, mid.x, mid.y)
                    }
                    path.lineTo(segment.last().x, segment.last().y)
                    drawPath(path = path, color = Color(0xFF2d7bf3), style = Stroke(width = 3f))
                    // 每个点绘制标记
                    segment.forEach { pt -> drawCircle(color = Color(0xFF2d7bf3), radius = 4f, center = pt) }
                    segment.clear()
                }

                show.forEach { p ->
                    val m = p.meters
                    val x = (((p.t - startT).coerceAtLeast(0L)).toFloat() / windowMs).coerceIn(0f, 1f) * w
                    if (m != null) {
                        val mc = m.coerceIn(minD, maxD)
                        val y = h * (maxD - mc) / range
                        segment.add(Offset(x, y))
                    } else {
                        flushSegment()
                    }
                }
                flushSegment()
            }
        }
    }
    // X轴刻度（时间）：将 0s 起点相对左侧距离刻度线右移（44dp 刻度列 + 6dp 间隔 + 8dp 额外偏移）
    Row(Modifier.fillMaxWidth().padding(start = 58.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        val tick = (secondsWindow / 3).coerceAtLeast(1)
        listOf(0, tick, tick * 2, secondsWindow).forEach { Text("${it}s", color = Color(0xFF9bb3d6), fontSize = 10.sp) }
    }
}