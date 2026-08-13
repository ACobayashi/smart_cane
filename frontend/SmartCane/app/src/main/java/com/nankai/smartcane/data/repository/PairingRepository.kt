package com.nankai.smartcane.data.repository

import com.nankai.smartcane.data.local.DemoData
import com.nankai.smartcane.data.local.LocalAppPreferences
import com.nankai.smartcane.data.local.StoredAppState
import com.nankai.smartcane.data.model.CaneDevice
import com.nankai.smartcane.data.model.CareRelation
import com.nankai.smartcane.data.model.CareRequest
import com.nankai.smartcane.data.model.PairingCode
import com.nankai.smartcane.data.model.RelationStatus
import com.nankai.smartcane.data.model.UserProfile
import com.nankai.smartcane.data.model.UserRole
import com.nankai.smartcane.data.network.ApiResult
import com.nankai.smartcane.data.network.CareRelationDto
import com.nankai.smartcane.data.network.CareRequestDto
import com.nankai.smartcane.data.network.PairingCodeDto
import com.nankai.smartcane.data.network.PairingDeviceDto
import com.nankai.smartcane.data.network.PairingUserDto
import com.nankai.smartcane.data.network.SmartCaneApiClient
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.random.Random

interface PairingRepository {
    val storedState: StateFlow<StoredAppState>
    suspend fun generatePairingCode(blindUser: UserProfile): Result<PairingCode>
    suspend fun findPairingCode(code: String): Result<PairingCode>
    suspend fun sendRelationRequest(code: String, companionUser: UserProfile): Result<String>
    suspend fun getPendingRequests(blindUser: UserProfile): Result<List<CareRequest>>
    suspend fun getCompanionRequests(companionUser: UserProfile): Result<List<CareRequest>>
    suspend fun approveRequest(requestId: String): Result<CareRelation>
    suspend fun rejectRequest(requestId: String): Result<Unit>
    suspend fun getCurrentRelation(user: UserProfile, role: UserRole = user.role): Result<CareRelation?>
    suspend fun unlinkRelation(relationId: String?): Result<Unit>
    suspend fun clearPairingCode()
}

class RemotePairingRepository(
    private val preferences: LocalAppPreferences
) : PairingRepository {
    override val storedState: StateFlow<StoredAppState> = preferences.state

    override suspend fun generatePairingCode(blindUser: UserProfile): Result<PairingCode> {
        return when (val result = SmartCaneApiClient.createPairingCode(blindUser.account.ifBlank { blindUser.userId }, DemoData.defaultCane.deviceId)) {
            is ApiResult.Success -> {
                val pairing = result.data.toPairingCode()
                preferences.savePairingCode(pairing.code, pairing.createdAtMillis, pairing.expiresAtMillis)
                Result.success(pairing)
            }
            is ApiResult.Failure -> Result.failure(IllegalStateException(result.message))
        }
    }

    override suspend fun findPairingCode(code: String): Result<PairingCode> {
        if (!code.trim().matches(Regex("\\d{6}"))) return Result.failure(IllegalArgumentException("请输入六位数字配对码。"))
        return when (val result = SmartCaneApiClient.getPairingCode(code.trim())) {
            is ApiResult.Success -> Result.success(result.data.toPairingCode())
            is ApiResult.Failure -> Result.failure(IllegalStateException(result.message))
        }
    }

    override suspend fun sendRelationRequest(code: String, companionUser: UserProfile): Result<String> {
        return when (val result = SmartCaneApiClient.createRelationRequest(code.trim(), companionUser.account.ifBlank { companionUser.userId }, companionUser.displayName)) {
            is ApiResult.Success -> {
                val requestId = result.data.requestId
                if (!result.data.success || requestId.isNullOrBlank()) {
                    Result.failure(IllegalStateException(result.data.error ?: "发送申请失败。"))
                } else {
                    preferences.savePendingRequestId(requestId)
                    preferences.saveRelationIds(null, RelationStatus.Pending)
                    Result.success(requestId)
                }
            }
            is ApiResult.Failure -> Result.failure(IllegalStateException(result.message))
        }
    }

    override suspend fun getPendingRequests(blindUser: UserProfile): Result<List<CareRequest>> {
        return when (val result = SmartCaneApiClient.getRelationRequests(blindUser.account.ifBlank { blindUser.userId })) {
            is ApiResult.Success -> Result.success(result.data.requests.map { it.toCareRequest() })
            is ApiResult.Failure -> Result.failure(IllegalStateException(result.message))
        }
    }

    override suspend fun getCompanionRequests(companionUser: UserProfile): Result<List<CareRequest>> {
        return when (val result = SmartCaneApiClient.getRelationRequestsByCompanion(companionUser.account.ifBlank { companionUser.userId })) {
            is ApiResult.Success -> Result.success(result.data.requests.map { it.toCareRequest() })
            is ApiResult.Failure -> Result.failure(IllegalStateException(result.message))
        }
    }

    override suspend fun approveRequest(requestId: String): Result<CareRelation> {
        return when (val result = SmartCaneApiClient.approveRelationRequest(requestId)) {
            is ApiResult.Success -> {
                val relationDto = result.data.relation ?: return Result.failure(IllegalStateException(result.data.error ?: "同意失败。"))
                val relation = relationDto.toCareRelation()
                preferences.saveRelation(relation)
                preferences.savePairingCode(null, null, null)
                Result.success(relation)
            }
            is ApiResult.Failure -> Result.failure(IllegalStateException(result.message))
        }
    }

    override suspend fun rejectRequest(requestId: String): Result<Unit> {
        return when (val result = SmartCaneApiClient.rejectRelationRequest(requestId)) {
            is ApiResult.Success -> {
                preferences.savePendingRequestId(null)
                preferences.saveRelationIds(null, RelationStatus.Rejected)
                Result.success(Unit)
            }
            is ApiResult.Failure -> Result.failure(IllegalStateException(result.message))
        }
    }

    override suspend fun getCurrentRelation(user: UserProfile, role: UserRole): Result<CareRelation?> {
        return when (val result = SmartCaneApiClient.getRelations(user.account.ifBlank { user.userId }, role.apiValue)) {
            is ApiResult.Success -> {
                val relation = result.data.relation?.toCareRelation()
                if (relation != null) preferences.saveRelation(relation)
                Result.success(relation)
            }
            is ApiResult.Failure -> Result.failure(IllegalStateException(result.message))
        }
    }

    override suspend fun unlinkRelation(relationId: String?): Result<Unit> {
        if (relationId.isNullOrBlank()) {
            preferences.saveRelation(null)
            return Result.success(Unit)
        }
        return when (val result = SmartCaneApiClient.removeRelation(relationId)) {
            is ApiResult.Success -> {
                preferences.saveRelation(null)
                Result.success(Unit)
            }
            is ApiResult.Failure -> Result.failure(IllegalStateException(result.message))
        }
    }

    override suspend fun clearPairingCode() {
        preferences.savePairingCode(null, null, null)
    }
}

class DemoPairingRepository(
    private val preferences: LocalAppPreferences
) : PairingRepository {
    override val storedState: StateFlow<StoredAppState> = preferences.state

    override suspend fun generatePairingCode(blindUser: UserProfile): Result<PairingCode> {
        val now = System.currentTimeMillis()
        val code = Random.nextInt(100000, 999999).toString().ifBlank { DemoData.DEFAULT_PAIRING_CODE }
        val pairing = PairingCode(code, blindUser, DemoData.defaultCane, now, now + 10 * 60 * 1000L)
        preferences.savePairingCode(pairing.code, pairing.createdAtMillis, pairing.expiresAtMillis)
        return Result.success(pairing)
    }

    override suspend fun findPairingCode(code: String): Result<PairingCode> {
        val normalized = code.trim()
        val state = preferences.state.value
        val now = System.currentTimeMillis()
        val storedCode = state.pairingCode ?: DemoData.DEFAULT_PAIRING_CODE
        val createdAt = state.pairingCreatedAtMillis ?: now
        val expiresAt = state.pairingExpiresAtMillis ?: (now + 10 * 60 * 1000L)
        if (!normalized.matches(Regex("\\d{6}"))) return Result.failure(IllegalArgumentException("请输入六位数字配对码。"))
        if (normalized != storedCode && normalized != DemoData.DEFAULT_PAIRING_CODE) return Result.failure(IllegalArgumentException("未找到该配对码。"))
        val pairing = PairingCode(normalized, DemoData.blindUser, DemoData.defaultCane, createdAt, expiresAt)
        if (!pairing.isValid(now)) return Result.failure(IllegalStateException("配对码已过期。"))
        return Result.success(pairing)
    }

    override suspend fun sendRelationRequest(code: String, companionUser: UserProfile): Result<String> {
        val requestId = "request_demo_001"
        preferences.savePendingRequestId(requestId)
        preferences.saveRelationIds(null, RelationStatus.Pending, companionUser)
        return Result.success(requestId)
    }

    override suspend fun getPendingRequests(blindUser: UserProfile): Result<List<CareRequest>> {
        val state = preferences.state.value
        return if (state.pendingRequestId != null || state.relationStatus == RelationStatus.Pending) {
            Result.success(listOf(CareRequest(state.pendingRequestId ?: "request_demo_001", state.pairingCode ?: DemoData.DEFAULT_PAIRING_CODE, DemoData.blindUser, DemoData.companionUser, DemoData.defaultCane, RelationStatus.Pending, System.currentTimeMillis(), System.currentTimeMillis())))
        } else Result.success(emptyList())
    }

    override suspend fun getCompanionRequests(companionUser: UserProfile): Result<List<CareRequest>> = getPendingRequests(DemoData.blindUser)

    override suspend fun approveRequest(requestId: String): Result<CareRelation> {
        val now = System.currentTimeMillis()
        val relation = CareRelation("relation_demo_001", DemoData.blindUser, DemoData.companionUser, DemoData.defaultCane, RelationStatus.Active, now, now)
        preferences.saveRelation(relation)
        preferences.savePairingCode(null, null, null)
        return Result.success(relation)
    }

    override suspend fun rejectRequest(requestId: String): Result<Unit> {
        preferences.savePendingRequestId(null)
        preferences.saveRelationIds(null, RelationStatus.Rejected)
        return Result.success(Unit)
    }

    override suspend fun getCurrentRelation(user: UserProfile, role: UserRole): Result<CareRelation?> {
        val state = preferences.state.value
        return if (state.relationStatus == RelationStatus.Active) Result.success(CareRelation(state.relationId ?: "relation_demo_001", DemoData.blindUser, state.companionUser ?: DemoData.companionUser, DemoData.defaultCane, RelationStatus.Active, System.currentTimeMillis(), state.relationUpdatedAtMillis ?: System.currentTimeMillis())) else Result.success(null)
    }

    override suspend fun unlinkRelation(relationId: String?): Result<Unit> {
        preferences.saveRelation(null)
        return Result.success(Unit)
    }

    override suspend fun clearPairingCode() { preferences.savePairingCode(null, null, null) }
}

private fun PairingCodeDto.toPairingCode(): PairingCode = PairingCode(
    code = code,
    blindUser = blindUser.toUserProfile(UserRole.Blind),
    caneDevice = device.toCaneDevice(),
    createdAtMillis = System.currentTimeMillis(),
    expiresAtMillis = expiresAt.toEpochMillisOrNowPlusTenMinutes()
)

private fun CareRequestDto.toCareRequest(): CareRequest = CareRequest(
    requestId = requestId,
    code = code,
    blindUser = blindUser.toUserProfile(UserRole.Blind),
    companionUser = companionUser.toUserProfile(UserRole.Companion),
    caneDevice = device.toCaneDevice(),
    status = status.toRelationStatus(),
    createdAtMillis = createdAt.toEpochMillisOrNowPlusTenMinutes(),
    updatedAtMillis = updatedAt.toEpochMillisOrNowPlusTenMinutes()
)

private fun CareRelationDto.toCareRelation(): CareRelation = CareRelation(
    relationId = relationId,
    blindUser = blindUser.toUserProfile(UserRole.Blind),
    companionUser = companionUser.toUserProfile(UserRole.Companion),
    caneDevice = device.toCaneDevice(),
    status = status.toRelationStatus(),
    requestedAtMillis = createdAt.toEpochMillisOrNowPlusTenMinutes(),
    updatedAtMillis = updatedAt.toEpochMillisOrNowPlusTenMinutes()
)

private fun PairingUserDto.toUserProfile(role: UserRole): UserProfile = UserProfile(
    userId = userId,
    account = userId,
    displayName = displayName.ifBlank { userId },
    role = role,
    isDemo = true
)

private fun PairingDeviceDto.toCaneDevice(): CaneDevice = CaneDevice(
    deviceId = deviceId,
    name = name.ifBlank { deviceId },
    online = false,
    lastSeenText = "等待设备连接"
)

private fun String.toRelationStatus(): RelationStatus = when (lowercase()) {
    "pending" -> RelationStatus.Pending
    "approved", "active", "connected" -> RelationStatus.Active
    "rejected" -> RelationStatus.Rejected
    "expired" -> RelationStatus.Expired
    "removed" -> RelationStatus.Removed
    else -> RelationStatus.None
}

private fun String.toEpochMillisOrNowPlusTenMinutes(): Long = runCatching { Instant.parse(this).toEpochMilli() }
    .recoverCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }
    .getOrElse { System.currentTimeMillis() + 10 * 60 * 1000L }
