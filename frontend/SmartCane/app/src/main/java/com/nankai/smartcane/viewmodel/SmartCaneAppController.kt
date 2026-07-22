package com.nankai.smartcane.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
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
import com.nankai.smartcane.data.network.EmergencyAlertDto
import com.nankai.smartcane.data.network.SmartCaneApiClient
import com.nankai.smartcane.data.network.SosRequestDto
import com.nankai.smartcane.data.repository.AuthRepository
import com.nankai.smartcane.data.repository.DemoAuthRepository
import com.nankai.smartcane.data.repository.PairingRepository
import com.nankai.smartcane.data.repository.RemotePairingRepository
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
    private var blindPollingJob: Job? = null
    private var companionPollingJob: Job? = null
    private var alertPollingJob: Job? = null
    private var nearbyRiskPollingJob: Job? = null
    private var locationUpdatesActive = false
    private var lastKnownPhoneLocation: Location? = null
    private var activeTtsUtteranceId: String? = null
    private var lastAlertId: Int = 0
    private val announcedNearbyRiskIds = mutableSetOf<Int>()
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastKnownPhoneLocation = location
        }
    }

    private val _uiState = MutableStateFlow(AppUiState(storedState = preferences.state.value))
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            preferences.state.collectLatest { stored ->
                _uiState.update { current -> current.copy(storedState = stored) }
            }
        }
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
            result.getOrNull()?.let { relation ->
                _uiState.update { it.copy(currentRelation = relation, pairingStatus = PairingFlowStatus.Connected) }
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

    fun startAlertPolling() {
        startNearbyRiskPolling()
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
                val deviceId = state.currentRelation?.caneDevice?.deviceId
                    ?: state.storedState.relationId?.let { DemoData.defaultCane.deviceId }
                    ?: DemoData.defaultCane.deviceId
                when (val result = SmartCaneApiClient.getLatestAlerts(role, userId, deviceId, lastAlertId)) {
                    is ApiResult.Success -> {
                        val newest = result.data.maxByOrNull { it.id }
                        if (newest != null && newest.id > lastAlertId) {
                            lastAlertId = newest.id
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
                                if (role == "blind") {
                                    speakText(newest.voicePrompt.ifBlank { newest.message })
                                }
                            }
                        }
                    }
                    is ApiResult.Failure -> Unit
                }
                delay(5_000L)
            }
        }
    }

    fun stopAlertPolling() {
        alertPollingJob?.cancel()
        alertPollingJob = null
        stopNearbyRiskPolling()
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
                ?.removeUpdates(locationListener)
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
                    .filter { isWithinFiveMeters(location, it.latitude, it.longitude, it.distanceMeters) }
                    .maxWithOrNull(compareBy({ riskLevelRank(it.riskLevel) }, { it.id }))
                    ?: return
                announcedNearbyRiskIds += point.id
                speakText("注意${riskLevelLabel(point.riskLevel)}风险区域")
            }
            is ApiResult.Failure -> Unit
        }
    }

    private fun ensureLocationUpdates() {
        if (locationUpdatesActive || !hasLocationPermission()) return
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        providers.forEach { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 3_000L, 1f, locationListener, appContext.mainLooper)
                }
            }
        }
        locationUpdatesActive = true
    }

    private fun currentPhoneLocation(): Location? {
        lastKnownPhoneLocation?.let { return it }
        if (!hasLocationPermission()) return null
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider ->
                runCatching {
                    if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
                }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?.also { lastKnownPhoneLocation = it }
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
        speakText(text, listenAfter = false)
    }

    private fun speakText(text: String, listenAfter: Boolean) {
        if (voiceRecognitionActive) {
            speechRecognizer?.cancel()
            voiceRecognitionActive = false
        }
        val utteranceId = "smartcane_${System.currentTimeMillis()}"
        activeTtsUtteranceId = utteranceId
        _uiState.update { it.copy(lastSpokenText = text, voiceState = VoiceState.Speaking, message = "正在播报") }

        fun finishSpeaking() {
            scope.launch {
                if (activeTtsUtteranceId != utteranceId) return@launch
                activeTtsUtteranceId = null
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
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
            when (val result = SmartCaneApiClient.postVoiceCommand(DemoData.defaultCane.deviceId, file, location?.latitude, location?.longitude)) {
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
            _uiState.update { it.copy(voiceState = VoiceState.Idle, message = "\u624b\u673a\u4e0d\u652f\u6301\u7cfb\u7edf\u8bed\u97f3\u8bc6\u522b", voiceTranscript = "\u624b\u673a\u4e0d\u652f\u6301\u7cfb\u7edf\u8bed\u97f3\u8bc6\u522b") }
            return
        }

        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            speechRecognizer = it
            it.setRecognitionListener(object : RecognitionListener {
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
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "\u8bed\u97f3\u7f51\u7edc\u4e0d\u53ef\u7528"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "\u8bf7\u7ed9 App \u5f00\u542f\u9ea6\u514b\u98ce\u6743\u9650"
                        else -> "\u8bed\u97f3\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u518d\u8bd5\u4e00\u6b21"
                    }
                    _uiState.update { state -> state.copy(voiceState = VoiceState.Idle, message = message, voiceTranscript = message) }
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
                        ensureLocationUpdates()
                        val location = currentPhoneLocation()
                        when (val result = SmartCaneApiClient.postVoiceRoute(text, location?.latitude, location?.longitude)) {
                            is ApiResult.Success -> speakText(result.data.voicePrompt.ifBlank { "\u5df2\u6536\u5230\u8def\u7ebf\u8bf7\u6c42" })
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
            })
        }

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
        }
    }

    private fun stopVoiceListening(message: String) {
        if (voiceRecognitionActive) {
            speechRecognizer?.stopListening()
        }
        voiceRecognitionActive = false
        _uiState.update { it.copy(voiceState = VoiceState.Idle, message = message, voiceTranscript = it.voiceTranscript ?: message) }
    }

    fun sendBlindSos() {
        if (_uiState.value.sosState == SosActionState.Sending) return
        scope.launch {
            _uiState.update { it.copy(sosState = SosActionState.Sending, message = null) }
            val result = SmartCaneApiClient.postSos(SosRequestDto(DemoData.defaultCane.deviceId, null, null, "用户端发起紧急求助"))
            when (result) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(sosState = SosActionState.Success, message = "SOS 已发送") }
                    speakText("紧急求助已发送")
                }
                is ApiResult.Failure -> _uiState.update { it.copy(sosState = SosActionState.Error, message = "发送失败") }
            }
        }
    }

    fun release() {
        stopPairingPolling()
        stopAlertPolling()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        activeTtsUtteranceId = null
        voiceRecognitionActive = false
        voiceRecordingJob?.cancel()
        voiceRecordingJob = null
        runCatching { voiceRecorder?.stop() }
        runCatching { voiceRecorder?.release() }
        voiceRecorder = null
        runCatching { voiceRecordingFile?.delete() }
        voiceRecordingFile = null
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun relation(): CareRelation? {
        _uiState.value.currentRelation?.let { return it }
        val state = _uiState.value.storedState
        if (state.relationStatus != RelationStatus.Active || state.relationId == null) return null
        val now = state.relationUpdatedAtMillis ?: System.currentTimeMillis()
        return CareRelation(state.relationId, DemoData.blindUser, state.companionUser ?: DemoData.companionUser, DemoData.defaultCane, RelationStatus.Active, now, now)
    }

    fun relationUpdateText(): String {
        val millis = _uiState.value.currentRelation?.updatedAtMillis ?: _uiState.value.storedState.relationUpdatedAtMillis ?: System.currentTimeMillis()
        return SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))
    }

    companion object {
        @Volatile private var INSTANCE: SmartCaneAppController? = null
        fun get(context: Context): SmartCaneAppController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val prefs = LocalAppPreferences(context.applicationContext)
                    val controller = SmartCaneAppController(
                        authRepository = DemoAuthRepository(prefs),
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
