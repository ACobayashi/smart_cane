package com.nankai.smartcane.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nankai.smartcane.CollaborationPage
import com.nankai.smartcane.GuidePage
import com.nankai.smartcane.MapPage
import com.nankai.smartcane.MinePage
import com.nankai.smartcane.data.model.AppMode
import com.nankai.smartcane.data.model.RelationStatus
import com.nankai.smartcane.data.network.EmergencyAlertDto
import com.nankai.smartcane.ui.auth.LoginScreen
import com.nankai.smartcane.ui.blind.BlindHomeScreen
import com.nankai.smartcane.ui.blind.BlindNavigationScreen
import com.nankai.smartcane.ui.companion.CompanionHomeScreen
import com.nankai.smartcane.ui.components.SmartBg
import com.nankai.smartcane.ui.components.SmartTeal
import com.nankai.smartcane.ui.mode.ModeSelectionScreen
import com.nankai.smartcane.ui.pairing.BlindPairingScreen
import com.nankai.smartcane.ui.pairing.CompanionPairingScreen
import com.nankai.smartcane.viewmodel.SmartCaneAppController
import kotlinx.coroutines.delay

private fun hasSmartCaneLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
fun SmartCaneRootApp() {
    val context = LocalContext.current
    val controller = remember { SmartCaneAppController.get(context) }
    val uiState by controller.uiState.collectAsState()
    var route: AppRoute by remember { mutableStateOf(AppRoute.Splash) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    DisposableEffect(Unit) { onDispose { controller.release() } }

    LaunchedEffect(Unit) {
        delay(350L)
        route = decideStartRoute(uiState.isLoggedIn, uiState.currentMode)
    }

    LaunchedEffect(uiState.isLoggedIn, uiState.currentMode) {
        if (!uiState.isLoggedIn) {
            route = AppRoute.Login
        } else if (route is AppRoute.Login || route is AppRoute.ModeSelection || route is AppRoute.Splash) {
            route = decideStartRoute(true, uiState.currentMode)
        }
    }

    LaunchedEffect(uiState.navigationStatus) {
        if (uiState.navigationStatus in setOf("active", "replanning") && uiState.activeNavigationRoute != null) {
            route = AppRoute.BlindNavigation
        } else if (uiState.navigationStatus == "arrived" && route is AppRoute.BlindNavigation) {
            route = AppRoute.BlindHome
        }
    }

    LaunchedEffect(route) {
        if (route is AppRoute.BlindHome && !hasSmartCaneLocationPermission(context)) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    DisposableEffect(route, uiState.currentUser?.userId) {
        when (route) {
            AppRoute.BlindPairing -> controller.startBlindRequestPolling()
            AppRoute.CompanionPairing,
            AppRoute.CompanionHome,
            AppRoute.CompanionRisk,
            AppRoute.CompanionMap,
            AppRoute.CompanionCollaboration,
            AppRoute.CompanionMine -> {
                controller.startCompanionRelationPolling()
                controller.startAlertPolling()
            }
            AppRoute.BlindHome, AppRoute.BlindNavigation -> {
                controller.startAlertPolling()
                controller.startBlindRiskProximityMonitoring()
            }
            else -> Unit
        }
        onDispose {
            if (route is AppRoute.BlindPairing || route is AppRoute.CompanionPairing) controller.stopPairingPolling()
            if (route is AppRoute.BlindHome || route is AppRoute.BlindNavigation) controller.stopBlindRiskProximityMonitoring()
        }
    }

    LaunchedEffect(route, uiState.currentRelation?.relationId, uiState.currentRelation?.status) {
        if (route is AppRoute.CompanionPairing && uiState.currentRelation?.status == RelationStatus.Active) {
            route = AppRoute.CompanionMine
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0F766E),
            secondary = Color(0xFF14B8A6),
            background = SmartBg,
            surface = Color.White
        )
    ) {
        when (route) {
            AppRoute.Splash -> SplashScreen()
            AppRoute.Login -> LoginScreen(
                isBusy = uiState.isBusy,
                message = uiState.message,
                onLogin = controller::login,
                onRegister = controller::register
            )
            AppRoute.ModeSelection -> ModeSelectionScreen(
                userName = uiState.currentUser?.displayName ?: "演示用户",
                onModeSelected = { mode ->
                    controller.selectMode(mode)
                    route = if (mode == AppMode.Blind) AppRoute.BlindHome else AppRoute.CompanionRisk
                },
                onLogout = controller::logout
            )
            AppRoute.BlindHome -> BlindHomeScreen(
                voiceState = uiState.voiceState,
                sosState = uiState.sosState,
                message = uiState.message,
                voiceTranscript = uiState.voiceTranscript,
                urgentAlert = uiState.urgentAlert,
                fallPending = uiState.fallPending,
                navigationPreference = uiState.navigationPreference,
                onVoicePressStart = controller::startVoicePress,
                onVoicePressEnd = controller::endVoicePress,
                onRepeat = controller::repeatNavigationPrompt,
                onSos = controller::sendBlindSos,
                onDismissAlert = controller::dismissUrgentAlert,
                onCancelFall = controller::cancelPendingFall,
                onNavigationPreference = controller::setNavigationPreference,
                onOpenSettings = { route = AppRoute.BlindPairing }
            )
            AppRoute.BlindNavigation -> BlindNavigationScreen(
                route = uiState.activeNavigationRoute,
                status = uiState.navigationStatus,
                instruction = uiState.currentNavigationInstruction,
                onStop = {
                    controller.stopNavigation()
                    route = AppRoute.BlindHome
                },
                onBack = { route = AppRoute.BlindHome }
            )
            AppRoute.BlindPairing -> BlindPairingScreen(
                pairingCode = uiState.storedState.pairingCode,
                pairingExpiresAtMillis = uiState.storedState.pairingExpiresAtMillis,
                pendingRequest = uiState.pendingRequest,
                pairingStatus = uiState.pairingStatus,
                relationStatus = uiState.currentRelation?.status ?: uiState.storedState.relationStatus,
                companionName = uiState.currentRelation?.companionUser?.displayName ?: uiState.storedState.companionUser?.displayName,
                isBusy = uiState.isBusy,
                onGenerateCode = controller::generatePairingCode,
                onApprove = controller::approveRelation,
                onReject = controller::rejectRelation,
                onUnlink = controller::unlinkRelation,
                onBack = { route = AppRoute.BlindHome },
                onSwitchToCompanion = {
                    controller.selectMode(AppMode.Companion)
                    route = AppRoute.CompanionRisk
                },
                onLogout = controller::logout
            )
            AppRoute.CompanionHome -> CompanionHomeScreen(
                userName = uiState.currentUser?.displayName ?: "陪护人",
                relation = uiState.currentRelation ?: controller.relation(),
                relationUpdateText = controller.relationUpdateText(),
                urgentAlert = uiState.urgentAlert,
                onAddCareTarget = { route = AppRoute.CompanionPairing },
                onViewLocation = { route = AppRoute.CompanionMap },
                onViewRiskRecords = { route = AppRoute.CompanionRisk },
                onViewDevice = { route = AppRoute.CompanionMine },
                onSwitchMode = { controller.selectMode(AppMode.Blind); route = AppRoute.BlindHome },
                onLogout = controller::logout,
                onDismissAlert = controller::dismissUrgentAlert,
                onUnlink = controller::unlinkRelation
            )
            AppRoute.CompanionPairing -> CompanionPairingScreen(
                preview = uiState.lastPairingPreview,
                pairingStatus = uiState.pairingStatus,
                relationStatus = uiState.currentRelation?.status ?: uiState.storedState.relationStatus,
                isBusy = uiState.isBusy,
                message = uiState.message,
                onFindCode = controller::findPairingCode,
                onSendRequest = controller::sendRelationRequest,
                onUnlink = controller::unlinkRelation,
                onBack = { route = AppRoute.CompanionMine }
            )
            AppRoute.CompanionRisk -> CompanionLegacyShell(0, { route = companionTabToRoute(it) }, uiState.urgentAlert, controller::dismissUrgentAlert) {
                GuidePage(deviceName = uiState.currentRelation?.caneDevice?.name ?: controller.relation()?.caneDevice?.name)
            }
            AppRoute.CompanionMap -> CompanionLegacyShell(1, { route = companionTabToRoute(it) }, uiState.urgentAlert, controller::dismissUrgentAlert) { MapPage() }
            AppRoute.CompanionCollaboration -> CompanionLegacyShell(2, { route = companionTabToRoute(it) }, uiState.urgentAlert, controller::dismissUrgentAlert) { CollaborationPage() }
            AppRoute.CompanionMine -> CompanionLegacyShell(3, { route = companionTabToRoute(it) }, uiState.urgentAlert, controller::dismissUrgentAlert) {
                MinePage(
                    userName = uiState.currentUser?.displayName ?: "陪护人",
                    relation = uiState.currentRelation ?: controller.relation(),
                    onSwitchToBlind = {
                        controller.selectMode(AppMode.Blind)
                        route = AppRoute.BlindHome
                    },
                    onAddCareTarget = { route = AppRoute.CompanionPairing },
                    onViewCareStatus = { route = AppRoute.CompanionRisk },
                    onUnlink = controller::unlinkRelation,
                    onLogout = controller::logout
                )
            }
        }
    }
}

private fun decideStartRoute(isLoggedIn: Boolean, mode: AppMode?): AppRoute = when {
    !isLoggedIn -> AppRoute.Login
    mode == AppMode.Blind -> AppRoute.BlindHome
    mode == AppMode.Companion -> AppRoute.CompanionRisk
    else -> AppRoute.ModeSelection
}

private fun companionTabToRoute(index: Int): AppRoute = when (index) {
    0 -> AppRoute.CompanionRisk
    1 -> AppRoute.CompanionMap
    2 -> AppRoute.CompanionCollaboration
    3 -> AppRoute.CompanionMine
    else -> AppRoute.CompanionRisk
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(SmartTeal), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SmartCane", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("智能导盲与远程陪护", color = Color(0xFFE0F2F1), fontSize = 16.sp)
        }
    }
}

@Composable
private fun CompanionLegacyShell(selectedTab: Int, onSelect: (Int) -> Unit, urgentAlert: EmergencyAlertDto?, onDismissAlert: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding(), containerColor = Color.White) {
                val items = listOf(
                    CompanionTabItem("实时", CompanionTabIconKind.Risk),
                    CompanionTabItem("地图", CompanionTabIconKind.Map),
                    CompanionTabItem("世界", CompanionTabIconKind.Collaboration),
                    CompanionTabItem("我的", CompanionTabIconKind.Mine)
                )
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { onSelect(index) },
                        icon = { CompanionTabIcon(item.iconKind, LocalContentColor.current) },
                        label = { Text(item.label, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SmartTeal,
                            selectedTextColor = SmartTeal,
                            indicatorColor = Color(0xFFE0F2F1),
                            unselectedIconColor = Color(0xFF94A3B8),
                            unselectedTextColor = Color(0xFF94A3B8)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().background(SmartBg).padding(innerPadding)) {
            content()
        }
    }

    if (urgentAlert != null) {
        AlertDialog(
            onDismissRequest = onDismissAlert,
            title = {
                Text(
                    if (urgentAlert.riskType == "sos") "SOS 紧急求助" else urgentAlert.title.ifBlank { "风险告警" },
                    color = Color(0xFFDC2626),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(urgentAlert.message.ifBlank { urgentAlert.voicePrompt }.ifBlank { if (urgentAlert.riskType == "sos") "用户已发起 SOS 紧急求助，请立即联系并查看地图位置。" else "请关注该风险告警。" }, fontSize = 18.sp, color = Color(0xFF0F172A), lineHeight = 26.sp)
                    Text("设备：${urgentAlert.deviceId}", fontSize = 15.sp, color = Color(0xFF64748B))
                    if (urgentAlert.latitude != null && urgentAlert.longitude != null) {
                        Text("位置：${urgentAlert.latitude}, ${urgentAlert.longitude}", fontSize = 15.sp, color = Color(0xFF64748B))
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismissAlert) { Text("知道了") } }
        )
    }
}

private data class CompanionTabItem(
    val label: String,
    val iconKind: CompanionTabIconKind
)

private enum class CompanionTabIconKind {
    Risk,
    Map,
    Collaboration,
    Mine
}

@Composable
private fun CompanionTabIcon(kind: CompanionTabIconKind, tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = Stroke(width = size.minDimension * 0.09f, cap = StrokeCap.Round)
        val color = tint
        when (kind) {
            CompanionTabIconKind.Risk -> {
                val path = Path().apply {
                    moveTo(size.width * 0.50f, size.height * 0.12f)
                    lineTo(size.width * 0.90f, size.height * 0.84f)
                    lineTo(size.width * 0.10f, size.height * 0.84f)
                    close()
                }
                drawPath(path, color = color, style = stroke)
                drawLine(color, Offset(size.width * 0.50f, size.height * 0.36f), Offset(size.width * 0.50f, size.height * 0.58f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = size.minDimension * 0.045f, center = Offset(size.width * 0.50f, size.height * 0.70f))
            }
            CompanionTabIconKind.Map -> {
                drawLine(color, Offset(size.width * 0.18f, size.height * 0.78f), Offset(size.width * 0.18f, size.height * 0.26f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.18f, size.height * 0.26f), Offset(size.width * 0.42f, size.height * 0.16f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.42f, size.height * 0.16f), Offset(size.width * 0.42f, size.height * 0.70f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.42f, size.height * 0.70f), Offset(size.width * 0.66f, size.height * 0.82f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.66f, size.height * 0.82f), Offset(size.width * 0.82f, size.height * 0.70f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.82f, size.height * 0.70f), Offset(size.width * 0.82f, size.height * 0.20f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = size.minDimension * 0.07f, center = Offset(size.width * 0.64f, size.height * 0.38f))
            }
            CompanionTabIconKind.Collaboration -> {
                val left = Offset(size.width * 0.32f, size.height * 0.42f)
                val right = Offset(size.width * 0.68f, size.height * 0.42f)
                val bottom = Offset(size.width * 0.50f, size.height * 0.76f)
                drawLine(color, left, right, strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, left, bottom, strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, right, bottom, strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = size.minDimension * 0.11f, center = left, style = stroke)
                drawCircle(color, radius = size.minDimension * 0.11f, center = right, style = stroke)
                drawCircle(color, radius = size.minDimension * 0.11f, center = bottom, style = stroke)
            }
            CompanionTabIconKind.Mine -> {
                drawCircle(color, radius = size.minDimension * 0.15f, center = Offset(size.width * 0.50f, size.height * 0.34f), style = stroke)
                drawArc(
                    color = color,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.46f),
                    size = Size(size.width * 0.56f, size.height * 0.46f),
                    style = stroke
                )
            }
        }
    }
}
