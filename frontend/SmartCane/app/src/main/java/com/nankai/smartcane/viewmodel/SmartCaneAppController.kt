package com.nankai.smartcane.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.nankai.smartcane.data.local.DemoData
import com.nankai.smartcane.data.local.LocalAppPreferences
import com.nankai.smartcane.data.local.StoredAppState
import com.nankai.smartcane.data.model.AppMode
import com.nankai.smartcane.data.model.CareRelation
import com.nankai.smartcane.data.model.CareRequest
import com.nankai.smartcane.data.model.PairingCode
import com.nankai.smartcane.data.model.PairingFlowStatus
import com.nankai.smartcane.data.model.RelationStatus
import com.nankai.smartcane.data.model.SelfSosGeneration
import com.nankai.smartcane.data.model.SelfSosReplayState
import com.nankai.smartcane.data.model.SelfSosReplayStateMachine
import com.nankai.smartcane.data.model.UserProfile
import com.nankai.smartcane.data.model.UserRole
import com.nankai.smartcane.data.network.ApiResult
import com.nankai.smartcane.data.network.EmergencyAlertDto
import com.nankai.smartcane.data.network.LocalCueDto
import com.nankai.smartcane.data.network.LocationUploadDto
import com.nankai.smartcane.data.network.NearbyRiskWarningDto
import com.nankai.smartcane.data.network.NavigationRouteDto
import com.nankai.smartcane.data.network.RouteAdviceDto
import com.nankai.smartcane.data.network.SmartCaneApiClient
import com.nankai.smartcane.data.network.VoiceCommandDto
import com.nankai.smartcane.data.network.SosRequestDto
import com.nankai.smartcane.data.repository.AuthRepository
import com.nankai.smartcane.data.repository.DemoAuthRepository
import com.nankai.smartcane.data.repository.PairingRepository
import com.nankai.smartcane.data.repository.RemoteAuthRepository
import com.nankai.smartcane.data.repository.RemotePairingRepository
import com.nankai.smartcane.navigation.NavigationLocationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class SmartCaneAppController private constructor(
    private val authRepository: AuthRepository,
    private val pairingRepository: PairingRepository,
    private val preferences: LocalAppPreferences,
    private val appContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceRecognitionActive = false
    private var voiceRecordingJob: Job? = null
    private var voiceRecorder: AudioRecord? = null
    private var voiceRecordingFile: File? = null
    private var backendVoiceRecordingActive = false
    private var backendVoiceRecorder: MediaRecorder? = null
    private var backendVoiceFile: File? = null
    private var blindPollingJob: Job? = null
    private var companionPollingJob: Job? = null
    private var alertPollingJob: Job? = null
    private var hardwareRiskPollingJob: Job? = null
    private var localCueStreamJob: Job? = null
    private var sosAlarmJob: Job? = null
    private var blindRiskMonitorJob: Job? = null
    private var locationUpdatesActive = false
    private var phoneLocationListener: LocationListener? = null
    private var lastKnownPhoneLocation: Location? = null
    private var latestContinuousLocation: Location? = null
    private var activeTtsUtteranceId: String? = null
    private var activeTtsPriority: TtsPriority? = null
    private val pendingSpeech = mutableListOf<QueuedSpeech>()
    private val speechCooldowns = mutableMapOf<String, Long>()
    private var lastAlertId: Int = 0
    private var alertBaselineReady = false
    private val localCueBaselines = mutableMapOf<String, Int>()
    private val seenCueIds = CueIdSpeechGate()
    private val spokenEventIds = EventSpeechGate()
    private val nearbyRiskSpeechCooldown = RiskPointSpeechCooldown()
    private val nearbyRiskApproachGate = RiskPointApproachGate()
    private var selfSosReplayDemoEnabled = true
    private var selfSosReplayStateMachine = SelfSosReplayStateMachine()
    private var navigationReceiverRegistered = false
    private val navigationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NavigationLocationService.ACTION_STATE_CHANGED -> {
                    val arrived = intent.getBooleanExtra(NavigationLocationService.EXTRA_ARRIVED, false)
                    val offRoute = intent.getBooleanExtra(NavigationLocationService.EXTRA_OFF_ROUTE, false)
                    val stepIndex = intent.getIntExtra(NavigationLocationService.EXTRA_STEP_INDEX, 0)
                    val instruction = intent.getStringExtra(NavigationLocationService.EXTRA_INSTRUCTION).orEmpty()
                    val trafficWarning = intent.getStringExtra(NavigationLocationService.EXTRA_TRAFFIC_WARNING).orEmpty()
                    val trafficWarningId = intent.getStringExtra(NavigationLocationService.EXTRA_TRAFFIC_WARNING_ID).orEmpty()
                    val distanceToRoute = intent.getDoubleExtra(NavigationLocationService.EXTRA_DISTANCE_TO_ROUTE_M, 0.0)
                    val distanceToDestination = intent.getDoubleExtra(NavigationLocationService.EXTRA_DISTANCE_TO_DESTINATION_M, 0.0)
                    val distanceToNextAction = intent.getDoubleExtra(NavigationLocationService.EXTRA_DISTANCE_TO_NEXT_ACTION_M, Double.MAX_VALUE)
                    val distanceToTrafficWarning = intent.getDoubleExtra(
                        NavigationLocationService.EXTRA_DISTANCE_TO_TRAFFIC_WARNING_M, Double.MAX_VALUE
                    )
                    _uiState.update {
                        it.copy(
                            navigationStatus = if (arrived) "arrived" else if (offRoute) "off_route" else "active",
                            currentStepIndex = stepIndex,
                            currentNavigationInstruction = instruction,
                            distanceToRouteM = distanceToRoute,
                            distanceToDestinationM = distanceToDestination,
                            navigationArrived = arrived
                        )
                    }
                    if (arrived) speakText("已到达目的地。", priority = TtsPriority.NAVIGATION)
                    else if (!maybeSpeakTrafficWarning(trafficWarningId, trafficWarning, distanceToTrafficWarning)) {
                        maybeSpeakNavigationStep(stepIndex, instruction, distanceToNextAction, distanceToDestination)
                    }
                }
                NavigationLocationService.ACTION_REPLANNING -> {
                    _uiState.update { it.copy(navigationStatus = "replanning") }
                    speakText("检测到持续偏航，正在重新规划。", priority = TtsPriority.NAVIGATION)
                }
                NavigationLocationService.ACTION_REPLANNED -> {
                    val success = intent.getBooleanExtra(NavigationLocationService.EXTRA_REPLAN_SUCCESS, false)
                    _uiState.update {
                        it.copy(
                            navigationStatus = if (success) "active" else "replan_failed",
                            activeNavigationRoute = NavigationLocationService.latestRoute?.bestRoute ?: it.activeNavigationRoute,
                            alternativeNavigationRoutes = NavigationLocationService.latestRoute?.routes ?: it.alternativeNavigationRoutes
                        )
                    }
                    val routePrompt = intent.getStringExtra(NavigationLocationService.EXTRA_VOICE_PROMPT).orEmpty()
                    speakText(
                        if (success) routePrompt.ifBlank { "重新规划完成" } else "重新规划失败，请停在安全位置",
                        priority = TtsPriority.NAVIGATION
                    )
                }
                NavigationLocationService.ACTION_LOCATION_FAILED -> {
                    val error = intent.getStringExtra(NavigationLocationService.EXTRA_ERROR) ?: "定位不可用，导航已停止。"
                    _uiState.update { it.copy(navigationStatus = "location_failed", message = error) }
                    speakText(error, priority = TtsPriority.NAVIGATION)
                }
            }
        }
    }
    private val announcedNavigationSteps = mutableSetOf<String>()
    private val announcedTrafficWarnings = mutableSetOf<String>()

    private fun currentCaneDeviceId(): String =
        _uiState.value.currentRelation?.caneDevice?.deviceId
            ?: DemoData.defaultCane.deviceId.takeIf { _uiState.value.currentUser?.isDemo == true }
            ?: mobileObserverId()
            ?: ""

    private fun mobileObserverId(): String? =
        _uiState.value.currentUser
            ?.takeIf { it.account.equals("AC", ignoreCase = true) }
            ?.userId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "mobile_$it" }

    private fun nearbyRiskObserverId(): String? = mobileObserverId() ?: speechCaneDeviceId()

    private fun boundCaneDeviceId(): String =
        _uiState.value.currentRelation
            ?.takeIf { it.status == RelationStatus.Active }
            ?.caneDevice?.deviceId
            ?.trim()
            .orEmpty()

    private fun speechCaneDeviceId(): String? =
        speechCaneDeviceId(_uiState.value.currentRelation)

    private fun activateNavigationRoute(route: RouteAdviceDto, deviceId: String): Boolean {
        val sessionId = route.sessionId ?: return false
        val bestRoute = route.bestRoute ?: return false
        NavigationLocationService.latestRoute = route
        NavigationLocationService.start(appContext, sessionId, deviceId)
        announcedNavigationSteps.clear()
        announcedTrafficWarnings.clear()
        _uiState.update { state ->
            state.copy(
                navigationStatus = "active",
                activeNavigationRoute = bestRoute,
                alternativeNavigationRoutes = route.routes,
                selectedRouteIndex = route.selectedRouteIndex
            )
        }
        return true
    }

    private fun speakVoiceCommandResult(result: VoiceCommandDto, deviceId: String) {
        val route = result.route
        if (route != null && activateNavigationRoute(route, deviceId)) {
            speakText(plannedRouteSpeech(route.voicePrompt), priority = TtsPriority.NAVIGATION)
        } else {
            speakText(result.voicePrompt.ifBlank { result.reply.ifBlank { "已收到语音指令" } })
        }
    }

    private val _uiState = MutableStateFlow(AppUiState(storedState = preferences.state.value))
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        val filter = IntentFilter().apply {
            addAction(NavigationLocationService.ACTION_STATE_CHANGED)
            addAction(NavigationLocationService.ACTION_REPLANNING)
            addAction(NavigationLocationService.ACTION_REPLANNED)
            addAction(NavigationLocationService.ACTION_LOCATION_FAILED)
        }
        ContextCompat.registerReceiver(appContext, navigationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        navigationReceiverRegistered = true
        scope.launch {
            preferences.state.collectLatest { stored ->
                _uiState.update { current -> current.copy(storedState = stored) }
            }
        }
    }

    private fun maybeSpeakNavigationStep(
        stepIndex: Int,
        instruction: String,
        distanceToNextActionM: Double,
        distanceToDestinationM: Double
    ) {
        if (instruction.isBlank()) return
        if (distanceToDestinationM <= 20.0) return
        val threshold = when {
            distanceToNextActionM <= 10.0 -> 10
            distanceToNextActionM <= 30.0 -> 30
            else -> return
        }
        if (!announcedNavigationSteps.add("$stepIndex:$threshold")) return
        val conciseInstruction = instruction.replace(Regex("[。；;].*"), "").trim()
        speakText("${threshold}米后，$conciseInstruction", priority = TtsPriority.NAVIGATION)
    }

    private fun maybeSpeakTrafficWarning(warningId: String, warning: String, distanceM: Double): Boolean {
        if (warningId.isBlank() || warning.isBlank() || distanceM > 30.0) return false
        if (!announcedTrafficWarnings.add(warningId)) return false
        speakText(warning, priority = TtsPriority.NAVIGATION)
        return true
    }

    fun login(account: String, password: String, rememberLogin: Boolean) {
        if (_uiState.value.isBusy) return
        scope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            val result = authRepository.login(account, password, rememberLogin)
            if (result.success && result.user != null) {
                _uiState.update { it.copy(isBusy = false, message = "登录成功") }
            } else {
                _uiState.update { it.copy(isBusy = false, message = result.message) }
            }
        }
    }

    fun register(account: String, password: String, displayName: String, role: UserRole, rememberLogin: Boolean) {
        if (_uiState.value.isBusy) return
        scope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            val result = authRepository.register(account, password, displayName, role, rememberLogin)
            if (result.success && result.user != null) {
                _uiState.update { it.copy(isBusy = false, message = "注册成功") }
            } else {
                _uiState.update { it.copy(isBusy = false, message = result.message) }
            }
        }
    }

    fun loginDemoBlind() = login(DemoData.BLIND_ACCOUNT, DemoData.DEMO_PASSWORD, true)
    fun loginDemoCompanion() = login(DemoData.COMPANION_ACCOUNT, DemoData.DEMO_PASSWORD, true)

    fun selectMode(mode: AppMode) {
        preferences.saveMode(mode)
        preferences.saveFirstGuideCompleted(true)
        _uiState.update { it.copy(message = "已切换") }
    }

    fun switchMode() {
        val next = if (_uiState.value.currentMode == AppMode.Companion) AppMode.Blind else AppMode.Companion
        selectMode(next)
    }

    fun logout() {
        stopPairingPolling()
        stopAlertPolling()
        scope.launch {
            authRepository.logout()
            _uiState.update { it.copy(message = null, currentRelation = null, pendingRequest = null, urgentAlert = null, pairingStatus = PairingFlowStatus.Idle) }
        }
    }

    fun clearDemoData() {
        scope.launch {
            authRepository.clearDemoData()
            _uiState.update { it.copy(message = "已清除", currentRelation = null, pendingRequest = null, pairingStatus = PairingFlowStatus.Idle) }
        }
    }

    fun dismissMessage() { _uiState.update { it.copy(message = null) } }

    fun generatePairingCode() {
        val user = _uiState.value.currentUser ?: DemoData.blindUser
        if (_uiState.value.isBusy) return
        scope.launch {
            _uiState.update { it.copy(isBusy = true, pairingStatus = PairingFlowStatus.Loading, message = null) }
            val result = pairingRepository.generatePairingCode(user)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    lastPairingPreview = result.getOrNull(),
                    pairingStatus = if (result.isSuccess) PairingFlowStatus.Waiting else PairingFlowStatus.Error,
                    message = result.exceptionOrNull()?.message ?: "配对码已生成"
                )
            }
        }
    }

    fun findPairingCode(code: String) {
        if (_uiState.value.isBusy) return
        scope.launch {
            _uiState.update { it.copy(isBusy = true, pairingStatus = PairingFlowStatus.Loading, message = null) }
            val result = pairingRepository.findPairingCode(code)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    lastPairingPreview = result.getOrNull(),
                    pairingStatus = if (result.isSuccess) PairingFlowStatus.Idle else PairingFlowStatus.Error,
                    message = result.exceptionOrNull()?.message ?: "已找到"
                )
            }
        }
    }

    fun sendRelationRequest(code: String) {
        val companion = _uiState.value.currentUser ?: DemoData.companionUser
        if (_uiState.value.isBusy) return
        scope.launch {
            _uiState.update { it.copy(isBusy = true, pairingStatus = PairingFlowStatus.Loading, message = null) }
            val result = pairingRepository.sendRelationRequest(code, companion)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    pairingStatus = if (result.isSuccess) PairingFlowStatus.Waiting else PairingFlowStatus.Error,
                    message = result.exceptionOrNull()?.message ?: "申请已发送"
                )
            }
            if (result.isSuccess) startCompanionRelationPolling()
        }
    }

    fun approveRelation() {
        val requestId = _uiState.value.pendingRequest?.requestId ?: _uiState.value.storedState.pendingRequestId ?: return
        if (_uiState.value.isBusy) return
        scope.launch {
            _uiState.update { it.copy(isBusy = true, pairingStatus = PairingFlowStatus.Loading) }
            val result = pairingRepository.approveRequest(requestId)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    currentRelation = result.getOrNull(),
                    pendingRequest = null,
                    pairingStatus = if (result.isSuccess) PairingFlowStatus.Connected else PairingFlowStatus.Error,
                    message = result.exceptionOrNull()?.message ?: "已同意"
                )
            }
        }
    }

    fun rejectRelation() {
        val requestId = _uiState.value.pendingRequest?.requestId ?: _uiState.value.storedState.pendingRequestId ?: return
        if (_uiState.value.isBusy) return
        scope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = pairingRepository.rejectRequest(requestId)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    pendingRequest = null,
                    pairingStatus = if (result.isSuccess) PairingFlowStatus.Rejected else PairingFlowStatus.Error,
                    message = result.exceptionOrNull()?.message ?: "已拒绝"
                )
            }
        }
    }

    fun unlinkRelation() {
        val relationId = _uiState.value.currentRelation?.relationId ?: _uiState.value.storedState.relationId
        if (_uiState.value.isBusy) return
        scope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = pairingRepository.unlinkRelation(relationId)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    currentRelation = null,
                    pendingRequest = null,
                    pairingStatus = if (result.isSuccess) PairingFlowStatus.Idle else PairingFlowStatus.Error,
                    message = result.exceptionOrNull()?.message ?: "已解除"
                )
            }
        }
    }

    fun refreshCurrentRelation() {
        val user = _uiState.value.currentUser ?: return
        scope.launch {
            val result = pairingRepository.getCurrentRelation(user)
            val relation = withLiveCaneState(result.getOrNull())
            _uiState.update {
                it.copy(
                    currentRelation = relation,
                    pairingStatus = if (relation != null) PairingFlowStatus.Connected else PairingFlowStatus.Idle
                )
            }
        }
    }

    fun startBlindRequestPolling() {
        if (blindPollingJob?.isActive == true) return
        val user = _uiState.value.currentUser ?: return
        blindPollingJob = scope.launch {
            while (true) {
                val relation = withLiveCaneState(pairingRepository.getCurrentRelation(user).getOrNull())
                if (relation != null) {
                    _uiState.update { it.copy(currentRelation = relation, pendingRequest = null, pairingStatus = PairingFlowStatus.Connected) }
                } else {
                    _uiState.update { it.copy(currentRelation = null) }
                    val requests = pairingRepository.getPendingRequests(user).getOrNull().orEmpty()
                    val pending = requests.firstOrNull()
                    _uiState.update {
                        it.copy(
                            pendingRequest = pending,
                            pairingStatus = when {
                                pending != null -> PairingFlowStatus.PendingApproval
                                it.storedState.pairingCode != null -> PairingFlowStatus.Waiting
                                else -> it.pairingStatus
                            }
                        )
                    }
                }
                delay(4_000L)
            }
        }
    }

    fun startCompanionRelationPolling() {
        if (companionPollingJob?.isActive == true) return
        val user = _uiState.value.currentUser ?: return
        companionPollingJob = scope.launch {
            while (true) {
                val relationResult = pairingRepository.getCurrentRelation(user)
                val relation = withLiveCaneState(relationResult.getOrNull())
                if (relation != null) {
                    _uiState.update { it.copy(currentRelation = relation, pairingStatus = PairingFlowStatus.Connected, pendingRequest = null, message = "关联成功") }
                } else {
                    _uiState.update { it.copy(currentRelation = null) }
                    val requests = pairingRepository.getCompanionRequests(user).getOrNull().orEmpty()
                    val latest = requests.lastOrNull()
                    if (latest != null) {
                        _uiState.update {
                            it.copy(
                                pendingRequest = latest,
                                pairingStatus = when (latest.status) {
                                    RelationStatus.Rejected -> PairingFlowStatus.Rejected
                                    RelationStatus.Active -> PairingFlowStatus.Connected
                                    else -> PairingFlowStatus.Waiting
                                }
                            )
                        }
                    }
                }
                delay(4_000L)
            }
        }
    }

    private suspend fun withLiveCaneState(relation: CareRelation?): CareRelation? {
        if (relation == null || relation.status != RelationStatus.Active) return relation
        val deviceId = relation.caneDevice.deviceId.trim()
        if (deviceId.isBlank()) return relation.copy(
            caneDevice = relation.caneDevice.copy(online = false, lastSeenText = "未绑定设备")
        )
        val online = when (val result = SmartCaneApiClient.getLatestDeviceState(deviceId)) {
            is ApiResult.Success -> result.data.found &&
                result.data.state?.online == true &&
                isRecentDeviceHeartbeat(result.data.state.updatedAt)
            is ApiResult.Failure -> false
        }
        return relation.copy(
            caneDevice = relation.caneDevice.copy(
                online = online,
                lastSeenText = if (online) "刚刚" else "设备离线"
            )
        )
    }

    fun stopPairingPolling() {
        blindPollingJob?.cancel()
        companionPollingJob?.cancel()
        blindPollingJob = null
        companionPollingJob = null
    }

    fun startBlindRiskProximityMonitoring() {
        if (blindRiskMonitorJob?.isActive == true) return
        startPhoneLocationUpdates()
        blindRiskMonitorJob = scope.launch {
            while (true) {
                val state = _uiState.value
                if (state.currentMode != AppMode.Blind) {
                    delay(6_000L)
                    continue
                }
                if (isNavigationInProgress(state.navigationStatus)) {
                    delay(6_000L)
                    continue
                }

                val location = latestPhoneLocation()
                if (location == null) {
                    _uiState.update {
                        if (it.message.isNullOrBlank()) it.copy(message = "\u8bf7\u5f00\u542f\u5b9a\u4f4d\u6743\u9650\uff0c\u7528\u4e8e\u9644\u8fd1\u98ce\u9669\u70b9\u8bed\u97f3\u63d0\u9192") else it
                    }
                    delay(6_000L)
                    continue
                }

                val deviceId = nearbyRiskObserverId()
                if (deviceId == null) {
                    delay(6_000L)
                    continue
                }
                SmartCaneApiClient.postLocation(
                    LocationUploadDto(
                        deviceId = deviceId,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        provider = location.provider,
                        quality = if (location.isFromMockProvider) "mock" else "usable",
                        accuracyM = location.accuracy.takeIf { it > 0f },
                        bearingDeg = location.bearing.takeIf { location.hasBearing() }
                    )
                )

                when (val result = SmartCaneApiClient.getNearbyRiskWarning(
                    location.latitude,
                    location.longitude,
                    radiusM = NON_NAVIGATION_RISK_WARNING_RADIUS_M,
                    bearingDeg = location.bearing.takeIf { location.hasBearing() },
                    excludeDeviceId = deviceId
                )) {
                    is ApiResult.Success -> {
                        val warning = result.data
                        if (nearbyRiskApproachGate.shouldSpeak(warning?.eventId)) {
                            warning?.let { maybeSpeakNearbyRiskWarning(it) }
                        }
                    }
                    is ApiResult.Failure -> Unit
                }
                delay(6_000L)
            }
        }
    }

    fun stopBlindRiskProximityMonitoring() {
        blindRiskMonitorJob?.cancel()
        blindRiskMonitorJob = null
        nearbyRiskApproachGate.reset()
        stopPhoneLocationUpdates()
    }

    fun setSelfSosReplayDemoEnabled(enabled: Boolean) {
        if (selfSosReplayDemoEnabled == enabled) return
        selfSosReplayDemoEnabled = enabled
        resetSelfSosReplayDemoState()
    }

    fun resetSelfSosReplayDemoState() {
        selfSosReplayStateMachine = SelfSosReplayStateMachine()
    }

    private fun maybeSpeakNearbyRiskWarning(warning: NearbyRiskWarningDto) {
        if (isNavigationInProgress(_uiState.value.navigationStatus)) return
        if (activeTtsPriority?.rank?.let { it > TtsPriority.ROAD_RISK.rank } == true) return
        val currentDeviceId = nearbyRiskObserverId() ?: return
        val isOwnFallRisk = currentDeviceId.isNotEmpty() &&
            warning.riskType == "fall_detected" &&
            warning.sourceDevices.any { sourceDevice -> sourceDevice.trim() == currentDeviceId }
        if (isOwnFallRisk) return
        val isSelfSosReplay = selfSosReplayDemoEnabled &&
            currentDeviceId.isNotEmpty() &&
            warning.riskType == "sos" &&
            warning.sourceDevices.any { sourceDevice -> sourceDevice.trim() == currentDeviceId }
        Log.d(
            SELF_SOS_REPLAY_LOG_TAG,
            "SELF_SOS_REPLAY candidate recognized=$isSelfSosReplay " +
                "riskPointId=${warning.eventId} riskType=${warning.riskType} riskLevel=${warning.riskLevel} " +
                "sourceDevices=${warning.sourceDevices} currentDeviceId=$currentDeviceId " +
                "distanceM=${warning.distanceM} timestamp=${warning.timestamp} reportCount=${warning.reportCount}"
        )
        if (isSelfSosReplay && _uiState.value.voiceState == VoiceState.Listening) return

        if (isSelfSosReplay) {
            Log.d(
                SELF_SOS_REPLAY_LOG_TAG,
                "SELF_SOS_REPLAY before update state=${selfSosReplayStateMachine.state} " +
                    "distanceM=${warning.distanceM} generation=" +
                    "${warning.eventId}|${warning.timestamp}|${warning.reportCount ?: 0}"
            )
            val transition = selfSosReplayStateMachine.update(
                SelfSosGeneration(
                    riskPointId = warning.eventId,
                    timestamp = warning.timestamp,
                    reportCount = warning.reportCount ?: 0
                ),
                warning.distanceM
            )
            val replayOutcome = mapOf(
                Pair(SelfSosReplayState.WAITING_TO_LEAVE, false) to
                    "SELF_SOS_REPLAY waiting_to_leave - speech suppressed",
                Pair(SelfSosReplayState.ARMED, false) to
                    "SELF_SOS_REPLAY armed - waiting for re-entry",
                Pair(SelfSosReplayState.PLAYED, true) to
                    "SELF_SOS_REPLAY trigger speech",
                Pair(SelfSosReplayState.PLAYED, false) to
                    "SELF_SOS_REPLAY already played - speech suppressed"
            )[Pair(transition.state, transition.shouldPlay)] ?: "SELF_SOS_REPLAY transition observed"
            Log.d(
                SELF_SOS_REPLAY_LOG_TAG,
                "$replayOutcome state=${transition.state} shouldPlay=${transition.shouldPlay} " +
                    "isNewGeneration=${transition.isNewGeneration}"
            )
            if (!transition.shouldPlay) return
        }

        val now = System.currentTimeMillis()
        if (_uiState.value.voiceState == VoiceState.Listening) return
        if (!nearbyRiskSpeechCooldown.tryAcquire(warning.eventId, now)) return

        val distanceM = warning.distanceM.roundToInt().coerceAtLeast(1)
        val directionText = warning.relativeDirectionText.ifBlank { "前方" }
        val fallback = "${directionText}${distanceM}米有${riskLevelLabel(warning.riskLevel)}风险点"
        val text = nearbyRiskPointSpeechText(
            riskType = warning.riskType,
            serverText = warning.voicePrompt.ifBlank { fallback }
        ) ?: return
        _uiState.update { it.copy(message = "附近风险点：${directionText}${distanceM}米", lastSpokenText = text) }
        speakText(
            text,
            priority = TtsPriority.ROAD_RISK,
            requiresOnlineCane = mobileObserverId() == null
        )
    }

    @Suppress("MissingPermission")
    private fun startPhoneLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse || phoneLocationListener != null) return

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = LocationListener { location -> latestContinuousLocation = location }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        providers.forEach { provider ->
            runCatching { manager.requestLocationUpdates(provider, 3_000L, 3f, listener, Looper.getMainLooper()) }
        }
        if (providers.isNotEmpty()) phoneLocationListener = listener
    }

    @Suppress("MissingPermission")
    private fun stopPhoneLocationUpdates() {
        val listener = phoneLocationListener ?: return
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching { manager.removeUpdates(listener) }
        phoneLocationListener = null
    }

    @Suppress("MissingPermission")
    private fun latestPhoneLocation(): Location? {
        if (!hasLocationPermission()) return null

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }

        return selectBestLocation(
            providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() } +
                listOfNotNull(latestContinuousLocation, lastKnownPhoneLocation),
            LOCATION_MAX_AGE_MS,
            LOCATION_MAX_ACCURACY_M
        )
    }

    fun startAlertPolling() {
        startHardwareRiskPolling()
        startLocalCueStream()
        if (alertPollingJob?.isActive == true) return
        alertPollingJob = scope.launch {
            while (true) {
                val state = _uiState.value
                val mode = state.currentMode
                val role = when (mode) {
                    AppMode.Companion -> "companion"
                    AppMode.Blind -> "blind"
                    null -> state.currentUser?.role?.apiValue ?: "blind"
                }
                val userId = state.currentUser?.account ?: state.currentUser?.userId
                val deviceId = boundCaneDeviceId().ifBlank { null }
                if (deviceId == null) {
                    delay(1_000L)
                    continue
                }
                when (val result = SmartCaneApiClient.getLatestAlerts(role, userId, deviceId, lastAlertId)) {
                    is ApiResult.Success -> {
                        val newAlerts = result.data.filter { it.id > lastAlertId }.sortedBy { it.id }
                        if (!alertBaselineReady) {
                            lastAlertId = newAlerts.lastOrNull()?.id ?: lastAlertId
                            alertBaselineReady = true
                            delay(5_000L)
                            continue
                        }
                        newAlerts.forEach { alert ->
                            lastAlertId = maxOf(lastAlertId, alert.id)
                            if (alert.riskType == "voice_request" && role == "blind") {
                                if (!alert.freshForSpeech) return@forEach
                                if (!spokenEventIds.tryAcquire(alert.id)) return@forEach
                                _uiState.update {
                                    it.copy(
                                        urgentAlert = null,
                                        voiceState = VoiceState.Speaking,
                                        message = "盲杖按钮已触发"
                                    )
                                }
                                speakText(alert.voicePrompt.ifBlank { alert.message }, listenAfter = true)
                            } else {
                                _uiState.update { it.copy(urgentAlert = alert, message = alert.title) }
                                if (alert.riskType == "fall_detected") return@forEach
                                alertSpeechForRole(
                                    role = role,
                                    riskType = alert.riskType,
                                    voicePrompt = alert.voicePrompt,
                                    message = alert.message,
                                    sosAlarmActive = sosAlarmJob?.isActive == true,
                                    distanceMm = alert.distance
                                )?.let { alertText ->
                                    if (!alert.freshForSpeech) return@let
                                    if (!spokenEventIds.tryAcquire(alert.id)) return@let
                                    if (alert.riskType == "sos" || alert.riskType == "fall_detected") {
                                        speakText(alertText, TtsPriority.EMERGENCY, bypassTextCooldown = true)
                                    } else {
                                        speakText(alertText)
                                    }
                                }
                            }
                        }
                    }
                    is ApiResult.Failure -> Unit
                }
                alertBaselineReady = true
                delay(5_000L)
            }
        }
    }

    fun stopAlertPolling() {
        alertPollingJob?.cancel()
        alertPollingJob = null
        lastAlertId = 0
        alertBaselineReady = false
        stopHardwareRiskPolling()
        stopLocalCueStream()
        spokenEventIds.clear()
        seenCueIds.clear()
    }

    private fun startHardwareRiskPolling() {
        if (hardwareRiskPollingJob?.isActive == true) return
        hardwareRiskPollingJob = scope.launch {
            while (true) {
                val state = _uiState.value
                if (state.currentMode != AppMode.Blind) {
                    delay(1_500L)
                    continue
                }

                val deviceId = boundCaneDeviceId()
                if (deviceId.isBlank()) {
                    _uiState.update { it.copy(fallPending = false, fallStage = null) }
                    delay(1_000L)
                    continue
                }
                when (val result = SmartCaneApiClient.getLatestDeviceState(deviceId)) {
                    is ApiResult.Success -> {
                        val deviceState = result.data.state
                        val online = result.data.found &&
                            deviceState?.online == true &&
                            isRecentDeviceHeartbeat(deviceState.updatedAt)
                        _uiState.update { state ->
                            val relation = state.currentRelation
                            val updatedRelation = if (relation?.caneDevice?.deviceId == deviceId) {
                                relation.copy(
                                    caneDevice = relation.caneDevice.copy(
                                        online = online,
                                        lastSeenText = if (online) "刚刚" else "设备离线"
                                    )
                                )
                            } else relation
                            state.copy(
                                currentRelation = updatedRelation,
                                fallPending = if (online) deviceState?.fallPending == true else false,
                                fallStage = if (online) deviceState?.fallStage else null
                            )
                        }
                    }
                    is ApiResult.Failure -> {
                        _uiState.update { state ->
                            val relation = state.currentRelation
                            state.copy(
                                currentRelation = if (relation?.caneDevice?.deviceId == deviceId) {
                                    relation.copy(caneDevice = relation.caneDevice.copy(online = false, lastSeenText = "连接不可用"))
                                } else relation,
                                fallPending = false,
                                fallStage = null
                            )
                        }
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun stopHardwareRiskPolling() {
        hardwareRiskPollingJob?.cancel()
        hardwareRiskPollingJob = null
    }

    private fun startLocalCueStream() {
        if (localCueStreamJob?.isActive == true) return
        localCueStreamJob = scope.launch {
            while (true) {
                val state = _uiState.value
                val role = if (state.currentMode == AppMode.Companion) "companion" else "blind"
                val deviceId = speechCaneDeviceId()
                if (deviceId == null) {
                    delay(1_000L)
                    continue
                }
                var baseline = localCueBaselines[deviceId]
                if (baseline == null) {
                    when (val result = SmartCaneApiClient.getLatestLocalCueId(deviceId)) {
                        is ApiResult.Success -> {
                            baseline = result.data
                            localCueBaselines[deviceId] = result.data
                        }
                        is ApiResult.Failure -> {
                            delay(1_000L)
                            continue
                        }
                    }
                }
                val streamResult = SmartCaneApiClient.streamLocalCues(
                    deviceId = deviceId,
                    sinceId = baseline,
                    role = role,
                    shouldContinue = {
                        localCueStreamJob?.isActive == true &&
                            speechCaneDeviceId() == deviceId &&
                            (if (_uiState.value.currentMode == AppMode.Companion) "companion" else "blind") == role
                    },
                    onCue = { cue ->
                        localCueBaselines[deviceId] = maxOf(localCueBaselines[deviceId] ?: 0, cue.id)
                        scope.launch { handleLocalCue(cue, deviceId) }
                    }
                )
                if (streamResult is ApiResult.Success) {
                    localCueBaselines[deviceId] = maxOf(localCueBaselines[deviceId] ?: 0, streamResult.data)
                }
                delay(500L)
            }
        }
    }

    private fun stopLocalCueStream() {
        localCueStreamJob?.cancel()
        localCueStreamJob = null
        localCueBaselines.clear()
    }

    private fun handleLocalCue(cue: LocalCueDto, deviceId: String) {
        if (speechCaneDeviceId() != deviceId) return
        if (!shouldSpeakLocalCue(cue, deviceId)) return
        if (!seenCueIds.tryAcquire(cue.cue.id)) return
        val riskType = cue.risk.type.lowercase(Locale.US)
        val speechText = hazardSpeechText(riskType, cue.risk.direction, cue.speech.text) ?: return
        _uiState.update {
            it.copy(
                message = if (riskType == "fall_detected") "检测到跌倒" else "盲杖新提示",
                voiceTranscript = speechText
            )
        }
        speakText(
            speechText,
            priority = if (riskType == "fall_detected") TtsPriority.EMERGENCY else hardwareRiskTtsPriority(riskType),
            bypassTextCooldown = true,
            requiresOnlineCane = true
        )
    }

    private fun ensureLocationUpdates() {
        if (locationUpdatesActive || !hasLocationPermission()) return
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = phoneLocationListener ?: LocationListener { location ->
            lastKnownPhoneLocation = location
            latestContinuousLocation = location
        }.also { phoneLocationListener = it }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        providers.forEach { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 3_000L, 1f, listener, appContext.mainLooper)
                }
            }
        }
        locationUpdatesActive = true
    }

    private fun currentPhoneLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providerLocations = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider ->
                runCatching {
                    if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
                }.getOrNull()
            }
        return selectBestLocation(
            providerLocations + listOfNotNull(latestContinuousLocation, lastKnownPhoneLocation),
            LOCATION_MAX_AGE_MS,
            LOCATION_MAX_ACCURACY_M
        )?.also {
            lastKnownPhoneLocation = it
            latestContinuousLocation = it
        }
    }

    private fun currentNavigationLocation(): Location? {
        ensureLocationUpdates()
        return currentPhoneLocation()?.takeIf {
            isUsableLocation(it, NAVIGATION_LOCATION_MAX_AGE_MS, NAVIGATION_LOCATION_MAX_ACCURACY_M)
        }
    }

    private fun selectBestLocation(locations: List<Location>, maxAgeMs: Long, maxAccuracyM: Float): Location? =
        locations
            .filter { isUsableLocation(it, maxAgeMs, maxAccuracyM) }
            .maxWithOrNull(compareBy<Location> { locationScore(it) }.thenBy { it.time })

    private fun isUsableLocation(location: Location, maxAgeMs: Long, maxAccuracyM: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && location.isMock) return false
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && location.isFromMockProvider) return false
        val now = System.currentTimeMillis()
        if (location.time <= 0L || now - location.time > maxAgeMs) return false
        if (location.hasAccuracy() && location.accuracy > maxAccuracyM) return false
        return true
    }

    private fun locationScore(location: Location): Long {
        val ageScore = (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
        val accuracyScore = if (location.hasAccuracy()) location.accuracy.toLong() * 1_000L else 80_000L
        return -(ageScore + accuracyScore)
    }


    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun riskLevelLabel(level: String): String = when (level.lowercase(Locale.US)) {
        "high" -> "高"
        "medium" -> "中"
        else -> "低"
    }

    fun dismissUrgentAlert() {
        _uiState.update { it.copy(urgentAlert = null) }
    }

    fun startVoiceInput() {
        if (_uiState.value.voiceState == VoiceState.Idle) startVoiceListening()
    }

    fun stopVoiceInput() {
        if (_uiState.value.voiceState == VoiceState.Listening) stopVoiceListening("已收到")
    }

    fun toggleVoiceListening() {
        when (_uiState.value.voiceState) {
            VoiceState.Listening -> stopVoiceRecordingAndSubmit()
            VoiceState.Speaking -> Unit
            VoiceState.Processing -> Unit
            else -> startVoiceRecording()
        }
    }

    fun startVoicePress() {
        if (_uiState.value.voiceState == VoiceState.Idle) {
            startVoiceRecording()
        }
    }

    fun endVoicePress() {
        if (_uiState.value.voiceState == VoiceState.Listening) {
            stopVoiceRecordingAndSubmit()
        }
    }

    fun repeatNavigationPrompt() {
        val state = _uiState.value
        val text = state.lastSpokenText
            ?: state.urgentAlert?.voicePrompt?.takeIf { it.isNotBlank() }
            ?: state.urgentAlert?.message
            ?: "暂无可重复播报内容"
        speakText(text)
    }

    fun speakText(text: String) {
        speakText(text, listenAfter = false, priority = inferTtsPriority(text))
    }

    private fun speakText(
        text: String,
        priority: TtsPriority,
        bypassTextCooldown: Boolean = false,
        requiresOnlineCane: Boolean = false
    ) {
        speakText(
            text,
            listenAfter = false,
            priority = priority,
            bypassTextCooldown = bypassTextCooldown,
            requiresOnlineCane = requiresOnlineCane
        )
    }

    private fun inferTtsPriority(text: String): TtsPriority = when {
        text.contains("SOS", ignoreCase = true) || text.contains("跌倒") || text.contains("告警") -> TtsPriority.EMERGENCY
        text.contains("台阶") || text.contains("坑") || text.contains("下视") -> TtsPriority.STEP
        text.contains("请停") || text.contains("停止") -> TtsPriority.OBSTACLE_STOP
        text.contains("导航") || text.contains("转") || text.contains("到达") || text.contains("规划") || text.contains("偏航") -> TtsPriority.NAVIGATION
        text.contains("风险") -> TtsPriority.ROAD_RISK
        else -> TtsPriority.NORMAL
    }

    private fun speechKey(text: String): String = text.trim().lowercase(Locale.CHINA)

    private fun speakText(
        text: String,
        listenAfter: Boolean,
        priority: TtsPriority = inferTtsPriority(text),
        fromQueue: Boolean = false,
        bypassTextCooldown: Boolean = false,
        requiresOnlineCane: Boolean = false
    ) {
        val cleanText = compactSpeechText(text)
        if (cleanText.isBlank()) return
        if (requiresOnlineCane && speechCaneDeviceId() == null) return
        val now = System.currentTimeMillis()
        val key = speechKey(cleanText)
        if (!fromQueue && !bypassTextCooldown && now - (speechCooldowns[key] ?: 0L) < 12_000L) return
        if (!fromQueue && !bypassTextCooldown && pendingSpeech.any { speechKey(it.text) == key }) return
        val currentPriority = activeTtsPriority
        if (activeTtsUtteranceId != null && currentPriority != null) {
            if (!shouldInterruptCurrentSpeech(currentPriority, priority)) {
                pendingSpeech += QueuedSpeech(cleanText, listenAfter, priority, requiresOnlineCane)
                pendingSpeech.sortByDescending { it.priority.rank }
                return
            }
            pendingSpeech.removeAll { it.priority.rank < priority.rank }
            tts?.stop()
            activeTtsUtteranceId = null
        }
        speechCooldowns[key] = now
        if (voiceRecognitionActive) {
            speechRecognizer?.cancel()
            voiceRecognitionActive = false
        }
        val utteranceId = "smartcane_${System.currentTimeMillis()}"
        activeTtsUtteranceId = utteranceId
        activeTtsPriority = priority
        _uiState.update { it.copy(lastSpokenText = cleanText, voiceState = VoiceState.Speaking, message = "正在播报") }

        fun finishSpeaking() {
            scope.launch {
                if (activeTtsUtteranceId != utteranceId) return@launch
                activeTtsUtteranceId = null
                activeTtsPriority = null
                if (listenAfter) {
                    _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "请按住说话", voiceTranscript = "请按住说话") }
                } else {
                    _uiState.update {
                        if (it.voiceState == VoiceState.Speaking) {
                            it.copy(voiceState = VoiceState.Idle)
                        } else {
                            it
                        }
                    }
                }
                while (true) {
                    val next = pendingSpeech.removeFirstOrNull() ?: break
                    if (!next.requiresOnlineCane || speechCaneDeviceId() != null) {
                        speakText(
                            next.text,
                            next.listenAfter,
                            next.priority,
                            fromQueue = true,
                            requiresOnlineCane = next.requiresOnlineCane
                        )
                        break
                    }
                }
            }
        }

        fun speakWith(engine: TextToSpeech) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(doneUtteranceId: String?) {
                    if (doneUtteranceId == utteranceId) finishSpeaking()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(errorUtteranceId: String?) {
                    if (errorUtteranceId == utteranceId) finishSpeaking()
                }
            })
            val result = engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                finishSpeaking()
            }
        }

        val engine = tts
        if (engine == null) {
            tts = TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.CHINESE
                    tts?.let(::speakWith)
                } else {
                    finishSpeaking()
                }
            }
        } else if (ttsReady) {
            speakWith(engine)
        } else {
            finishSpeaking()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceRecording() {
        if (!hasAudioPermission()) {
            _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "请给 App 开启麦克风权限", voiceTranscript = "请给 App 开启麦克风权限") }
            return
        }
        val sampleRate = 16_000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "录音设备不可用", voiceTranscript = "录音设备不可用") }
            return
        }

        val bufferSize = maxOf(minBufferSize, sampleRate / 2)
        val file = File(appContext.cacheDir, "smartcane_voice_${System.currentTimeMillis()}.pcm")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "录音初始化失败", voiceTranscript = "录音初始化失败") }
            return
        }

        voiceRecorder = recorder
        voiceRecordingFile = file
        voiceRecognitionActive = true
        _uiState.update { it.copy(voiceState = VoiceState.Listening, message = "正在录音", voiceTranscript = null) }
        voiceRecordingJob = scope.launch(Dispatchers.IO) {
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(bufferSize)
                runCatching { recorder.startRecording() }
                while (voiceRecognitionActive) {
                    val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(0)
                    if (read > 0) output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun stopVoiceRecordingAndSubmit() {
        val file = voiceRecordingFile
        val recorder = voiceRecorder
        val job = voiceRecordingJob
        voiceRecognitionActive = false
        runCatching { recorder?.stop() }
        scope.launch {
            job?.join()
            runCatching { recorder?.release() }
            voiceRecorder = null
            voiceRecordingJob = null
            voiceRecordingFile = null

            if (file == null || !file.exists() || file.length() < 3_200L) {
                _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "没有录到声音，请再按住说一次", voiceTranscript = "没有录到声音，请再按住说一次") }
                return@launch
            }

            _uiState.update { it.copy(voiceState = VoiceState.Processing, message = "正在识别语音", voiceTranscript = "正在识别语音…") }
            ensureLocationUpdates()
            val location = currentPhoneLocation()
            val deviceId = currentCaneDeviceId()
            if (deviceId.isBlank()) {
                _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "请先绑定真实盲杖设备") }
                return@launch
            }
            when (val result = SmartCaneApiClient.postVoiceCommand(deviceId, file, location?.latitude, location?.longitude)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            voiceState = VoiceState.Idle,
                            message = "你说：${result.data.transcript}",
                            voiceTranscript = result.data.transcript
                        )
                    }
                    speakVoiceCommandResult(result.data, deviceId)
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(voiceState = VoiceState.Idle, message = result.message, voiceTranscript = result.message) }
                    speakText("语音请求失败，请检查网络或后端服务")
                }
            }
            runCatching { file.delete() }
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startVoiceListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            startBackendVoiceRecording()
            return
        }

        val recognizer = runCatching {
            speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
                speechRecognizer = it
                it.setRecognitionListener(createRecognitionListener())
            }
        }.getOrElse {
            startBackendVoiceRecording()
            return
        }

        startSystemRecognizer(recognizer)
    }

    private fun createRecognitionListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _uiState.update { state ->
                state.copy(
                    voiceState = VoiceState.Listening,
                    message = "\u6b63\u5728\u542c\u4f60\u8bf4",
                    voiceTranscript = null
                )
            }
        }

        override fun onBeginningOfSpeech() {
            _uiState.update { state -> state.copy(message = "\u6b63\u5728\u8bc6\u522b", voiceTranscript = "\u6b63\u5728\u8bc6\u522b\u2026") }
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            voiceRecognitionActive = false
            _uiState.update { state -> state.copy(message = "\u6b63\u5728\u7406\u89e3", voiceTranscript = state.voiceTranscript ?: "\u6b63\u5728\u6574\u7406\u5b57\u5e55\u2026") }
        }

        override fun onError(error: Int) {
            voiceRecognitionActive = false
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "\u6ca1\u542c\u6e05\uff0c\u8bf7\u518d\u6309\u4e00\u6b21\u6309\u94ae"
                SpeechRecognizer.ERROR_AUDIO -> "\u9ea6\u514b\u98ce\u5f02\u5e38"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "\u8bed\u97f3\u7f51\u7edc\u4e0d\u53ef\u7528\uff0c\u6b63\u5728\u5207\u6362\u5230\u540e\u7aef\u8bc6\u522b"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "\u8bf7\u7ed9 App \u5f00\u542f\u9ea6\u514b\u98ce\u6743\u9650"
                else -> "\u7cfb\u7edf\u8bed\u97f3\u8bc6\u522b\u5931\u8d25\uff0c\u6b63\u5728\u5207\u6362\u5230\u540e\u7aef\u8bc6\u522b"
            }
            _uiState.update { state -> state.copy(voiceState = VoiceState.Idle, message = message, voiceTranscript = message) }
            if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_CLIENT) {
                startBackendVoiceRecording()
            }
        }

        override fun onResults(results: Bundle?) {
            voiceRecognitionActive = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (text.isNullOrBlank()) {
                _uiState.update { state -> state.copy(voiceState = VoiceState.Idle, message = "\u6ca1\u542c\u6e05\uff0c\u8bf7\u518d\u8bd5\u4e00\u6b21", voiceTranscript = "\u6ca1\u542c\u6e05\uff0c\u8bf7\u518d\u8bd5\u4e00\u6b21") }
                return
            }

            scope.launch {
                _uiState.update { state -> state.copy(voiceState = VoiceState.Idle, message = "\u4f60\u8bf4\uff1a$text", voiceTranscript = text) }
                startPhoneLocationUpdates()
                val location = latestPhoneLocation()
                val deviceId = currentCaneDeviceId()
                when (val result = SmartCaneApiClient.postVoiceRoute(
                    text, location?.latitude, location?.longitude,
                    deviceId = deviceId, routePreference = _uiState.value.navigationPreference
                )) {
                    is ApiResult.Success -> {
                        if (activateNavigationRoute(result.data, deviceId)) {
                            speakText(
                                plannedRouteSpeech(result.data.voicePrompt),
                                priority = TtsPriority.NAVIGATION
                            )
                        } else {
                            speakText(result.data.voicePrompt.ifBlank { "已收到路线请求" })
                        }
                    }
                    is ApiResult.Failure -> speakText("\u8bed\u97f3\u6307\u4ee4\u5df2\u6536\u5230\uff0c\u4f46\u540e\u7aef\u6682\u65f6\u4e0d\u53ef\u7528")
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partialText = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (!partialText.isNullOrBlank()) {
                _uiState.update { state -> state.copy(voiceTranscript = partialText, message = "\u6b63\u5728\u8bc6\u522b") }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun startSystemRecognizer(recognizer: SpeechRecognizer) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "\u8bf7\u8bf4\u51fa\u76ee\u7684\u5730\u6216\u64cd\u4f5c\u6307\u4ee4")
        }

        try {
            voiceRecognitionActive = true
            _uiState.update { it.copy(voiceState = VoiceState.Listening, message = "\u6b63\u5728\u542c\u4f60\u8bf4", voiceTranscript = null) }
            recognizer.startListening(intent)
        } catch (_: SecurityException) {
            voiceRecognitionActive = false
            _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "\u8bf7\u5728\u7cfb\u7edf\u8bbe\u7f6e\u4e2d\u5f00\u542f\u9ea6\u514b\u98ce\u6743\u9650", voiceTranscript = "\u8bf7\u5728\u7cfb\u7edf\u8bbe\u7f6e\u4e2d\u5f00\u542f\u9ea6\u514b\u98ce\u6743\u9650") }
        } catch (_: Exception) {
            voiceRecognitionActive = false
            startBackendVoiceRecording()
        }
    }

    private fun startBackendVoiceRecording() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission) {
            _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "\u8bf7\u5f00\u542f\u9ea6\u514b\u98ce\u6743\u9650\u540e\u518d\u8bf4\u8bdd", voiceTranscript = "\u8bf7\u5f00\u542f\u9ea6\u514b\u98ce\u6743\u9650\u540e\u518d\u8bf4\u8bdd") }
            return
        }
        if (backendVoiceRecordingActive) return

        val file = File(appContext.cacheDir, "smartcane_voice_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(16_000)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            backendVoiceRecorder = recorder
            backendVoiceFile = file
            backendVoiceRecordingActive = true
            _uiState.update {
                it.copy(
                    voiceState = VoiceState.Listening,
                    message = "\u7cfb\u7edf\u8bed\u97f3\u4e0d\u53ef\u7528\uff0c\u5df2\u5207\u6362\u5230\u540e\u7aef\u5f55\u97f3\u8bc6\u522b",
                    voiceTranscript = "\u6b63\u5728\u5f55\u97f3\uff0c\u677e\u5f00\u6309\u94ae\u540e\u8bc6\u522b"
                )
            }
            scope.launch {
                delay(7_000L)
                if (backendVoiceRecordingActive) stopBackendVoiceRecordingAndUpload("\u6b63\u5728\u8bc6\u522b\u8bed\u97f3")
            }
        } catch (_: Exception) {
            runCatching { recorder.release() }
            backendVoiceRecorder = null
            backendVoiceFile = null
            backendVoiceRecordingActive = false
            _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "\u65e0\u6cd5\u542f\u52a8\u5f55\u97f3\uff0c\u8bf7\u68c0\u67e5\u9ea6\u514b\u98ce\u6743\u9650", voiceTranscript = "\u65e0\u6cd5\u542f\u52a8\u5f55\u97f3\uff0c\u8bf7\u68c0\u67e5\u9ea6\u514b\u98ce\u6743\u9650") }
        }
    }

    private fun stopBackendVoiceRecordingAndUpload(message: String) {
        val recorder = backendVoiceRecorder ?: return
        val file = backendVoiceFile
        backendVoiceRecorder = null
        backendVoiceFile = null
        backendVoiceRecordingActive = false
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        if (file == null || !file.exists() || file.length() <= 0L) {
            _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "\u6ca1\u6709\u5f55\u5230\u58f0\u97f3\uff0c\u8bf7\u518d\u8bd5\u4e00\u6b21", voiceTranscript = "\u6ca1\u6709\u5f55\u5230\u58f0\u97f3\uff0c\u8bf7\u518d\u8bd5\u4e00\u6b21") }
            return
        }

        scope.launch {
            _uiState.update { it.copy(voiceState = VoiceState.Speaking, message = message, voiceTranscript = "\u6b63\u5728\u4e0a\u4f20\u5230\u540e\u7aef\u8bc6\u522b\u2026") }
            val deviceId = currentCaneDeviceId()
            if (deviceId.isBlank()) {
                _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "请先绑定真实盲杖设备") }
                return@launch
            }
            val location = currentNavigationLocation()
            if (location == null) {
                _uiState.update {
                    it.copy(
                        voiceState = VoiceState.Idle,
                        message = "当前位置不稳定，请到室外或开启精确定位后再试",
                        voiceTranscript = "当前位置不稳定，暂不能发起导航"
                    )
                }
                speakText("当前位置不稳定，请到室外或开启精确定位后再试。")
                runCatching { file.delete() }
                return@launch
            }
            when (val result = SmartCaneApiClient.postVoiceCommandAudio(deviceId, file, location.latitude, location.longitude)) {
                is ApiResult.Success -> {
                    val transcript = result.data.transcript.ifBlank { "\u5df2\u6536\u5230\u8bed\u97f3" }
                    _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "\u4f60\u8bf4\uff1a$transcript", voiceTranscript = transcript) }
                    speakVoiceCommandResult(result.data, deviceId)
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "\u8bed\u97f3\u8bc6\u522b\u6682\u65f6\u4e0d\u53ef\u7528", voiceTranscript = result.message) }
                    speakText("\u8bed\u97f3\u8bc6\u522b\u6682\u65f6\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002")
                }
            }
            runCatching { file.delete() }
        }
    }

    private fun stopVoiceListening(message: String) {
        if (backendVoiceRecordingActive) {
            stopBackendVoiceRecordingAndUpload(message)
            return
        }
        if (voiceRecognitionActive) {
            speechRecognizer?.stopListening()
        }
        voiceRecognitionActive = false
        _uiState.update { it.copy(voiceState = VoiceState.Idle, message = message, voiceTranscript = it.voiceTranscript ?: message) }
    }

    private fun startLocalSosAlarm() {
        sosAlarmJob?.cancel()
        sosAlarmJob = scope.launch {
            speakText(
                "已发起紧急求助，请在安全地带等候",
                TtsPriority.EMERGENCY,
                bypassTextCooldown = true
            )
        }
    }

    fun sendBlindSos() {
        if (_uiState.value.sosState == SosActionState.Sending) return
        scope.launch {
            sosAlarmJob?.cancel()
            _uiState.update { it.copy(sosState = SosActionState.Sending, message = "正在发送 SOS，并持续呼救 30 秒") }
            startPhoneLocationUpdates()
            val location = latestPhoneLocation()
            val deviceId = currentCaneDeviceId()
            if (deviceId.isBlank()) {
                sosAlarmJob?.cancel()
                _uiState.update { it.copy(sosState = SosActionState.Error, message = "请先绑定真实盲杖设备") }
                return@launch
            }
            if (location != null) {
                SmartCaneApiClient.postLocation(
                    LocationUploadDto(
                        deviceId = deviceId,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        provider = location.provider,
                        quality = if (location.isFromMockProvider) "mock" else "usable",
                        accuracyM = location.accuracy.takeIf { it > 0f },
                        bearingDeg = location.bearing.takeIf { location.hasBearing() }
                    )
                )
            }
            val result = SmartCaneApiClient.postSos(
                SosRequestDto(
                    deviceId,
                    location?.latitude,
                    location?.longitude,
                    "用户端发起 SOS 紧急求助，请立即联系并查看地图位置"
                )
            )
            when (result) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(sosState = SosActionState.Success, message = "SOS 已发送") }
                    startLocalSosAlarm()
                }
                is ApiResult.Failure -> {
                    sosAlarmJob?.cancel()
                    _uiState.update { it.copy(sosState = SosActionState.Error, message = "发送失败") }
                }
            }
        }
    }

    fun stopNavigation() {
        NavigationLocationService.stop(appContext)
        announcedNavigationSteps.clear()
        announcedTrafficWarnings.clear()
        _uiState.update {
            it.copy(
                navigationStatus = "idle",
                activeNavigationRoute = null,
                alternativeNavigationRoutes = emptyList(),
                navigationArrived = false
            )
        }
    }

    fun setNavigationPreference(preference: String) {
        if (preference !in setOf("safe", "distance")) return
        _uiState.update { it.copy(navigationPreference = preference) }
        speakText(if (preference == "safe") "已选择安全优先。" else "已选择距离优先。", priority = TtsPriority.NORMAL)
    }

    fun release() {
        stopPairingPolling()
        stopAlertPolling()
        stopBlindRiskProximityMonitoring()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        activeTtsUtteranceId = null
        activeTtsPriority = null
        pendingSpeech.clear()
        voiceRecognitionActive = false
        voiceRecordingJob?.cancel()
        voiceRecordingJob = null
        runCatching { voiceRecorder?.stop() }
        runCatching { voiceRecorder?.release() }
        voiceRecorder = null
        runCatching { voiceRecordingFile?.delete() }
        voiceRecordingFile = null
        if (backendVoiceRecordingActive) {
            runCatching { backendVoiceRecorder?.stop() }
            runCatching { backendVoiceRecorder?.release() }
        }
        backendVoiceRecorder = null
        backendVoiceFile = null
        backendVoiceRecordingActive = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        if (navigationReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(navigationReceiver) }
            navigationReceiverRegistered = false
        }
    }

    fun relation(): CareRelation? = _uiState.value.currentRelation

    fun relationUpdateText(): String {
        val millis = _uiState.value.currentRelation?.updatedAtMillis
            ?: _uiState.value.storedState.relationUpdatedAtMillis
            ?: return "??"
        return SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))
    }

    companion object {
        private const val SELF_SOS_REPLAY_LOG_TAG = "SelfSosReplay"
        private const val LOCATION_MAX_AGE_MS = 5 * 60 * 1000L
        private const val NAVIGATION_LOCATION_MAX_AGE_MS = 90 * 1000L
        private const val LOCATION_MAX_ACCURACY_M = 80f
        private const val NAVIGATION_LOCATION_MAX_ACCURACY_M = 50f
        @Volatile private var INSTANCE: SmartCaneAppController? = null
        fun get(context: Context): SmartCaneAppController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val prefs = LocalAppPreferences(context.applicationContext)
                    val controller = SmartCaneAppController(
                        authRepository = RemoteAuthRepository(prefs),
                        pairingRepository = RemotePairingRepository(prefs),
                        preferences = prefs,
                        appContext = context.applicationContext
                    )
                    INSTANCE = controller
                    controller
                }
            }
        }
    }
}

enum class VoiceState(val label: String) { Idle("按住说话"), Listening("正在录音"), Processing("正在识别"), Speaking("正在播报") }
enum class SosActionState { Idle, Sending, Success, Error }

internal fun alertSpeechForRole(
    role: String,
    riskType: String,
    voicePrompt: String,
    message: String,
    sosAlarmActive: Boolean,
    distanceMm: Int? = null
): String? {
    if (riskType == "sos") {
        return if (role == "companion") "用户发起紧急求助" else null
    }
    return null
}

internal const val RISK_POINT_SPEECH_COOLDOWN_MS = 5 * 60 * 1000L
internal const val FRONT_OBSTACLE_SPEECH = "前方障碍，请减速"
internal const val GROUND_DROP_SPEECH = "前方落差，请减速"

internal fun hazardSpeechText(riskType: String, direction: String, serverText: String): String? {
    val normalizedType = riskType.trim().lowercase(Locale.US)
    val normalizedDirection = direction.trim().lowercase(Locale.US)
    val text = serverText.trim()
    return when {
        normalizedType == "front_obstacle" -> FRONT_OBSTACLE_SPEECH
        normalizedType == "ground_step_up" -> FRONT_OBSTACLE_SPEECH
        normalizedType == "ground_step" && (
            normalizedDirection == "down" || text.contains("下台阶") || text.contains("落差") || text.contains("坑洼")
        ) -> GROUND_DROP_SPEECH
        normalizedType == "ground_step" -> FRONT_OBSTACLE_SPEECH
        normalizedType in setOf("ground_drop", "ground_step_down", "down_no_target") -> GROUND_DROP_SPEECH
        normalizedType == "left_obstacle" -> "左侧有障碍"
        normalizedType == "right_obstacle" -> "右侧有障碍"
        text.isBlank() -> null
        else -> text
    }
}

internal fun nearbyRiskPointSpeechText(riskType: String, serverText: String): String? {
    val text = serverText.trim()
    return text.ifBlank { null }
}

internal const val ROUTE_PLANNED_CONFIRMATION = "收到，已规划好最佳路线"

internal fun plannedRouteSpeech(routePrompt: String): String {
    val details = routePrompt.trim()
    return if (details.isBlank()) ROUTE_PLANNED_CONFIRMATION else "$ROUTE_PLANNED_CONFIRMATION。$details"
}

internal fun compactSpeechText(text: String): String {
    val normalized = text.trim()
    if (normalized.isBlank()) return ""
    val compactForMatching = normalized.replace(Regex("\\s+"), "")
    if (compactForMatching.contains("疑似跌倒")) return ""
    return when {
        compactForMatching.contains("AndroidApp") && compactForMatching.contains("紧急求助") -> "用户发起紧急求助"
        else -> normalized
    }
}

internal class RiskPointSpeechCooldown(
    private val cooldownMs: Long = RISK_POINT_SPEECH_COOLDOWN_MS
) {
    private val spokenAtByRiskPoint = mutableMapOf<Int, Long>()

    fun tryAcquire(riskPointId: Int, now: Long): Boolean {
        val lastSpokenAt = spokenAtByRiskPoint[riskPointId]
        if (lastSpokenAt != null && now - lastSpokenAt < cooldownMs) return false
        spokenAtByRiskPoint.entries.removeAll { now - it.value >= cooldownMs }
        spokenAtByRiskPoint[riskPointId] = now
        return true
    }
}

internal class RiskPointApproachGate {
    private var initialized = false
    private var currentRiskPointId: Int? = null

    fun shouldSpeak(riskPointId: Int?): Boolean {
        if (!initialized) {
            initialized = true
            currentRiskPointId = riskPointId
            return false
        }
        if (riskPointId == null) {
            currentRiskPointId = null
            return false
        }
        if (currentRiskPointId == riskPointId) return false
        currentRiskPointId = riskPointId
        return true
    }

    fun reset() {
        initialized = false
        currentRiskPointId = null
    }
}

internal class EventSpeechGate(
    private val retainedEventCount: Int = 512
) {
    private val spokenEventIds = LinkedHashSet<Int>()

    fun tryAcquire(eventId: Int): Boolean {
        if (eventId <= 0 || !spokenEventIds.add(eventId)) return false
        while (spokenEventIds.size > retainedEventCount) {
            spokenEventIds.remove(spokenEventIds.first())
        }
        return true
    }

    fun clear() {
        spokenEventIds.clear()
    }
}

internal class CueIdSpeechGate(private val retainedCueCount: Int = 512) {
    private val seenCueIds = LinkedHashSet<String>()

    fun tryAcquire(cueId: String): Boolean {
        val normalized = cueId.trim()
        if (normalized.isBlank() || !seenCueIds.add(normalized)) return false
        while (seenCueIds.size > retainedCueCount) {
            seenCueIds.remove(seenCueIds.first())
        }
        return true
    }

    fun clear() {
        seenCueIds.clear()
    }
}

internal const val DEVICE_EVENT_FRESHNESS_SECONDS = 10L
internal const val LOCAL_CUE_FRESHNESS_SECONDS = 3L
internal const val DEVICE_HEARTBEAT_FRESHNESS_SECONDS = 15L

internal fun speechCaneDeviceId(relation: CareRelation?): String? =
    relation
        ?.takeIf { it.status == RelationStatus.Active && it.caneDevice.online }
        ?.caneDevice?.deviceId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun isRecentDeviceHeartbeat(
    updatedAt: String,
    nowMillis: Long = System.currentTimeMillis(),
    freshnessSeconds: Long = DEVICE_HEARTBEAT_FRESHNESS_SECONDS
): Boolean = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { isLenient = false }
    val withoutFraction = updatedAt.replace(Regex("\\.\\d+(Z|[+-]\\d{2}:\\d{2})$"), "$1")
    val updatedAtMillis = parser.parse(withoutFraction)?.time ?: return@runCatching false
    val ageMillis = nowMillis - updatedAtMillis
    ageMillis in 0..(freshnessSeconds * 1_000L)
}.getOrDefault(false)

internal fun isFreshDeviceEvent(
    eventTimestamp: String,
    serverTimestamp: String,
    freshnessSeconds: Long = DEVICE_EVENT_FRESHNESS_SECONDS
): Boolean = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { isLenient = false }
    fun withoutFraction(value: String): String =
        value.replace(Regex("\\.\\d+(Z|[+-]\\d{2}:\\d{2})$"), "$1")
    val eventTime = parser.parse(withoutFraction(eventTimestamp)) ?: return@runCatching false
    val serverTime = parser.parse(withoutFraction(serverTimestamp)) ?: return@runCatching false
    val ageSeconds = (serverTime.time - eventTime.time) / 1_000L
    ageSeconds in 0..freshnessSeconds
}.getOrDefault(false)

internal fun shouldSpeakLocalCue(cue: LocalCueDto, currentDeviceId: String): Boolean {
    if (cue.eventKind != "local_cue") return false
    if (cue.deviceId != currentDeviceId || cue.cue.id.isBlank() || cue.cue.repeat) return false
    if (!cue.speech.shouldSpeak || cue.speech.text.isBlank()) return false
    if (!isFreshDeviceEvent(cue.timestamp, cue.serverTime, LOCAL_CUE_FRESHNESS_SECONDS)) return false
    if (cue.risk.type == "fall_detected") {
        val fall = cue.fall ?: return false
        return fall.detected &&
            fall.eventId.isNotBlank() &&
            fall.eventId == cue.cue.id &&
            cue.cue.source == "formal_fall"
    }
    return true
}

internal class RiskEpisodeTracker(
    private val trustedClearThreshold: Int = 3
) {
    private var activeKey: String? = null
    private var trustedClearCount = 0

    fun enter(key: String): Boolean {
        observeActive()
        if (activeKey == key) return false
        activeKey = key
        return true
    }

    fun observeActive() {
        trustedClearCount = 0
    }

    fun observeTrustedClear(): Boolean {
        if (activeKey == null) return false
        trustedClearCount++
        if (trustedClearCount < trustedClearThreshold) return false
        activeKey = null
        trustedClearCount = 0
        return true
    }

    fun observeUnknown() {
        trustedClearCount = 0
    }
}

internal const val NON_NAVIGATION_RISK_WARNING_RADIUS_M = 10

internal fun isNavigationInProgress(status: String): Boolean =
    status.lowercase(Locale.US) in setOf("active", "replanning", "off_route")

internal fun hardwareRiskTtsPriority(riskType: String): TtsPriority {
    val normalized = riskType.lowercase(Locale.US)
    return if (normalized.contains("ground") || normalized.contains("drop") || normalized.contains("down_sensor")) {
        TtsPriority.STEP
    } else {
        TtsPriority.OBSTACLE_STOP
    }
}

enum class TtsPriority(val rank: Int) {
    NORMAL(1), ROAD_RISK(2), NAVIGATION(3), OBSTACLE_STOP(4), STEP(5), EMERGENCY(6)
}

internal fun shouldInterruptCurrentSpeech(current: TtsPriority, incoming: TtsPriority): Boolean {
    if (current == TtsPriority.NAVIGATION) return false
    return incoming.rank > current.rank
}

data class QueuedSpeech(
    val text: String,
    val listenAfter: Boolean,
    val priority: TtsPriority,
    val requiresOnlineCane: Boolean = false
)

data class AppUiState(
    val storedState: StoredAppState,
    val isBusy: Boolean = false,
    val message: String? = null,
    val lastPairingPreview: PairingCode? = null,
    val pairingStatus: PairingFlowStatus = PairingFlowStatus.Idle,
    val pendingRequest: CareRequest? = null,
    val currentRelation: CareRelation? = null,
    val voiceState: VoiceState = VoiceState.Idle,
    val sosState: SosActionState = SosActionState.Idle,
    val isNavigationPaused: Boolean = false,
    val lastSpokenText: String? = null,
    val voiceTranscript: String? = null,
    val urgentAlert: EmergencyAlertDto? = null
    ,
    val navigationStatus: String = "idle",
    val currentStepIndex: Int = 0,
    val currentNavigationInstruction: String = "",
    val distanceToRouteM: Double = 0.0,
    val distanceToDestinationM: Double = 0.0,
    val navigationArrived: Boolean = false
    ,
    val fallPending: Boolean = false,
    val fallStage: String? = null
    ,
    val activeNavigationRoute: NavigationRouteDto? = null,
    val alternativeNavigationRoutes: List<NavigationRouteDto> = emptyList(),
    val selectedRouteIndex: Int? = null
    ,
    val navigationPreference: String = "safe"
) {
    val isLoggedIn: Boolean get() = storedState.isLoggedIn
    val currentUser: UserProfile? get() = storedState.currentUser
    val currentMode: AppMode? get() = storedState.lastMode
    val shouldShowModeSelection: Boolean get() = isLoggedIn && currentMode == null
}

private fun UserRole.defaultMode(): AppMode = when (this) {
    UserRole.Blind -> AppMode.Blind
    UserRole.Companion -> AppMode.Companion
}
