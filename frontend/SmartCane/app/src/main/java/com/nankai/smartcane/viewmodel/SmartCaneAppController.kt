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
import com.nankai.smartcane.data.model.UserProfile
import com.nankai.smartcane.data.model.UserRole
import com.nankai.smartcane.data.network.ApiResult
import com.nankai.smartcane.data.network.DeviceStateDto
import com.nankai.smartcane.data.network.EmergencyAlertDto
import com.nankai.smartcane.data.network.LocationUploadDto
import com.nankai.smartcane.data.network.NearbyRiskWarningDto
import com.nankai.smartcane.data.network.NavigationRouteDto
import com.nankai.smartcane.data.network.SmartCaneApiClient
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
    private var nearbyRiskPollingJob: Job? = null
    private var hardwareRiskPollingJob: Job? = null
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
    private var lastHardwareRiskSignature: String? = null
    private var lastHardwareRiskSpokenAt: Long = 0L
    private var suppressHardwareRiskUntilAfterFall: Long = 0L
    private val hardwareRiskSpeechTimes: MutableMap<String, Long> = mutableMapOf()
    private var lastNearbyRiskText: String? = null
    private var lastNearbyRiskTextSpokenAt: Long = 0L
    private val announcedNearbyRiskIds = mutableSetOf<Int>()
    private val nearbyRiskSpeechTimes: MutableMap<Int, Long> = mutableMapOf()
    private var navigationReceiverRegistered = false
    private val navigationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NavigationLocationService.ACTION_STATE_CHANGED -> {
                    val arrived = intent.getBooleanExtra(NavigationLocationService.EXTRA_ARRIVED, false)
                    val offRoute = intent.getBooleanExtra(NavigationLocationService.EXTRA_OFF_ROUTE, false)
                    val stepIndex = intent.getIntExtra(NavigationLocationService.EXTRA_STEP_INDEX, 0)
                    val instruction = intent.getStringExtra(NavigationLocationService.EXTRA_INSTRUCTION).orEmpty()
                    val distanceToRoute = intent.getDoubleExtra(NavigationLocationService.EXTRA_DISTANCE_TO_ROUTE_M, 0.0)
                    val distanceToDestination = intent.getDoubleExtra(NavigationLocationService.EXTRA_DISTANCE_TO_DESTINATION_M, 0.0)
                    val distanceToNextAction = intent.getDoubleExtra(NavigationLocationService.EXTRA_DISTANCE_TO_NEXT_ACTION_M, Double.MAX_VALUE)
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
                    else maybeSpeakNavigationStep(stepIndex, instruction, distanceToNextAction, distanceToDestination)
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
                    speakText(if (success) "重新规划完成。" else "重新规划失败，请停在安全位置。", priority = TtsPriority.NAVIGATION)
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

    private fun currentCaneDeviceId(): String =
        _uiState.value.currentRelation?.caneDevice?.deviceId
            ?: DemoData.defaultCane.deviceId.takeIf { _uiState.value.currentUser?.isDemo == true }
            ?: ""

    private fun isFromCurrentCane(deviceId: String): Boolean =
        deviceId.split(",").map { it.trim() }.contains(currentCaneDeviceId())

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
            val relation = result.getOrNull()
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
                val relation = pairingRepository.getCurrentRelation(user).getOrNull()
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
                val relation = relationResult.getOrNull()
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

                val location = latestPhoneLocation()
                if (location == null) {
                    _uiState.update {
                        if (it.message.isNullOrBlank()) it.copy(message = "\u8bf7\u5f00\u542f\u5b9a\u4f4d\u6743\u9650\uff0c\u7528\u4e8e\u9644\u8fd1\u98ce\u9669\u70b9\u8bed\u97f3\u63d0\u9192") else it
                    }
                    delay(6_000L)
                    continue
                }

                val deviceId = currentCaneDeviceId()
                if (deviceId.isBlank()) {
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

                when (val result = SmartCaneApiClient.getNearbyRiskWarning(location.latitude, location.longitude, radiusM = 50, bearingDeg = location.bearing.takeIf { location.hasBearing() })) {
                    is ApiResult.Success -> result.data?.let { maybeSpeakNearbyRiskWarning(it) }
                    is ApiResult.Failure -> Unit
                }
                delay(6_000L)
            }
        }
    }

    fun stopBlindRiskProximityMonitoring() {
        blindRiskMonitorJob?.cancel()
        blindRiskMonitorJob = null
        stopPhoneLocationUpdates()
    }

    private fun maybeSpeakNearbyRiskWarning(warning: NearbyRiskWarningDto) {
        if (isFromCurrentCane(warning.deviceId)) return
        val now = System.currentTimeMillis()
        val lastSpokenAt = nearbyRiskSpeechTimes[warning.eventId] ?: 0L
        if (now - lastSpokenAt < 300_000L) return
        if (_uiState.value.voiceState == VoiceState.Listening) return

        val distanceCm = (warning.distanceM * 100).toInt().coerceAtLeast(1)
        val directionText = warning.relativeDirectionText.ifBlank { "前方" }
        val text = "${directionText}${distanceCm}厘米${riskLevelLabel(warning.riskLevel)}风险"
        if (text == lastNearbyRiskText && now - lastNearbyRiskTextSpokenAt < 600_000L) return
        nearbyRiskSpeechTimes[warning.eventId] = now
        lastNearbyRiskText = text
        lastNearbyRiskTextSpokenAt = now
        _uiState.update { it.copy(message = "附近历史风险：${distanceCm}厘米", lastSpokenText = text) }
        speakText(text)
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
        startNearbyRiskPolling()
        startHardwareRiskPolling()
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
                val deviceId = currentCaneDeviceId().ifBlank { null }
                when (val result = SmartCaneApiClient.getLatestAlerts(role, userId, deviceId, lastAlertId)) {
                    is ApiResult.Success -> {
                        val newest = result.data.maxByOrNull { it.id }
                        if (newest != null && newest.id > lastAlertId) {
                            lastAlertId = newest.id
                            if (!alertBaselineReady) {
                                alertBaselineReady = true
                                delay(5_000L)
                                continue
                            }
                            if (newest.riskType == "voice_request" && role == "blind") {
                                _uiState.update {
                                    it.copy(
                                        urgentAlert = null,
                                        voiceState = VoiceState.Speaking,
                                        message = "盲杖按钮已触发"
                                    )
                                }
                                speakText(newest.voicePrompt.ifBlank { newest.message }, listenAfter = true)
                            } else {
                                _uiState.update { it.copy(urgentAlert = newest, message = newest.title) }
                                if (role == "blind" && sosAlarmJob?.isActive != true) {
                                    val alertPrompt = if (newest.riskType == "fall_detected") "检测到跌倒" else newest.voicePrompt.ifBlank { newest.message }
                                    speakText(alertPrompt)
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
        stopNearbyRiskPolling()
        stopHardwareRiskPolling()
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

                val deviceId = currentCaneDeviceId()
                if (deviceId.isBlank()) {
                    delay(1_000L)
                    continue
                }
                when (val result = SmartCaneApiClient.getLatestDeviceState(deviceId)) {
                    is ApiResult.Success -> result.data.state?.let {
                        val wasPending = _uiState.value.fallPending
                        _uiState.update { state -> state.copy(fallPending = it.fallPending, fallStage = it.fallStage) }
                        if (it.fallPending && !wasPending) {
                            speakText("疑似跌倒，按键可取消。", priority = TtsPriority.EMERGENCY)
                        }
                        maybeSpeakHardwareRisk(it)
                    }
                    is ApiResult.Failure -> Unit
                }
                delay(1_000L)
            }
        }
    }

    private fun stopHardwareRiskPolling() {
        hardwareRiskPollingJob?.cancel()
        hardwareRiskPollingJob = null
    }

    private fun maybeSpeakHardwareRisk(state: DeviceStateDto) {
        if (!state.online) return
        if (isNonHardwareSource(state.source)) return

        val level = state.riskLevel.lowercase(Locale.US)
        if (level !in setOf("low", "medium", "high")) return
        val riskType = state.riskType.lowercase(Locale.US)
        if (riskType == "none" || riskType == "history_risk") return
        val isFall = riskType == "fall_detected"
        if (!isFall) {
            if (level == "low") return
            if (riskType.contains("left") && level != "high" && (state.leftCm == null || state.leftCm > 35)) return
            if (riskType.contains("right") && level != "high" && (state.rightCm == null || state.rightCm > 35)) return
            if (riskType.contains("front") && level != "high" && (state.frontCm == null || state.frontCm > 40)) return
        }

        val now = System.currentTimeMillis()
        if (!isFall) {
            if (now < suppressHardwareRiskUntilAfterFall) return
            if (_uiState.value.voiceState != VoiceState.Idle) return
        }

        val prompt = hardwareRiskPrompt(state) ?: state.voicePrompt.takeIf { it.isNotBlank() } ?: return
        val speechKey = hardwareRiskSpeechKey(riskType)
        val lastKeySpokenAt = hardwareRiskSpeechTimes[speechKey] ?: 0L
        if (now - lastKeySpokenAt < 10_000L) return

        val signature = listOf(state.source, riskType, level).joinToString("|")
        if (signature == lastHardwareRiskSignature && now - lastHardwareRiskSpokenAt < 10_000L) return
        lastHardwareRiskSignature = signature
        lastHardwareRiskSpokenAt = now
        hardwareRiskSpeechTimes[speechKey] = now
        if (isFall) {
            suppressHardwareRiskUntilAfterFall = now + 30_000L
        }

        _uiState.update { it.copy(message = "硬件实时风险：${riskLevelLabel(level)}", voiceTranscript = prompt) }
        speakText(prompt, priority = if (isFall) TtsPriority.EMERGENCY else inferTtsPriority(prompt))
    }

    private fun isNonHardwareSource(source: String): Boolean {
        val normalized = source.lowercase(Locale.US)
        return normalized.contains("mock") ||
            normalized.contains("simulator") ||
            normalized.contains("simulation") ||
            normalized.contains("history") ||
            normalized.contains("fake") ||
            normalized.contains("demo")
    }

    private fun hardwareRiskPrompt(state: DeviceStateDto): String? {
        val riskType = state.riskType.lowercase(Locale.US)
        return when {
            riskType == "fall_detected" -> "检测到跌倒"
            riskType.contains("front") -> state.frontCm?.let { "前方${it}厘米有障碍" }
            riskType.contains("left") -> state.leftCm?.let { "左侧${it}厘米有障碍，请向右保持距离" }
            riskType.contains("right") -> state.rightCm?.let { "右侧${it}厘米有障碍，请向左保持距离" }
            riskType.contains("ground") || riskType.contains("drop") -> "前方存在落差，请立即停下并探测台阶"
            riskType.contains("down_sensor") -> "下视传感器异常，请停下检查"
            riskType.contains("obstacle") -> nearestHardwareObstaclePrompt(state)
            else -> state.voicePrompt.takeIf { it.isNotBlank() }
        }
    }

    private fun hardwareRiskSpeechKey(riskType: String): String = when {
        riskType == "fall_detected" -> "fall"
        riskType.contains("front") -> "front"
        riskType.contains("left") -> "left"
        riskType.contains("right") -> "right"
        riskType.contains("ground") || riskType.contains("down") || riskType.contains("drop") -> "down"
        else -> riskType
    }

    private fun nearestHardwareObstaclePrompt(state: DeviceStateDto): String? {
        val candidates = listOfNotNull(
            state.frontCm?.let { "前方" to it },
            state.leftCm?.let { "左侧" to it },
            state.rightCm?.let { "右侧" to it },
            state.downCm?.let { "下方" to it }
        )
        val nearest = candidates.minByOrNull { it.second } ?: return null
        return "${nearest.first}${nearest.second}厘米有障碍"
    }

    private fun startNearbyRiskPolling() {
        if (nearbyRiskPollingJob?.isActive == true) return
        nearbyRiskPollingJob = scope.launch {
            while (true) {
                maybeAnnounceNearbyRisk()
                delay(4_000L)
            }
        }
    }

    private fun stopNearbyRiskPolling() {
        nearbyRiskPollingJob?.cancel()
        nearbyRiskPollingJob = null
        locationUpdatesActive = false
        runCatching {
            (appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.removeUpdates(phoneLocationListener ?: return@runCatching)
        }
    }

    private suspend fun maybeAnnounceNearbyRisk() {
        if (_uiState.value.currentMode != AppMode.Blind) return
        if (_uiState.value.voiceState != VoiceState.Idle) return

        ensureLocationUpdates()
        val location = currentPhoneLocation() ?: return
        when (val result = SmartCaneApiClient.getMapRiskPoints(location.latitude, location.longitude, 5)) {
            is ApiResult.Success -> {
                val point = result.data
                    .filterNot { announcedNearbyRiskIds.contains(it.id) }
                    .filterNot { isFromCurrentCane(it.deviceId) }
                    .filter { riskLevelRank(it.riskLevel) >= riskLevelRank("medium") }
                    .filter { isWithinFiveMeters(location, it.latitude, it.longitude, it.distanceMeters) }
                    .maxWithOrNull(compareBy({ riskLevelRank(it.riskLevel) }, { it.id }))
                    ?: return
                val text = "接近道路风险点，请减速确认"
                val now = System.currentTimeMillis()
                if (text == lastNearbyRiskText && now - lastNearbyRiskTextSpokenAt < 600_000L) return
                announcedNearbyRiskIds += point.id
                lastNearbyRiskText = text
                lastNearbyRiskTextSpokenAt = now
                speakText(text)
            }
            is ApiResult.Failure -> Unit
        }
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

    private fun isWithinFiveMeters(location: Location, latitude: Double?, longitude: Double?, backendDistanceMeters: Double?): Boolean {
        backendDistanceMeters?.let { return it <= 5.0 }
        if (latitude == null || longitude == null) return true
        val distance = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, latitude, longitude, distance)
        return distance.first() <= 5f
    }

    private fun riskLevelRank(level: String): Int = when (level.lowercase(Locale.US)) {
        "high" -> 3
        "medium" -> 2
        else -> 1
    }

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

    fun cancelPendingFall() {
        if (!_uiState.value.fallPending) return
        val deviceId = currentCaneDeviceId()
        if (deviceId.isBlank()) {
            _uiState.update { it.copy(message = "未绑定真实盲杖，无法发送取消指令") }
            return
        }
        scope.launch {
            when (val result = SmartCaneApiClient.cancelPendingFall(deviceId)) {
                is ApiResult.Success -> if (result.data) {
                    _uiState.update { it.copy(fallPending = false, fallStage = "slow_fall_cancel_requested", message = "已发送跌倒取消请求") }
                    speakText("已取消。", priority = TtsPriority.EMERGENCY)
                }
                is ApiResult.Failure -> _uiState.update { it.copy(message = "取消失败：${result.message}") }
            }
        }
    }

    private fun speakText(text: String, priority: TtsPriority) {
        speakText(text, listenAfter = false, priority = priority)
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
        fromQueue: Boolean = false
    ) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return
        val now = System.currentTimeMillis()
        val key = speechKey(cleanText)
        if (!fromQueue && now - (speechCooldowns[key] ?: 0L) < 12_000L) return
        if (!fromQueue && pendingSpeech.any { speechKey(it.text) == key }) return
        val currentPriority = activeTtsPriority
        if (activeTtsUtteranceId != null && currentPriority != null) {
            if (priority.rank <= currentPriority.rank) {
                pendingSpeech += QueuedSpeech(cleanText, listenAfter, priority)
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
                val next = pendingSpeech.removeFirstOrNull()
                if (next != null) {
                    speakText(next.text, next.listenAfter, next.priority, fromQueue = true)
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
                    speakText(result.data.voicePrompt.ifBlank { "已收到语音指令" })
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
                        result.data.sessionId?.let {
                            NavigationLocationService.latestRoute = result.data
                            NavigationLocationService.start(appContext, it, deviceId)
                            announcedNavigationSteps.clear()
                            _uiState.update { state ->
                                state.copy(
                                    navigationStatus = "active",
                                    activeNavigationRoute = result.data.bestRoute,
                                    alternativeNavigationRoutes = result.data.routes,
                                    selectedRouteIndex = result.data.selectedRouteIndex
                                )
                            }
                        }
                        speakText(result.data.voicePrompt.ifBlank { "\u5df2\u6536\u5230\u8def\u7ebf\u8bf7\u6c42" })
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
                    speakText(result.data.reply.ifBlank { "\u5df2\u6536\u5230\u8bed\u97f3\u6307\u4ee4" })
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

    private fun startLocalSosAlarm(durationMs: Long = 30_000L) {
        sosAlarmJob?.cancel()
        sosAlarmJob = scope.launch {
            val alarmText = "\u5df2\u7ecf\u53d1\u51fa\u7d27\u6025\u4fe1\u606f\uff0c\u8bf7\u505c\u7559\u5728\u5b89\u5168\u5730\u5e26\u3002"
            val endAt = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < endAt) {
                speakText(alarmText)
                delay(6_500L)
            }
        }
    }

    fun sendBlindSos() {
        if (_uiState.value.sosState == SosActionState.Sending) return
        scope.launch {
            _uiState.update { it.copy(sosState = SosActionState.Sending, message = "正在发送 SOS，并持续呼救 30 秒") }
            startLocalSosAlarm()
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
                }
                is ApiResult.Failure -> _uiState.update { it.copy(sosState = SosActionState.Error, message = "发送失败") }
            }
        }
    }

    fun stopNavigation() {
        NavigationLocationService.stop(appContext)
        announcedNavigationSteps.clear()
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
enum class TtsPriority(val rank: Int) {
    NORMAL(1), ROAD_RISK(2), NAVIGATION(3), OBSTACLE_STOP(4), STEP(5), EMERGENCY(6)
}
data class QueuedSpeech(val text: String, val listenAfter: Boolean, val priority: TtsPriority)

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
