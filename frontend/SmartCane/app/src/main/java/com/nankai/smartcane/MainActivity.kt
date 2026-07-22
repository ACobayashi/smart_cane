package com.nankai.smartcane

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.PolylineOptions
import com.nankai.smartcane.data.model.CareRelation
import com.nankai.smartcane.data.network.AiAdviceDto
import com.nankai.smartcane.data.network.AiAdviceRequestDto
import com.nankai.smartcane.data.network.ApiResult
import com.nankai.smartcane.data.network.CollaborationOverviewDto
import com.nankai.smartcane.data.network.DeviceDto
import com.nankai.smartcane.data.network.DeviceStateDto
import com.nankai.smartcane.data.network.LatestRiskEventDto
import com.nankai.smartcane.data.network.ServerStatusDto
import com.nankai.smartcane.data.network.SmartCaneApiClient
import com.nankai.smartcane.data.network.SosRequestDto
import com.nankai.smartcane.navigation.SmartCaneRootApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SmartCaneRootApp() }
    }
}

data class RiskEvent(
    val id: Int,
    val deviceId: String,
    val riskType: String,
    val level: String,
    val direction: String,
    val distanceMm: Int?,
    val aiMessage: String,
    val time: String
)

private const val DEMO_CENTER_LAT = 31.2304
private const val DEMO_CENTER_LNG = 121.4737

private data class MapRiskMarker(
    val position: LatLng,
    val title: String,
    val snippet: String,
    val hue: Float
)

private val demoEvents = listOf(
    RiskEvent(38, "cane_003", "front_obstacle", "low", "front", 1285, "前方约 128 厘米有障碍物，请减速并准备绕行。", "14:30:35"),
    RiskEvent(37, "cane_002", "left_obstacle", "high", "left", 422, "左侧约 42 厘米有障碍物，请向右侧保持距离。", "14:30:33"),
    RiskEvent(36, "cane_003", "right_obstacle", "medium", "right", 1196, "右侧约 119 厘米有障碍物，请向左侧保持距离。", "14:30:31"),
    RiskEvent(35, "cane_001", "ground_drop", "high", "down", 1200, "前方地面高度变化明显，可能有台阶或坑洼，请停止前进并用盲杖确认。", "14:30:29")
)

private sealed interface LatestEventsUiState {
    data object Loading : LatestEventsUiState
    data class Success(val events: List<RiskEvent>) : LatestEventsUiState
    data class Error(val message: String) : LatestEventsUiState
}

private sealed interface DeviceStatusUiState {
    data object Loading : DeviceStatusUiState
    data class Success(val status: ServerStatusDto, val devices: List<DeviceDto>) : DeviceStatusUiState
    data class Error(val message: String) : DeviceStatusUiState
}

private sealed interface MapRiskUiState {
    data object Loading : MapRiskUiState
    data class Success(val points: List<LatestRiskEventDto>) : MapRiskUiState
    data class Error(val message: String) : MapRiskUiState
}

private data class CurrentRiskCardData(
    val level: String,
    val adviceText: String,
    val sourceText: String,
    val tagText: String
)

private sealed interface CurrentRiskUiState {
    data object Loading : CurrentRiskUiState
    data class Success(val data: CurrentRiskCardData) : CurrentRiskUiState
    data class Empty(val message: String) : CurrentRiskUiState
    data class Error(val message: String) : CurrentRiskUiState
}

private sealed interface DeviceRealtimeUiState {
    data object Loading : DeviceRealtimeUiState
    data class Success(val state: DeviceStateDto?, val overview: CollaborationOverviewDto?) : DeviceRealtimeUiState
    data class Error(val message: String) : DeviceRealtimeUiState
}

private sealed interface SosUiState {
    data object Idle : SosUiState
    data object Sending : SosUiState
    data class Success(val message: String, val sentAt: String) : SosUiState
    data class Error(val message: String) : SosUiState
}

private fun LatestRiskEventDto.toRiskEvent(): RiskEvent = RiskEvent(
    id = id,
    deviceId = deviceId,
    riskType = riskType,
    level = riskLevel,
    direction = directionFromRiskType(riskType),
    distanceMm = distance,
    aiMessage = message,
    time = displayTimestamp(timestamp)
)

private fun directionFromRiskType(riskType: String): String = when {
    riskType.contains("left", ignoreCase = true) -> "left"
    riskType.contains("right", ignoreCase = true) -> "right"
    riskType.contains("drop", ignoreCase = true) || riskType.contains("down", ignoreCase = true) -> "down"
    else -> "front"
}

private fun displayTimestamp(timestamp: String): String = runCatching {
    timestamp.takeIf { it.length >= 16 }?.substring(11, 16) ?: timestamp
}.getOrDefault(timestamp.ifBlank { currentTimeText() })

private fun currentTimeText(): String = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())

private fun LatestRiskEventDto.toAiAdviceRequest(): AiAdviceRequestDto {
    val distanceMm = distance?.coerceAtLeast(0)
    return AiAdviceRequestDto(
        deviceId = deviceId,
        latitude = latitude,
        longitude = longitude,
        riskType = riskType,
        level = riskLevel,
        frontMm = if (directionFromRiskType(riskType) == "front") distanceMm else null,
        leftMm = if (directionFromRiskType(riskType) == "left") distanceMm else null,
        rightMm = if (directionFromRiskType(riskType) == "right") distanceMm else null,
        downMm = if (directionFromRiskType(riskType) == "down") distanceMm else null
    )
}

private fun directionLabel(direction: String): String = when (direction) {
    "left" -> "左侧"
    "right" -> "右侧"
    "down" -> "下方"
    else -> "前方"
}

private fun riskLevelHeadline(level: String): String = when (level.lowercase(Locale.US)) {
    "high" -> "高风险"
    "medium" -> "中风险"
    else -> "低风险"
}

private fun LatestRiskEventDto.primaryTagText(): String {
    val direction = directionLabel(directionFromRiskType(riskType))
    return distance?.let { "$direction ${it / 10} cm" } ?: riskTypeLabel(riskType)
}

private fun LatestRiskEventDto.toMapMarker(context: Context): MapRiskMarker? {
    val lat = latitude ?: return null
    val lng = longitude ?: return null
    val level = riskLevel.lowercase(Locale.US)
    val messageText = voicePrompt.ifBlank { message }.ifBlank { "风险点" }
    return MapRiskMarker(
        position = convertGpsToAmap(context, LatLng(lat, lng)),
        title = "${riskLevelLabel(level)}：${riskTypeLabel(riskType)}",
        snippet = "\u6765\u6e90\u8bbe\u5907 $deviceId\n$messageText",
        hue = riskLevelHue(level)
    )
}

private fun convertGpsToAmap(context: Context, point: LatLng): LatLng =
    CoordinateConverter(context)
        .from(CoordinateConverter.CoordType.GPS)
        .coord(point)
        .convert()

private fun riskLevelLabel(level: String): String = when (level.lowercase(Locale.US)) {
    "high" -> "高风险"
    "medium" -> "中风险"
    else -> "低风险"
}

private fun riskLevelHue(level: String): Float = when (level.lowercase(Locale.US)) {
    "high" -> BitmapDescriptorFactory.HUE_RED
    "medium" -> BitmapDescriptorFactory.HUE_ORANGE
    else -> BitmapDescriptorFactory.HUE_GREEN
}

private fun riskTypeLabel(type: String): String = when {
    type.contains("green", ignoreCase = true) -> "绿色通道"
    type.contains("rough", ignoreCase = true) -> "崎岖路段"
    type.contains("drop", ignoreCase = true) || type.contains("down", ignoreCase = true) -> "落差"
    type.contains("verified", ignoreCase = true) -> "已验证点位"
    type.contains("obstacle", ignoreCase = true) -> "障碍物"
    else -> "风险点"
}

private fun riskLevelColor(level: String): Color = when (level.lowercase(Locale.US)) {
    "high" -> Color(0xFFDC2626)
    "medium" -> Color(0xFFF59E0B)
    else -> Color(0xFF16A34A)
}

private fun shouldUseNativeAmap(): Boolean = Build.VERSION.SDK_INT < 36

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
fun SmartCaneApp() {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0F766E),
            secondary = Color(0xFF14B8A6),
            background = Color(0xFFEEF6F7),
            surface = Color.White
        )
    ) {
        var selectedTab by remember { mutableIntStateOf(0) }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = { BottomNavigationBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it }) }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFEEF6F7))
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    0 -> GuidePage()
                    1 -> MapPage()
                    2 -> CollaborationPage()
                    3 -> SosPage()
                    4 -> MinePage()
                }
            }
        }
    }
}

@Composable
fun AppHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title。$subtitle" }
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A), lineHeight = 32.sp)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, fontSize = 14.sp, color = Color(0xFF64748B), lineHeight = 20.sp)
    }
}

@Composable
fun GuidePage(deviceName: String? = null) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        item { AppHeader("实时", "设备上报与云端风险建议") }
        item { CurrentRiskCard() }
        item { RealtimeDevicePanel(deviceName = deviceName) }
        item { LatestRiskFromServer() }
    }
}

@Composable
fun LatestRiskFromServer() {
    var retryKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LatestEventsUiState>(LatestEventsUiState.Loading) }

    LaunchedEffect(retryKey) {
        state = LatestEventsUiState.Loading
        state = when (val result = SmartCaneApiClient.getLatestEvents()) {
            is ApiResult.Success -> LatestEventsUiState.Success(result.data.map { it.toRiskEvent() })
            is ApiResult.Failure -> LatestEventsUiState.Error(result.message)
        }
    }

    when (val current = state) {
        LatestEventsUiState.Loading -> StateCard("最新风险提醒", "正在从后端加载最新风险数据…")
        is LatestEventsUiState.Success -> {
            if (current.events.isEmpty()) {
                StateCard("最新风险提醒", "后端已连接，暂未收到设备上报的风险事件。")
            } else {
                LatestRiskList("最新风险提醒", current.events)
            }
        }
        is LatestEventsUiState.Error -> StateCard("最新风险提醒", current.message, showRetry = true, onRetry = { retryKey++ })
    }
}

@Composable
fun StateCard(title: String, message: String, showRetry: Boolean = false, onRetry: () -> Unit = {}) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Text(message, fontSize = 15.sp, color = Color(0xFF64748B), lineHeight = 22.sp)
            if (showRetry) OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试") }
        }
    }
}

@Composable
fun RealtimeDevicePanel(deviceName: String? = null) {
    var retryKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DeviceRealtimeUiState>(DeviceRealtimeUiState.Loading) }

    LaunchedEffect(retryKey) {
        while (true) {
            val deviceResult = SmartCaneApiClient.getLatestDeviceState()
            val overviewResult = SmartCaneApiClient.getCollaborationOverview()
            state = when {
                deviceResult is ApiResult.Success -> DeviceRealtimeUiState.Success(
                    state = deviceResult.data.state,
                    overview = (overviewResult as? ApiResult.Success)?.data
                )
                deviceResult is ApiResult.Failure -> DeviceRealtimeUiState.Error(deviceResult.message)
                else -> DeviceRealtimeUiState.Error("实时设备数据加载失败")
            }
            delay(3_000L)
        }
    }

    when (val current = state) {
        DeviceRealtimeUiState.Loading -> StateCard("实时设备", "正在加载设备上报的实时距离数据…")
        is DeviceRealtimeUiState.Error -> StateCard("实时设备", current.message, showRetry = true, onRetry = { retryKey++ })
        is DeviceRealtimeUiState.Success -> RealtimeDeviceDataCard(current.state, deviceName)
    }
}

@Composable
private fun RealtimeDeviceDataCard(state: DeviceStateDto?, deviceName: String? = null) {
    val displayName = deviceName?.takeIf { it.isNotBlank() }
        ?: state?.deviceName?.takeIf { it.isNotBlank() }
        ?: state?.deviceId?.takeIf { it.isNotBlank() }

    if (displayName == null) {
        StateCard("\u5b9e\u65f6\u8bbe\u5907", "\u6682\u65e0\u8bbe\u5907\u5b9e\u65f6\u8ddd\u79bb\u6570\u636e\u3002\u8bbe\u5907\u4e0a\u62a5\u4f20\u611f\u5668\u5e27\u540e\u4f1a\u5728\u8fd9\u91cc\u663e\u793a\u3002")
        return
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "\u5f53\u524d\u8bbe\u5907\uff0c$displayName" },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("\u5f53\u524d\u8bbe\u5907", color = Color(0xFF64748B), fontSize = 15.sp)
            Text(displayName, color = Color(0xFF0F172A), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private fun distanceColor(kind: String, value: Int?): Color = when {
    value == null -> Color(0xFF94A3B8)
    kind == "front" && value < 60 -> Color(0xFFDC2626)
    kind == "front" && value < 120 -> Color(0xFFF59E0B)
    kind == "side" && value < 55 -> Color(0xFFDC2626)
    kind == "side" && value < 90 -> Color(0xFFF59E0B)
    else -> Color(0xFF16A34A)
}

@Composable
fun CurrentRiskCard() {
    var retryKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<CurrentRiskUiState>(CurrentRiskUiState.Loading) }

    LaunchedEffect(retryKey) {
        state = CurrentRiskUiState.Loading
        state = when (val eventResult = SmartCaneApiClient.getLatestEvents()) {
            is ApiResult.Failure -> CurrentRiskUiState.Error(eventResult.message)
            is ApiResult.Success -> {
                val latestEvent = eventResult.data.firstOrNull()
                if (latestEvent == null) {
                    CurrentRiskUiState.Empty("正在等待设备上报新的风险事件。")
                } else {
                    when (val adviceResult = SmartCaneApiClient.postAiAdvice(latestEvent.toAiAdviceRequest())) {
                        is ApiResult.Success -> CurrentRiskUiState.Success(
                            CurrentRiskCardData(
                                level = latestEvent.riskLevel,
                                adviceText = adviceResult.data.advice.ifBlank {
                                    latestEvent.voicePrompt.ifBlank { latestEvent.message }
                                },
                                sourceText = if (adviceResult.data.fallback) "安全建议" else "智能建议",
                                tagText = latestEvent.primaryTagText()
                            )
                        )
                        is ApiResult.Failure -> CurrentRiskUiState.Success(
                            CurrentRiskCardData(
                                level = latestEvent.riskLevel,
                                adviceText = latestEvent.voicePrompt.ifBlank { latestEvent.message }.ifBlank { "已收到设备风险事件。" },
                                sourceText = "设备已上报",
                                tagText = latestEvent.primaryTagText()
                            )
                        )
                    }
                }
            }
        }
    }

    val levelText = when (val current = state) {
        CurrentRiskUiState.Loading -> "加载中"
        is CurrentRiskUiState.Success -> riskLevelHeadline(current.data.level)
        is CurrentRiskUiState.Empty -> "暂无风险"
        is CurrentRiskUiState.Error -> "连接异常"
    }
    val levelColor = when (val current = state) {
        is CurrentRiskUiState.Success -> riskLevelColor(current.data.level)
        is CurrentRiskUiState.Empty -> Color(0xFF0F766E)
        else -> Color(0xFF64748B)
    }
    val adviceText = when (val current = state) {
        CurrentRiskUiState.Loading -> "正在获取最新风险与安全建议…"
        is CurrentRiskUiState.Success -> current.data.adviceText
        is CurrentRiskUiState.Empty -> current.message
        is CurrentRiskUiState.Error -> current.message
    }
    val sourceText = when (val current = state) {
        CurrentRiskUiState.Loading -> "正在加载"
        is CurrentRiskUiState.Success -> current.data.sourceText
        is CurrentRiskUiState.Empty -> "等待设备上报"
        is CurrentRiskUiState.Error -> "连接异常"
    }
    val tagText = (state as? CurrentRiskUiState.Success)?.data?.tagText

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "当前风险，$levelText，$adviceText" },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(Modifier.padding(22.dp)) {
            Text("当前风险", color = Color(0xFF64748B), fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Text(levelText, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = levelColor)
            Spacer(Modifier.height(10.dp))
            Text(adviceText, fontSize = 17.sp, lineHeight = 26.sp, color = Color(0xFF1E293B))
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusTag(sourceText)
                if (tagText != null) StatusTag(tagText)
                if (state is CurrentRiskUiState.Error) {
                    OutlinedButton(onClick = { retryKey++ }, modifier = Modifier.weight(1f)) { Text("重试") }
                }
            }
        }
    }
}

@Composable
fun StatusTag(text: String) {
    Surface(color = Color(0xFFFEE2E2), shape = RoundedCornerShape(999.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Color(0xFF991B1B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DistanceGrid() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DistanceCard("前方", "128 cm", Color(0xFF0F766E), Modifier.weight(1f))
        DistanceCard("左侧", "42 cm", Color(0xFFDC2626), Modifier.weight(1f))
        DistanceCard("右侧", "119 cm", Color(0xFFF59E0B), Modifier.weight(1f))
    }
}

@Composable
fun DistanceCard(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$title 距离 $value" }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color(0xFF64748B), fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DeviceStatusFromServer() {
    var retryKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DeviceStatusUiState>(DeviceStatusUiState.Loading) }

    LaunchedEffect(retryKey) {
        state = DeviceStatusUiState.Loading
        val statusResult = SmartCaneApiClient.getStatus()
        val devicesResult = SmartCaneApiClient.getDevices()
        state = if (statusResult is ApiResult.Success && devicesResult is ApiResult.Success) {
            DeviceStatusUiState.Success(statusResult.data, devicesResult.data)
        } else {
            DeviceStatusUiState.Error(
                (statusResult as? ApiResult.Failure)?.message
                    ?: (devicesResult as? ApiResult.Failure)?.message
                    ?: "设备状态加载失败。"
            )
        }
    }

    when (val current = state) {
        DeviceStatusUiState.Loading -> StateCard("设备状态", "正在从后端加载设备状态…")
        is DeviceStatusUiState.Success -> DeviceStatusDataCard(current.status, current.devices)
        is DeviceStatusUiState.Error -> StateCard("设备状态", current.message, showRetry = true, onRetry = { retryKey++ })
    }
}

@Composable
fun DeviceStatusDataCard(status: ServerStatusDto, devices: List<DeviceDto>) {
    val total = devices.size.coerceAtLeast(status.deviceCount)
    val online = devices.count { it.online }
    val primary = devices.firstOrNull { it.deviceId == "cane_001" } ?: devices.firstOrNull()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "设备状态，后端${if (status.online) "在线" else "离线"}，在线设备 $online 台，共 $total 台。" },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("设备状态", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            StatusRow("后端状态", if (status.online) "在线" else "离线", if (status.online) Color(0xFF16A34A) else Color(0xFFDC2626))
            StatusRow("服务提示", status.message.ifBlank { "-" })
            StatusRow("已连接设备", "$online / $total 台在线")
            StatusRow("主要设备", primary?.name?.ifBlank { primary.deviceId } ?: "暂无设备")
            StatusRow("最后在线", primary?.lastSeen?.let(::displayTimestamp) ?: "-")
        }
    }
}

@Composable
fun StatusRow(label: String, value: String, valueColor: Color = Color(0xFF0F172A)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = Color(0xFF64748B), fontSize = 15.sp, modifier = Modifier.weight(1f), lineHeight = 21.sp)
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1.3f), lineHeight = 21.sp)
    }
}

@Composable
fun MapPage() {
    val context = LocalContext.current
    var retryKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<MapRiskUiState>(MapRiskUiState.Loading) }
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var sheetExpanded by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        locationGranted = grants.values.any { it } || hasLocationPermission(context)
    }

    LaunchedEffect(retryKey) {
        state = MapRiskUiState.Loading
        while (true) {
            state = when (val result = SmartCaneApiClient.getMapRiskPoints()) {
                is ApiResult.Success -> MapRiskUiState.Success(result.data)
                is ApiResult.Failure -> MapRiskUiState.Error(result.message)
            }
            delay(5_000L)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission(context)) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            locationGranted = true
        }
    }

    val points = (state as? MapRiskUiState.Success)?.points.orEmpty()
    val events = points.map { it.toRiskEvent() }
    val highCount = points.count { it.riskLevel.equals("high", ignoreCase = true) }
    val mediumCount = points.count { it.riskLevel.equals("medium", ignoreCase = true) }
    val sourceDeviceCount = points.map { it.deviceId }.filter { it.isNotBlank() }.distinct().size

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFEEF6F7))) {
        val panelExpandedHeight = maxHeight * 0.55f
        Box(Modifier.fillMaxSize()) {
            RiskMapViewport(
                points = points,
                showMyLocation = locationGranted,
                modifier = Modifier.fillMaxSize()
            )

            MapTopBar(
                pointCount = points.size,
                highCount = highCount,
                mediumCount = mediumCount,
                sourceDeviceCount = sourceDeviceCount,
                locationGranted = locationGranted,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            MapStatusOverlay(
                state = state,
                onRetry = { retryKey++ },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 92.dp)
            )

            NearbyRiskBottomSheet(
                events = events,
                expanded = sheetExpanded,
                expandedHeight = panelExpandedHeight,
                onExpandedChange = { sheetExpanded = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun RiskMapViewport(points: List<LatestRiskEventDto>, showMyLocation: Boolean, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (shouldUseNativeAmap()) {
            AmapRiskMap(points = points, showMyLocation = showMyLocation, modifier = Modifier.fillMaxSize())
        } else {
            CompatibleRiskMap(points = points, showMyLocation = showMyLocation, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MapTopBar(pointCount: Int, highCount: Int, mediumCount: Int, sourceDeviceCount: Int, locationGranted: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        color = Color.White.copy(alpha = 0.94f),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("风险地图", color = Color(0xFF0F172A), fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text("高德地图 · $pointCount 个附近风险点", color = Color(0xFF64748B), fontSize = 13.sp, maxLines = 1)
            }
            Surface(color = if (locationGranted) Color(0xFFDCFCE7) else Color(0xFFFFF7ED), shape = RoundedCornerShape(999.dp)) {
                Text(
                    if (locationGranted) "已授权" else "待授权",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = if (locationGranted) Color(0xFF166534) else Color(0xFFC2410C),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MapStatusOverlay(state: MapRiskUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        MapRiskUiState.Loading -> Surface(modifier = modifier, color = Color.White.copy(alpha = 0.92f), shape = RoundedCornerShape(14.dp)) {
            Text("正在加载风险点", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color(0xFF0F172A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        is MapRiskUiState.Error -> Column(
            modifier = modifier.background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(16.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(state.message, color = Color(0xFFB91C1C), fontSize = 13.sp, lineHeight = 18.sp)
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
        is MapRiskUiState.Success -> Unit
    }
}

@Composable
private fun NearbyRiskBottomSheet(
    events: List<RiskEvent>,
    expanded: Boolean,
    expandedHeight: androidx.compose.ui.unit.Dp,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetHeight = if (expanded) expandedHeight else 104.dp
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = Color.White.copy(alpha = 0.97f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -8f) onExpandedChange(true)
                        if (dragAmount > 8f) onExpandedChange(false)
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(modifier = Modifier.size(width = 42.dp, height = 5.dp), color = Color(0xFFCBD5E1), shape = RoundedCornerShape(999.dp)) {}
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("附近风险点", color = Color(0xFF0F172A), fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        Text(
                            if (expanded) {
                                "下滑收起"
                            } else if (events.isEmpty()) {
                                "等待设备上报附近风险点"
                            } else {
                                "上拉查看半屏列表"
                            },
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                    Surface(color = Color(0xFFE0F2F1), shape = RoundedCornerShape(999.dp)) {
                        Text("${events.size}", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFF0F766E), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (expanded) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (events.isEmpty()) {
                        item {
                            Text(
                                "暂未收到附近风险点上报。",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                color = Color(0xFF64748B),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        items(events) { event -> CompactRiskEventItem(event) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactRiskEventItem(event: RiskEvent) {
    val levelText = when (event.level.lowercase()) {
        "high" -> "高"
        "medium" -> "中"
        else -> "低"
    }
    val levelColor = when (event.level.lowercase()) {
        "high" -> Color(0xFFDC2626)
        "medium" -> Color(0xFFF59E0B)
        else -> Color(0xFF16A34A)
    }
    Row(Modifier.fillMaxWidth().background(Color(0xFFF8FAFC), RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.Top) {
        Surface(color = levelColor.copy(alpha = 0.14f), shape = RoundedCornerShape(10.dp)) {
            Text(levelText, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp), color = levelColor, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(event.aiMessage, color = Color(0xFF1E293B), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("${event.deviceId} · ${event.time}", color = Color(0xFF94A3B8), fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CompatibleRiskMap(points: List<LatestRiskEventDto>, showMyLocation: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color(0xFFEAF4FF), RoundedCornerShape(22.dp))) {
        Canvas(Modifier.fillMaxSize().padding(20.dp)) {
            val w = size.width
            val h = size.height
            val roadColor = Color(0xFFD8E5F2)
            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(roadColor, Offset(0f, y), Offset(w, y), strokeWidth = 5f, cap = StrokeCap.Round)
            }
            for (i in 1..2) {
                val x = w * i / 3f
                drawLine(roadColor, Offset(x, 0f), Offset(x, h), strokeWidth = 5f, cap = StrokeCap.Round)
            }

            val route = Path().apply {
                moveTo(w * 0.18f, h * 0.78f)
                cubicTo(w * 0.30f, h * 0.66f, w * 0.38f, h * 0.54f, w * 0.50f, h * 0.50f)
                cubicTo(w * 0.64f, h * 0.45f, w * 0.72f, h * 0.30f, w * 0.86f, h * 0.22f)
            }
            drawPath(route, Color(0xFF2563EB), style = Stroke(width = 12f, cap = StrokeCap.Round))

            val visible = points.take(8)
            visible.forEachIndexed { index, point ->
                val baseX = w * (0.24f + 0.08f * (index % 4))
                val baseY = h * (0.70f - 0.12f * (index / 2))
                val center = Offset(baseX.coerceIn(26f, w - 26f), baseY.coerceIn(26f, h - 26f))
                val color = riskLevelColor(point.riskLevel)
                drawCircle(color.copy(alpha = 0.16f), radius = 30f, center = center)
                drawCircle(color, radius = 16f, center = center)
            }
            if (showMyLocation) {
                val center = Offset(w * 0.18f, h * 0.78f)
                drawCircle(Color(0xFF2563EB).copy(alpha = 0.18f), radius = 34f, center = center)
                drawCircle(Color.White, radius = 18f, center = center)
                drawCircle(Color(0xFF2563EB), radius = 12f, center = center)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("南开大学图书馆附近", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("风险点来自设备与后端实时上报", color = Color(0xFF64748B), fontSize = 13.sp)
        }
    }
}

@Composable
private fun AmapRiskMap(points: List<LatestRiskEventDto>, showMyLocation: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        TextureMapView(context).apply { onCreate(Bundle()) }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            val markers = points.mapNotNull { it.toMapMarker(context) }
            val fallbackCenter = convertGpsToAmap(context, LatLng(DEMO_CENTER_LAT, DEMO_CENTER_LNG))
            val center = markers.firstOrNull()?.position ?: fallbackCenter
            val amap = view.map
            amap.uiSettings.isZoomControlsEnabled = false
            amap.uiSettings.isCompassEnabled = true
            amap.uiSettings.isScaleControlsEnabled = true
            amap.myLocationStyle = MyLocationStyle()
                .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
                .strokeColor(android.graphics.Color.argb(80, 37, 99, 235))
                .radiusFillColor(android.graphics.Color.argb(35, 37, 99, 235))
                .strokeWidth(2f)
            amap.isMyLocationEnabled = showMyLocation
            amap.clear()
            amap.moveCamera(CameraUpdateFactory.newLatLngZoom(center, if (markers.isEmpty()) 16f else 17f))
            if (markers.size >= 2) {
                amap.addPolyline(
                    PolylineOptions()
                        .addAll(markers.map { it.position })
                        .color(android.graphics.Color.rgb(37, 99, 235))
                        .width(8f)
                )
            }
            markers.forEach { marker ->
                amap.addMarker(
                    MarkerOptions()
                        .position(marker.position)
                        .title(marker.title)
                        .snippet(marker.snippet)
                        .icon(BitmapDescriptorFactory.defaultMarker(marker.hue))
                )
            }
        }
    )
}

@Composable
fun RouteLegend(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Surface(modifier = Modifier.size(10.dp), color = color, shape = RoundedCornerShape(999.dp)) {}
        Text(text, modifier = Modifier.padding(start = 6.dp), color = Color(0xFF64748B), fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun MapPlaceholder() {
    MapPage()
}

@Composable
fun MapDot(level: String, modifier: Modifier = Modifier) {
    val color = when (level) {
        "high" -> Color(0xFFDC2626)
        "medium" -> Color(0xFFF59E0B)
        else -> Color(0xFF16A34A)
    }
    Surface(modifier = modifier.size(18.dp), color = color, shape = RoundedCornerShape(999.dp)) {}
}

@Composable
fun CollaborationPage() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { AppHeader("世界", "设备与环境动态") }
        item { DeviceStatusFromServer() }
        item { LatestRiskFromServer() }
    }
}

@Composable
fun StatisticsGrid() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("累计事件", "38", "条", Modifier.weight(1f))
        StatCard("在线设备", "3", "台", Modifier.weight(1f))
        StatCard("高风险", "5", "条", Modifier.weight(1f))
    }
}

@Composable
fun StatCard(title: String, number: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color(0xFF64748B), fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(number, color = Color(0xFF0F766E), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = Color(0xFF64748B), fontSize = 12.sp)
        }
    }
}

@Composable
fun DeviceListCard() {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp)) {
            Text("设备列表", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Spacer(Modifier.height(10.dp))
            DeviceRow("盲杖一号", "在线", "已上传风险提醒")
            DeviceRow("盲杖二号", "在线", "已上传风险提醒")
            DeviceRow("盲杖三号", "在线", "已上传风险提醒")
        }
    }
}

@Composable
fun DeviceRow(id: String, status: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) { contentDescription = "$id，$status，$desc" }, verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color(0xFFE0F2FE), shape = RoundedCornerShape(12.dp)) {
            Text("杖", modifier = Modifier.padding(10.dp), color = Color(0xFF0369A1), fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(id, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Text(desc, color = Color(0xFF64748B), fontSize = 13.sp)
        }
        Text(status, color = Color(0xFF16A34A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun SosPage() {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SosUiState>(SosUiState.Idle) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    fun sendSos() {
        if (state == SosUiState.Sending) return
        scope.launch {
            state = SosUiState.Sending
            state = when (val result = SmartCaneApiClient.postSos(SosRequestDto("cane_001", null, null, "用户通过 Android App 发起紧急求助（位置不可用）"))) {
                is ApiResult.Success -> if (result.data.success) {
                    SosUiState.Success(result.data.message.ifBlank { "SOS 已发送" }, currentTimeText())
                } else {
                    SosUiState.Error(result.data.message.ifBlank { "后端返回 SOS 发送失败" })
                }
                is ApiResult.Failure -> SosUiState.Error(result.message)
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item { AppHeader("SOS 紧急求助", "二次确认，避免误触") }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("紧急求助", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                    Text("点击 SOS 后需要二次确认。确认后会尝试向现有后端发送演示求助信息。", textAlign = TextAlign.Center, color = Color(0xFF475569), fontSize = 16.sp, lineHeight = 24.sp)
                    Button(onClick = { showConfirmDialog = true }, enabled = state != SosUiState.Sending, modifier = Modifier.fillMaxWidth().height(68.dp)) {
                        Text(if (state == SosUiState.Sending) "发送中…" else "发送 SOS", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    SosStatusContent(state)
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("确认发送 SOS？") },
            text = { Text("确认后将向后端发送紧急求助演示信息。") },
            confirmButton = { TextButton(onClick = { showConfirmDialog = false; sendSos() }) { Text("确认发送") } },
            dismissButton = { TextButton(onClick = { showConfirmDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SosStatusContent(state: SosUiState) {
    when (state) {
        SosUiState.Idle -> Text("尚未发送 SOS。", color = Color(0xFF64748B))
        SosUiState.Sending -> Text("正在发送 SOS，请稍候…", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
        is SosUiState.Success -> Text("${state.message}\n发送时间：${state.sentAt}", color = Color(0xFF166534), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        is SosUiState.Error -> Text("发送失败：${state.message}", color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
fun MinePage(
    userName: String = "\u966a\u62a4\u4eba",
    relation: CareRelation? = null,
    onSwitchToBlind: () -> Unit = {},
    onAddCareTarget: () -> Unit = {},
    onViewCareStatus: () -> Unit = {},
    onUnlink: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    var showUnlinkConfirm by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { AppHeader("我的", "账号与设备") }
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("当前账号", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                    StatusRow("用户名", userName)
                    Button(onClick = onAddCareTarget, modifier = Modifier.fillMaxWidth()) { Text("\u8f93\u5165\u9080\u8bf7\u7801\u6dfb\u52a0\u7528\u6237") }
                    OutlinedButton(onClick = onSwitchToBlind, modifier = Modifier.fillMaxWidth()) { Text("切换到用户模式") }
                    Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
                }
            }
        }
        item {
            relation?.let { currentRelation ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("\u5f53\u524d\u966a\u62a4\u5bf9\u8c61", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        StatusRow("\u76f2\u4eba\u7528\u6237", currentRelation.blindUser.displayName)
                        StatusRow("\u76f2\u6756", currentRelation.caneDevice.name)
                        StatusRow("\u5173\u8054\u72b6\u6001", currentRelation.status.displayName)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onViewCareStatus, modifier = Modifier.weight(1f)) { Text("\u67e5\u770b\u72b6\u6001") }
                            OutlinedButton(onClick = { showUnlinkConfirm = true }, modifier = Modifier.weight(1f)) { Text("\u89e3\u9664\u5173\u8054") }
                        }
                    }
                }
            }
        }
        item { DeviceStatusFromServer() }
    }
    if (showUnlinkConfirm) {
        AlertDialog(
            onDismissRequest = { showUnlinkConfirm = false },
            title = { Text("\u89e3\u9664\u5173\u8054\uff1f") },
            text = { Text("\u89e3\u9664\u540e\u9700\u8981\u91cd\u65b0\u8f93\u5165\u9080\u8bf7\u7801\u5173\u8054\u3002") },
            confirmButton = { TextButton(onClick = { showUnlinkConfirm = false; onUnlink() }) { Text("\u89e3\u9664") } },
            dismissButton = { TextButton(onClick = { showUnlinkConfirm = false }) { Text("\u53d6\u6d88") } }
        )
    }
}

@Composable
fun LatestRiskList(title: String, events: List<RiskEvent>) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Spacer(Modifier.height(10.dp))
            events.forEach { RiskEventItem(it) }
        }
    }
}

@Composable
fun RiskEventItem(event: RiskEvent) {
    val levelText = when (event.level.lowercase()) {
        "high" -> "高风险"
        "medium" -> "中风险"
        else -> "低风险"
    }
    val levelColor = when (event.level.lowercase()) {
        "high" -> Color(0xFFDC2626)
        "medium" -> Color(0xFFF59E0B)
        else -> Color(0xFF16A34A)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) { contentDescription = "$levelText，${event.aiMessage}，时间${event.time}" }, verticalAlignment = Alignment.Top) {
        Surface(color = levelColor.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
            Text(levelText, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), color = levelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(event.aiMessage, color = Color(0xFF1E293B), fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(event.time, color = Color(0xFF94A3B8), fontSize = 12.sp)
        }
    }
}

@Composable
fun BottomNavigationBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar(modifier = Modifier.navigationBarsPadding(), containerColor = Color.White) {
        val items = listOf("导盲" to "盲", "地图" to "图", "协同" to "协", "求助" to "救", "我的" to "我")
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                icon = { Text(item.second, fontSize = 20.sp) },
                label = { Text(item.first, fontSize = 12.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF0F766E),
                    selectedTextColor = Color(0xFF0F766E),
                    indicatorColor = Color(0xFFE0F2F1),
                    unselectedIconColor = Color(0xFF94A3B8),
                    unselectedTextColor = Color(0xFF94A3B8)
                )
            )
        }
    }
}



