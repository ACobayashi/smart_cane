package com.nankai.smartcane.data.network

import com.nankai.smartcane.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ApiConfig {
    /**
     * Set BACKEND_BASE_URL in local.properties for a physical phone, for example:
     * BACKEND_BASE_URL=http://192.168.1.23:8000
     */
    val BASE_URL: String = BuildConfig.BACKEND_BASE_URL.trimEnd('/')
}

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val message: String) : ApiResult<Nothing>
}

data class LatestRiskEventDto(
    val id: Int,
    val deviceId: String,
    val riskType: String,
    val riskLevel: String,
    val distance: Int?,
    val message: String,
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: String,
    val voicePrompt: String = "",
    val riskScore: Double? = null,
    val distanceMeters: Double? = null
)

data class EmergencyAlertDto(
    val id: Int,
    val deviceId: String,
    val riskType: String,
    val riskLevel: String,
    val priority: String,
    val title: String,
    val message: String,
    val voicePrompt: String,
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: String
)

data class ServerStatusDto(val online: Boolean, val message: String, val deviceCount: Int)

data class DeviceDto(
    val deviceId: String,
    val name: String,
    val online: Boolean,
    val lastSeen: String,
    val battery: Int? = null
)

data class SosRequestDto(val deviceId: String, val latitude: Double?, val longitude: Double?, val message: String)

data class SosRecordDto(
    val id: Int,
    val deviceId: String,
    val latitude: Double?,
    val longitude: Double?,
    val message: String,
    val receivedAt: String
)

data class SosResponseDto(val success: Boolean, val message: String, val sos: SosRecordDto?)

data class PairingUserDto(val userId: String, val displayName: String)
data class PairingDeviceDto(val deviceId: String, val name: String)

data class PairingCodeDto(
    val success: Boolean,
    val code: String,
    val expiresAt: String,
    val blindUser: PairingUserDto,
    val device: PairingDeviceDto,
    val error: String? = null
)

data class CreateRelationRequestResponseDto(
    val success: Boolean,
    val requestId: String?,
    val status: String,
    val error: String? = null
)

data class CareRequestDto(
    val requestId: String,
    val status: String,
    val code: String,
    val blindUser: PairingUserDto,
    val companionUser: PairingUserDto,
    val device: PairingDeviceDto,
    val createdAt: String,
    val updatedAt: String
)

data class CareRequestsResponseDto(val success: Boolean, val requests: List<CareRequestDto>, val error: String? = null)

data class CareRelationDto(
    val relationId: String,
    val status: String,
    val blindUser: PairingUserDto,
    val companionUser: PairingUserDto,
    val device: PairingDeviceDto,
    val createdAt: String,
    val updatedAt: String
)

data class CareRelationDecisionResponseDto(
    val success: Boolean,
    val requestId: String?,
    val status: String,
    val relation: CareRelationDto?,
    val error: String? = null
)

data class CareRelationsResponseDto(
    val success: Boolean,
    val relations: List<CareRelationDto>,
    val relation: CareRelationDto?,
    val error: String? = null
)

data class RemoveRelationResponseDto(val success: Boolean, val relationId: String?, val status: String, val error: String? = null)

data class MapStatusDto(val provider: String, val configured: Boolean)

data class SensorFrameRequestDto(
    val deviceId: String,
    val latitude: Double?,
    val longitude: Double?,
    val frontCm: Int?,
    val leftCm: Int?,
    val rightCm: Int?,
    val downCm: Int?,
    val battery: Int? = null,
    val accelTotalG: Double? = null,
    val fallDetected: Boolean? = null,
    val fallStage: String? = null,
    val fallConfidence: Double? = null,
    val alertType: String? = null
)

data class AiAdviceRequestDto(
    val deviceId: String,
    val latitude: Double?,
    val longitude: Double?,
    val riskType: String,
    val level: String,
    val frontMm: Int?,
    val leftMm: Int?,
    val rightMm: Int?,
    val downMm: Int?
)

data class AiAdviceDto(
    val provider: String,
    val model: String,
    val enabled: Boolean,
    val advice: String,
    val fallback: Boolean
)

data class SensorFrameResultDto(
    val riskType: String,
    val riskLevel: String,
    val riskScore: Double,
    val direction: String,
    val voicePrompt: String
)

data class RouteAdviceDto(
    val voicePrompt: String,
    val routeCount: Int,
    val distanceM: Int?,
    val durationS: Int?,
    val riskScore: Double?
)

object SmartCaneApiClient {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    suspend fun getLatestEvents(): ApiResult<List<LatestRiskEventDto>> = withContext(Dispatchers.IO) {
        try {
            val response = getJson("/events/latest")
            val events = response.optJSONArray("events") ?: JSONArray()
            ApiResult.Success(List(events.length()) { index -> events.getJSONObject(index).toLatestRiskEventDto() })
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun getLatestAlerts(
        role: String,
        userId: String?,
        deviceId: String?,
        sinceId: Int = 0
    ): ApiResult<List<EmergencyAlertDto>> = withContext(Dispatchers.IO) {
        try {
            val path = StringBuilder("/api/alerts/latest?role=${role.urlEncode()}&sinceId=$sinceId")
            if (!userId.isNullOrBlank()) path.append("&userId=${userId.urlEncode()}")
            if (!deviceId.isNullOrBlank()) path.append("&deviceId=${deviceId.urlEncode()}")
            val response = getJson(path.toString())
            val alerts = response.optJSONArray("alerts") ?: JSONArray()
            ApiResult.Success(List(alerts.length()) { index -> alerts.getJSONObject(index).toEmergencyAlertDto() })
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun getStatus(): ApiResult<ServerStatusDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(getJson("/status").toServerStatusDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun getDevices(): ApiResult<List<DeviceDto>> = withContext(Dispatchers.IO) {
        try {
            val response = getJson("/devices")
            val devices = response.optJSONArray("devices") ?: JSONArray()
            ApiResult.Success(List(devices.length()) { index -> devices.getJSONObject(index).toDeviceDto() })
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun postSos(request: SosRequestDto): ApiResult<SosResponseDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(postJson("/sos", request.toJson()).toSosResponseDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun createPairingCode(blindUserId: String, deviceId: String): ApiResult<PairingCodeDto> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().put("blindUserId", blindUserId).put("deviceId", deviceId)
            ApiResult.Success(postJson("/pairing-codes", payload).toPairingCodeDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun getPairingCode(code: String): ApiResult<PairingCodeDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(getJson("/pairing-codes/${code.urlEncode()}").toPairingCodeDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun createRelationRequest(
        code: String,
        companionUserId: String,
        companionName: String
    ): ApiResult<CreateRelationRequestResponseDto> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("code", code)
                .put("companionUserId", companionUserId)
                .put("companionName", companionName)
            ApiResult.Success(postJson("/care-relations/requests", payload).toCreateRelationRequestResponseDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun getRelationRequests(blindUserId: String): ApiResult<CareRequestsResponseDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(getJson("/care-relations/requests?blindUserId=${blindUserId.urlEncode()}").toCareRequestsResponseDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun getRelationRequestsByCompanion(companionUserId: String): ApiResult<CareRequestsResponseDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(getJson("/care-relations/requests?companionUserId=${companionUserId.urlEncode()}").toCareRequestsResponseDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun approveRelationRequest(requestId: String): ApiResult<CareRelationDecisionResponseDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(postJson("/care-relations/${requestId.urlEncode()}/approve", JSONObject()).toCareRelationDecisionResponseDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun rejectRelationRequest(requestId: String): ApiResult<CareRelationDecisionResponseDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(postJson("/care-relations/${requestId.urlEncode()}/reject", JSONObject()).toCareRelationDecisionResponseDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun getRelations(userId: String, role: String): ApiResult<CareRelationsResponseDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(getJson("/care-relations?userId=${userId.urlEncode()}&role=${role.urlEncode()}").toCareRelationsResponseDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun removeRelation(relationId: String): ApiResult<RemoveRelationResponseDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(deleteJson("/care-relations/${relationId.urlEncode()}").toRemoveRelationResponseDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun getAmapStatus(): ApiResult<MapStatusDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(getJson("/api/map/status").toMapStatusDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun getMapRiskPoints(
        latitude: Double? = null,
        longitude: Double? = null,
        radiusM: Int = 500
    ): ApiResult<List<LatestRiskEventDto>> = withContext(Dispatchers.IO) {
        try {
            val path = if (latitude != null && longitude != null) {
                "/api/map/risk-points?lat=$latitude&lng=$longitude&radius=$radiusM"
            } else {
                "/api/map/risk-points"
            }
            val response = getJson(path)
            val points = response.optJSONArray("points") ?: JSONArray()
            ApiResult.Success(List(points.length()) { index -> points.getJSONObject(index).toLatestRiskEventDto() })
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun postSensorFrame(request: SensorFrameRequestDto): ApiResult<SensorFrameResultDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(postJson("/api/sensor-frames", request.toJson()).toSensorFrameResultDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun postAiAdvice(request: AiAdviceRequestDto): ApiResult<AiAdviceDto> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(postJson("/api/ai-advice", request.toJson()).toAiAdviceDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun postVoiceRoute(
        text: String,
        currentLatitude: Double?,
        currentLongitude: Double?,
        city: String? = null
    ): ApiResult<RouteAdviceDto> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("device_id", "cane_001")
                put("text", text)
                put("current_lat", currentLatitude ?: JSONObject.NULL)
                put("current_lng", currentLongitude ?: JSONObject.NULL)
                put("city", city ?: JSONObject.NULL)
                put("coordsys", "gps")
            }
            ApiResult.Success(postJson("/api/navigation/voice-route", payload).toRouteAdviceDto())
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    suspend fun getReverseGeocode(latitude: Double, longitude: Double): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(getJson("/api/map/regeo?lat=$latitude&lng=$longitude&coordsys=gps").optString("formatted_address"))
        } catch (exception: Exception) {
            ApiResult.Failure(exception.toUserMessage())
        }
    }

    private fun getJson(path: String): JSONObject {
        val connection = (URL(ApiConfig.BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        return connection.useJsonConnection { code, body ->
            if (code in 200..299) JSONObject(body.ifBlank { "{}" }) else throw IllegalStateException(extractError(body, code))
        }
    }

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(ApiConfig.BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(body.size)
        }
        connection.outputStream.use { it.write(body) }
        return connection.useJsonConnection { code, responseBody ->
            if (code in 200..299) JSONObject(responseBody.ifBlank { "{}" }) else throw IllegalStateException(extractError(responseBody, code))
        }
    }

    private fun deleteJson(path: String): JSONObject {
        val connection = (URL(ApiConfig.BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        return connection.useJsonConnection { code, body ->
            if (code in 200..299) JSONObject(body.ifBlank { "{}" }) else throw IllegalStateException(extractError(body, code))
        }
    }

    private fun HttpURLConnection.useJsonConnection(block: (Int, String) -> JSONObject): JSONObject {
        return try {
            val code = responseCode
            val stream = if (code in 200..299) inputStream else errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            block(code, body)
        } finally {
            disconnect()
        }
    }

    private fun SosRequestDto.toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("latitude", latitude ?: JSONObject.NULL)
        put("longitude", longitude ?: JSONObject.NULL)
        put("message", message)
    }

    private fun SensorFrameRequestDto.toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("lat", latitude ?: JSONObject.NULL)
        put("lng", longitude ?: JSONObject.NULL)
        put("front_cm", frontCm ?: JSONObject.NULL)
        put("left_cm", leftCm ?: JSONObject.NULL)
        put("right_cm", rightCm ?: JSONObject.NULL)
        put("down_cm", downCm ?: JSONObject.NULL)
        put("battery", battery ?: JSONObject.NULL)
        put("accel_total_g", accelTotalG ?: JSONObject.NULL)
        put("fall_detected", fallDetected ?: JSONObject.NULL)
        put("fall_stage", fallStage ?: JSONObject.NULL)
        put("fall_confidence", fallConfidence ?: JSONObject.NULL)
        put("alert_type", alertType ?: JSONObject.NULL)
        put("source", "android_frontend_sim")
    }

    private fun AiAdviceRequestDto.toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("lat", latitude ?: JSONObject.NULL)
        put("lng", longitude ?: JSONObject.NULL)
        put("risk_type", riskType)
        put("level", level)
        put("front_mm", frontMm ?: JSONObject.NULL)
        put("left_mm", leftMm ?: JSONObject.NULL)
        put("right_mm", rightMm ?: JSONObject.NULL)
        put("down_mm", downMm ?: JSONObject.NULL)
        put("source", "android_frontend_advice")
    }

    private fun JSONObject.toLatestRiskEventDto(): LatestRiskEventDto {
        val riskType = optString("riskType", optString("risk_type", "none"))
        val riskLevel = optString("riskLevel", optString("risk_level", "low"))
        val messageValue = optString("message", optString("voice_prompt", "暂无风险说明"))
        return LatestRiskEventDto(
            id = optInt("id"),
            deviceId = optString("deviceId", optString("device_id", "")),
            riskType = riskType,
            riskLevel = riskLevel,
            distance = when {
                !isNull("distance") -> optInt("distance")
                !isNull("front_cm") -> optInt("front_cm") * 10
                !isNull("frontCm") -> optInt("frontCm") * 10
                else -> null
            },
            message = messageValue,
            latitude = nullableDouble("latitude") ?: nullableDouble("lat"),
            longitude = nullableDouble("longitude") ?: nullableDouble("lng"),
            timestamp = optString("timestamp"),
            voicePrompt = optString("voicePrompt", optString("voice_prompt", messageValue)),
            riskScore = nullableDouble("riskScore") ?: nullableDouble("risk_score"),
            distanceMeters = nullableDouble("distanceM") ?: nullableDouble("distance_m")
        )
    }

    private fun JSONObject.toEmergencyAlertDto(): EmergencyAlertDto = EmergencyAlertDto(
        id = optInt("id"),
        deviceId = optString("deviceId", optString("device_id", "")),
        riskType = optString("riskType", optString("risk_type", "none")),
        riskLevel = optString("riskLevel", optString("risk_level", "low")),
        priority = optString("priority", "medium"),
        title = optString("title", "风险告警"),
        message = optString("message"),
        voicePrompt = optString("voicePrompt", optString("voice_prompt", optString("message"))),
        latitude = nullableDouble("latitude") ?: nullableDouble("lat"),
        longitude = nullableDouble("longitude") ?: nullableDouble("lng"),
        timestamp = optString("timestamp")
    )

    private fun JSONObject.toServerStatusDto(): ServerStatusDto = ServerStatusDto(
        online = optBoolean("online"),
        message = optString("message"),
        deviceCount = optInt("deviceCount", optInt("device_count"))
    )

    private fun JSONObject.toDeviceDto(): DeviceDto = DeviceDto(
        deviceId = optString("deviceId", optString("device_id")),
        name = optString("name", optString("device_id")),
        online = optBoolean("online", true),
        lastSeen = optString("lastSeen", optString("last_seen", "")),
        battery = nullableInt("battery")
    )

    private fun JSONObject.toSosResponseDto(): SosResponseDto = SosResponseDto(
        success = optBoolean("success"),
        message = optString("message"),
        sos = optJSONObject("sos")?.toSosRecordDto()
    )

    private fun JSONObject.toSosRecordDto(): SosRecordDto = SosRecordDto(
        id = optInt("id"),
        deviceId = optString("deviceId", optString("device_id")),
        latitude = nullableDouble("latitude") ?: nullableDouble("lat"),
        longitude = nullableDouble("longitude") ?: nullableDouble("lng"),
        message = optString("message"),
        receivedAt = optString("receivedAt", optString("received_at", ""))
    )

    private fun JSONObject.toPairingUserDto(): PairingUserDto = PairingUserDto(optString("userId"), optString("displayName"))
    private fun JSONObject.toPairingDeviceDto(): PairingDeviceDto = PairingDeviceDto(optString("deviceId"), optString("name"))

    private fun JSONObject.toPairingCodeDto(): PairingCodeDto = PairingCodeDto(
        success = optBoolean("success"),
        code = optString("code"),
        expiresAt = optString("expiresAt"),
        blindUser = optJSONObject("blindUser")?.toPairingUserDto() ?: PairingUserDto("", ""),
        device = optJSONObject("device")?.toPairingDeviceDto() ?: PairingDeviceDto("", ""),
        error = optString("error").takeIf { it.isNotBlank() }
    )

    private fun JSONObject.toCreateRelationRequestResponseDto(): CreateRelationRequestResponseDto = CreateRelationRequestResponseDto(
        success = optBoolean("success"),
        requestId = optString("requestId").takeIf { it.isNotBlank() },
        status = optString("status"),
        error = optString("error").takeIf { it.isNotBlank() }
    )

    private fun JSONObject.toCareRequestDto(): CareRequestDto = CareRequestDto(
        requestId = optString("requestId"),
        status = optString("status"),
        code = optString("code"),
        blindUser = optJSONObject("blindUser")?.toPairingUserDto() ?: PairingUserDto("", ""),
        companionUser = optJSONObject("companionUser")?.toPairingUserDto() ?: PairingUserDto("", ""),
        device = optJSONObject("device")?.toPairingDeviceDto() ?: PairingDeviceDto("", ""),
        createdAt = optString("createdAt"),
        updatedAt = optString("updatedAt")
    )

    private fun JSONObject.toCareRequestsResponseDto(): CareRequestsResponseDto {
        val array = optJSONArray("requests") ?: JSONArray()
        return CareRequestsResponseDto(
            success = optBoolean("success"),
            requests = List(array.length()) { index -> array.getJSONObject(index).toCareRequestDto() },
            error = optString("error").takeIf { it.isNotBlank() }
        )
    }

    private fun JSONObject.toCareRelationDto(): CareRelationDto = CareRelationDto(
        relationId = optString("relationId"),
        status = optString("status"),
        blindUser = optJSONObject("blindUser")?.toPairingUserDto() ?: PairingUserDto("", ""),
        companionUser = optJSONObject("companionUser")?.toPairingUserDto() ?: PairingUserDto("", ""),
        device = optJSONObject("device")?.toPairingDeviceDto() ?: PairingDeviceDto("", ""),
        createdAt = optString("createdAt"),
        updatedAt = optString("updatedAt")
    )

    private fun JSONObject.toCareRelationDecisionResponseDto(): CareRelationDecisionResponseDto = CareRelationDecisionResponseDto(
        success = optBoolean("success"),
        requestId = optString("requestId").takeIf { it.isNotBlank() },
        status = optString("status"),
        relation = optJSONObject("relation")?.toCareRelationDto(),
        error = optString("error").takeIf { it.isNotBlank() }
    )

    private fun JSONObject.toCareRelationsResponseDto(): CareRelationsResponseDto {
        val array = optJSONArray("relations") ?: JSONArray()
        val relations = List(array.length()) { index -> array.getJSONObject(index).toCareRelationDto() }
        return CareRelationsResponseDto(
            success = optBoolean("success"),
            relations = relations,
            relation = optJSONObject("relation")?.toCareRelationDto() ?: relations.firstOrNull(),
            error = optString("error").takeIf { it.isNotBlank() }
        )
    }

    private fun JSONObject.toRemoveRelationResponseDto(): RemoveRelationResponseDto = RemoveRelationResponseDto(
        success = optBoolean("success"),
        relationId = optString("relationId").takeIf { it.isNotBlank() },
        status = optString("status"),
        error = optString("error").takeIf { it.isNotBlank() }
    )

    private fun JSONObject.toMapStatusDto(): MapStatusDto = MapStatusDto(
        provider = optString("provider", "amap"),
        configured = optBoolean("configured")
    )

    private fun JSONObject.toSensorFrameResultDto(): SensorFrameResultDto {
        val risk = optJSONObject("risk") ?: this
        return SensorFrameResultDto(
            riskType = risk.optString("risk_type", risk.optString("riskType", "none")),
            riskLevel = risk.optString("risk_level", risk.optString("riskLevel", "low")),
            riskScore = risk.optDouble("risk_score", risk.optDouble("riskScore", 0.0)),
            direction = risk.optString("direction", "none"),
            voicePrompt = risk.optString("voice_prompt", risk.optString("voicePrompt", ""))
        )
    }

    private fun JSONObject.toAiAdviceDto(): AiAdviceDto {
        val adviceText = cleanText(optString("ai_message"))
            ?: cleanText(optString("advice"))
            ?: "请慢行，注意附近风险。"
        return AiAdviceDto(
            provider = optString("provider", "unknown"),
            model = optString("model", ""),
            enabled = optBoolean("enabled", false),
            advice = adviceText,
            fallback = optBoolean("fallback", true)
        )
    }

    private fun JSONObject.toRouteAdviceDto(): RouteAdviceDto {
        val bestRoute = optJSONObject("best_route") ?: optJSONObject("bestRoute")
        return RouteAdviceDto(
            voicePrompt = optString("voice_prompt", optString("voicePrompt", "暂无路线建议")),
            routeCount = optInt("route_count", optInt("routeCount")),
            distanceM = bestRoute?.nullableInt("distance_m") ?: bestRoute?.nullableInt("distanceM"),
            durationS = bestRoute?.nullableInt("duration_s") ?: bestRoute?.nullableInt("durationS"),
            riskScore = bestRoute?.nullableDouble("risk_score") ?: bestRoute?.nullableDouble("riskScore")
        )
    }

    private fun cleanText(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun JSONObject.nullableDouble(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name) else null
    private fun JSONObject.nullableInt(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null

    private fun extractError(body: String, code: Int): String {
        return runCatching {
            val json = JSONObject(body.ifBlank { "{}" })
            json.optString("error")
                .ifBlank { json.optString("detail") }
                .ifBlank { json.optString("message") }
                .ifBlank { "HTTP $code" }
        }.getOrDefault("HTTP $code")
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun Exception.toUserMessage(): String = when (this) {
        is java.net.ConnectException -> "无法连接后端，请确认 FastAPI 已启动，并检查手机和电脑是否在同一网络。"
        is java.net.SocketTimeoutException -> "连接后端超时，请检查网络或后端地址。"
        is java.net.UnknownHostException -> "找不到后端地址，请在 local.properties 检查 BACKEND_BASE_URL。"
        else -> message?.takeIf { it.isNotBlank() } ?: "网络请求失败，请稍后重试。"
    }
}
