package com.nankai.smartcane.data.model

enum class UserRole(val displayName: String, val apiValue: String) {
    Blind("用户", "blind"),
    Companion("陪护人", "companion")
}

enum class AppMode(val displayName: String) {
    Blind("用户"),
    Companion("陪护人")
}

enum class PairingFlowStatus(val displayName: String) {
    Idle("未开始"),
    Loading("加载中"),
    Waiting("等待确认"),
    PendingApproval("待确认"),
    Connected("已关联"),
    Rejected("已拒绝"),
    Expired("已过期"),
    Error("出错")
}

data class UserProfile(
    val userId: String,
    val account: String,
    val displayName: String,
    val role: UserRole,
    val isDemo: Boolean = true
)

data class CaneDevice(
    val deviceId: String,
    val name: String,
    val online: Boolean,
    val lastSeenText: String
)

enum class RelationStatus(val displayName: String) {
    None("未关联"),
    Pending("等待确认"),
    Requested("收到申请"),
    Active("已关联"),
    Rejected("已拒绝"),
    Expired("已过期"),
    Removed("已解除")
}

data class CareRelation(
    val relationId: String,
    val blindUser: UserProfile,
    val companionUser: UserProfile?,
    val caneDevice: CaneDevice,
    val status: RelationStatus,
    val requestedAtMillis: Long,
    val updatedAtMillis: Long
)

data class PairingCode(
    val code: String,
    val blindUser: UserProfile,
    val caneDevice: CaneDevice,
    val createdAtMillis: Long,
    val expiresAtMillis: Long
) {
    fun remainingSeconds(nowMillis: Long = System.currentTimeMillis()): Long =
        ((expiresAtMillis - nowMillis).coerceAtLeast(0L) / 1000L)

    fun isValid(nowMillis: Long = System.currentTimeMillis()): Boolean = remainingSeconds(nowMillis) > 0
}

data class CareRequest(
    val requestId: String,
    val code: String,
    val blindUser: UserProfile,
    val companionUser: UserProfile,
    val caneDevice: CaneDevice,
    val status: RelationStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class DemoNavigationScenario(
    val destination: String,
    val nextInstruction: String,
    val distanceToIntersectionMeters: Int,
    val recommendedDirection: String,
    val riskLevel: String,
    val riskReason: List<String>
)

data class DemoRiskAlert(
    val level: String,
    val message: String,
    val timeText: String
)

data class AuthResult(
    val success: Boolean,
    val user: UserProfile? = null,
    val message: String
)
