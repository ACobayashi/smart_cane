package com.nankai.smartcane.data.repository

import com.nankai.smartcane.data.local.DemoData
import com.nankai.smartcane.data.local.LocalAppPreferences
import com.nankai.smartcane.data.model.AuthResult
import com.nankai.smartcane.data.model.UserProfile
import com.nankai.smartcane.data.model.UserRole
import com.nankai.smartcane.data.network.ApiResult
import com.nankai.smartcane.data.network.AuthUserDto
import com.nankai.smartcane.data.network.SmartCaneApiClient

interface AuthRepository {
    suspend fun login(account: String, password: String, rememberLogin: Boolean): AuthResult
    suspend fun register(account: String, password: String, displayName: String, role: UserRole, rememberLogin: Boolean): AuthResult
    suspend fun logout()
    suspend fun clearDemoData()
}

class DemoAuthRepository(
    private val preferences: LocalAppPreferences
) : AuthRepository {
    override suspend fun login(account: String, password: String, rememberLogin: Boolean): AuthResult {
        val normalized = account.trim()
        val user: UserProfile? = when (normalized) {
            DemoData.TEST_ACCOUNT -> DemoData.blindUser
            DemoData.BLIND_ACCOUNT -> DemoData.blindUser
            DemoData.COMPANION_ACCOUNT -> DemoData.companionUser
            else -> null
        }

        if (user == null || password != DemoData.DEMO_PASSWORD) {
            return AuthResult(
                success = false,
                message = "账号或密码错误。可用账号 demo，密码 123456。"
            )
        }

        preferences.saveLogin(user, rememberLogin = rememberLogin)
        return AuthResult(success = true, user = user, message = "\u767b\u5f55\u6210\u529f")
    }

    override suspend fun register(account: String, password: String, displayName: String, role: UserRole, rememberLogin: Boolean): AuthResult {
        return AuthResult(success = false, message = "请连接服务后注册新账号")
    }

    override suspend fun logout() {
        preferences.logout()
    }

    override suspend fun clearDemoData() {
        preferences.clearDemoData()
    }
}


class RemoteAuthRepository(
    private val preferences: LocalAppPreferences,
    private val fallback: AuthRepository = DemoAuthRepository(preferences)
) : AuthRepository {
    override suspend fun login(account: String, password: String, rememberLogin: Boolean): AuthResult {
        return when (val result = SmartCaneApiClient.login(account, password)) {
            is ApiResult.Success -> {
                val user = result.data.user?.toUserProfile(isDemo = false)
                if (result.data.success && user != null) {
                    preferences.saveLogin(user, rememberLogin)
                    AuthResult(success = true, user = user, message = result.data.message.ifBlank { "\u767b\u5f55\u6210\u529f" })
                } else {
                    AuthResult(success = false, message = result.data.message.ifBlank { "账号或密码错误" })
                }
            }
            is ApiResult.Failure -> {
                // Keep old demo accounts usable when backend is temporarily unavailable.
                fallback.login(account, password, rememberLogin).takeIf { it.success }
                    ?: AuthResult(success = false, message = result.message)
            }
        }
    }

    override suspend fun register(account: String, password: String, displayName: String, role: UserRole, rememberLogin: Boolean): AuthResult {
        return when (val result = SmartCaneApiClient.register(account, password, displayName, role.apiValue)) {
            is ApiResult.Success -> {
                val user = result.data.user?.toUserProfile(isDemo = false)
                if (result.data.success && user != null) {
                    preferences.saveLogin(user, rememberLogin)
                    AuthResult(success = true, user = user, message = result.data.message.ifBlank { "\u6ce8\u518c\u6210\u529f" })
                } else {
                    AuthResult(success = false, message = result.data.message.ifBlank { "注册失败" })
                }
            }
            is ApiResult.Failure -> AuthResult(success = false, message = result.message)
        }
    }

    override suspend fun logout() = preferences.logout()

    override suspend fun clearDemoData() = preferences.clearDemoData()
}

private fun AuthUserDto.toUserProfile(isDemo: Boolean): UserProfile = UserProfile(
    userId = userId,
    account = account,
    displayName = displayName,
    role = if (role == UserRole.Companion.apiValue) UserRole.Companion else UserRole.Blind,
    isDemo = isDemo
)
