package com.nankai.smartcane.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nankai.smartcane.CollaborationPage
import com.nankai.smartcane.GuidePage
import com.nankai.smartcane.MapPage
import com.nankai.smartcane.MinePage
import com.nankai.smartcane.SosPage
import com.nankai.smartcane.data.model.AppMode
import com.nankai.smartcane.data.model.RelationStatus
import com.nankai.smartcane.ui.auth.LoginScreen
import com.nankai.smartcane.ui.blind.BlindHomeScreen
import com.nankai.smartcane.ui.companion.CompanionHomeScreen
import com.nankai.smartcane.ui.components.SmartBg
import com.nankai.smartcane.ui.components.SmartTeal
import com.nankai.smartcane.ui.mode.ModeSelectionScreen
import com.nankai.smartcane.ui.pairing.BlindPairingScreen
import com.nankai.smartcane.ui.pairing.CompanionPairingScreen
import com.nankai.smartcane.viewmodel.SmartCaneAppController
import kotlinx.coroutines.delay

@Composable
fun SmartCaneRootApp() {
    val context = LocalContext.current
    val controller = remember { SmartCaneAppController.get(context) }
    val uiState by controller.uiState.collectAsState()
    var route: AppRoute by remember { mutableStateOf(AppRoute.Splash) }

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

    DisposableEffect(route, uiState.currentUser?.userId) {
        when (route) {
            AppRoute.BlindPairing -> controller.startBlindRequestPolling()
            AppRoute.CompanionPairing -> controller.startCompanionRelationPolling()
            AppRoute.CompanionHome -> {
                controller.startCompanionRelationPolling()
                controller.startAlertPolling()
            }
            AppRoute.BlindHome -> controller.startAlertPolling()
            else -> Unit
        }
        onDispose {
            if (route is AppRoute.BlindPairing || route is AppRoute.CompanionPairing) controller.stopPairingPolling()
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
                onLogin = controller::login
            )
            AppRoute.ModeSelection -> ModeSelectionScreen(
                userName = uiState.currentUser?.displayName ?: "演示用户",
                onModeSelected = { mode ->
                    controller.selectMode(mode)
                    route = if (mode == AppMode.Blind) AppRoute.BlindHome else AppRoute.CompanionHome
                },
                onLogout = controller::logout
            )
            AppRoute.BlindHome -> BlindHomeScreen(
                voiceState = uiState.voiceState,
                sosState = uiState.sosState,
                message = uiState.message,
                urgentAlert = uiState.urgentAlert,
                onVoiceToggle = controller::toggleVoiceListening,
                onRepeat = controller::repeatNavigationPrompt,
                onSos = controller::sendBlindSos,
                onDismissAlert = controller::dismissUrgentAlert,
                onOpenSettings = { route = AppRoute.BlindPairing }
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
                onLogout = controller::logout,
                onClearDemoData = controller::clearDemoData
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
                onClearDemoData = controller::clearDemoData,
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
                onBack = { route = AppRoute.CompanionHome }
            )
            AppRoute.CompanionRisk -> CompanionLegacyShell(0, { route = AppRoute.CompanionHome }, { route = companionTabToRoute(it) }) { GuidePage() }
            AppRoute.CompanionMap -> CompanionLegacyShell(1, { route = AppRoute.CompanionHome }, { route = companionTabToRoute(it) }) { MapPage() }
            AppRoute.CompanionCollaboration -> CompanionLegacyShell(2, { route = AppRoute.CompanionHome }, { route = companionTabToRoute(it) }) { CollaborationPage() }
            AppRoute.CompanionSos -> CompanionLegacyShell(3, { route = AppRoute.CompanionHome }, { route = companionTabToRoute(it) }) { SosPage() }
            AppRoute.CompanionMine -> CompanionLegacyShell(4, { route = AppRoute.CompanionHome }, { route = companionTabToRoute(it) }) { MinePage() }
        }
    }
}

private fun decideStartRoute(isLoggedIn: Boolean, mode: AppMode?): AppRoute = when {
    !isLoggedIn -> AppRoute.Login
    mode == AppMode.Blind -> AppRoute.BlindHome
    mode == AppMode.Companion -> AppRoute.CompanionHome
    else -> AppRoute.ModeSelection
}

private fun companionTabToRoute(index: Int): AppRoute = when (index) {
    0 -> AppRoute.CompanionRisk
    1 -> AppRoute.CompanionMap
    2 -> AppRoute.CompanionCollaboration
    3 -> AppRoute.CompanionSos
    4 -> AppRoute.CompanionMine
    else -> AppRoute.CompanionHome
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
private fun CompanionLegacyShell(selectedTab: Int, onBackHome: () -> Unit, onSelect: (Int) -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding(), containerColor = Color.White) {
                val items = listOf("风险" to "⚠", "地图" to "图", "协同" to "协", "SOS" to "救", "设备" to "杖")
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { onSelect(index) },
                        icon = { Text(item.second, fontSize = 18.sp) },
                        label = { Text(item.first, fontSize = 12.sp) },
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
            FloatingActionButton(
                onClick = onBackHome,
                modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                containerColor = SmartTeal
            ) { Text("总览", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
