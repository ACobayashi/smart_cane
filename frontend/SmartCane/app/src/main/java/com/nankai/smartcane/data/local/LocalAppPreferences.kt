package com.nankai.smartcane.data.local

import android.content.Context
import com.nankai.smartcane.data.model.AppMode
import com.nankai.smartcane.data.model.CareRelation
import com.nankai.smartcane.data.model.RelationStatus
import com.nankai.smartcane.data.model.UserProfile
import com.nankai.smartcane.data.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalAppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private var sessionLoginAllowed = false
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<StoredAppState> = _state.asStateFlow()

    fun saveLogin(user: UserProfile, rememberLogin: Boolean) {
        sessionLoginAllowed = true
        val previousAccount = prefs.getString(KEY_ACCOUNT, null)
        val previousUserId = prefs.getString(KEY_USER_ID, null)
        val switchingUser = previousAccount != null && (previousAccount != user.account || previousUserId != user.userId)
        prefs.edit().apply {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putBoolean(KEY_REMEMBER_LOGIN, rememberLogin)
            putString(KEY_USER_ID, user.userId)
            putString(KEY_ACCOUNT, user.account)
            putString(KEY_DISPLAY_NAME, user.displayName)
            putString(KEY_ROLE, user.role.name)
            putBoolean(KEY_IS_DEMO, user.isDemo)
            remove(KEY_LAST_MODE)
            remove(KEY_FIRST_GUIDE_COMPLETED)
            if (switchingUser) clearUserScopedState()
        }.apply()
        refresh()
    }

    private fun android.content.SharedPreferences.Editor.clearUserScopedState(): android.content.SharedPreferences.Editor {
        remove(KEY_CANE_BOUND)
        remove(KEY_RELATION_ID)
        remove(KEY_PENDING_REQUEST_ID)
        remove(KEY_RELATION_STATUS)
        remove(KEY_COMPANION_ID)
        remove(KEY_COMPANION_ACCOUNT)
        remove(KEY_COMPANION_NAME)
        remove(KEY_PAIRING_CODE)
        remove(KEY_PAIRING_CREATED_AT)
        remove(KEY_PAIRING_EXPIRES_AT)
        remove(KEY_RELATION_UPDATED_AT)
        remove(KEY_STATE_OWNER_ACCOUNT)
        return this
    }

    fun saveMode(mode: AppMode) {
        prefs.edit().putString(KEY_LAST_MODE, mode.name).apply()
        refresh()
    }

    fun saveFirstGuideCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_FIRST_GUIDE_COMPLETED, completed).apply()
        refresh()
    }

    fun savePairingCode(code: String?, createdAtMillis: Long?, expiresAtMillis: Long?) {
        prefs.edit().apply {
            putString(KEY_STATE_OWNER_ACCOUNT, prefs.getString(KEY_ACCOUNT, null))
            if (code == null || createdAtMillis == null || expiresAtMillis == null) {
                remove(KEY_PAIRING_CODE)
                remove(KEY_PAIRING_CREATED_AT)
                remove(KEY_PAIRING_EXPIRES_AT)
            } else {
                putString(KEY_PAIRING_CODE, code)
                putLong(KEY_PAIRING_CREATED_AT, createdAtMillis)
                putLong(KEY_PAIRING_EXPIRES_AT, expiresAtMillis)
            }
        }.apply()
        refresh()
    }

    fun savePendingRequestId(requestId: String?) {
        prefs.edit().apply {
            putString(KEY_STATE_OWNER_ACCOUNT, prefs.getString(KEY_ACCOUNT, null))
            if (requestId.isNullOrBlank()) remove(KEY_PENDING_REQUEST_ID) else putString(KEY_PENDING_REQUEST_ID, requestId)
        }.apply()
        refresh()
    }

    fun saveRelation(relation: CareRelation?) {
        prefs.edit().apply {
            putString(KEY_STATE_OWNER_ACCOUNT, prefs.getString(KEY_ACCOUNT, null))
            if (relation == null || relation.status == RelationStatus.None || relation.status == RelationStatus.Removed) {
                remove(KEY_RELATION_ID)
                remove(KEY_RELATION_STATUS)
                remove(KEY_COMPANION_ID)
                remove(KEY_COMPANION_ACCOUNT)
                remove(KEY_COMPANION_NAME)
                remove(KEY_RELATION_UPDATED_AT)
                remove(KEY_PENDING_REQUEST_ID)
            } else {
                putString(KEY_RELATION_ID, relation.relationId)
                putString(KEY_RELATION_STATUS, relation.status.name)
                relation.companionUser?.let {
                    putString(KEY_COMPANION_ID, it.userId)
                    putString(KEY_COMPANION_ACCOUNT, it.account)
                    putString(KEY_COMPANION_NAME, it.displayName)
                }
                putLong(KEY_RELATION_UPDATED_AT, relation.updatedAtMillis)
            }
        }.apply()
        refresh()
    }

    fun saveRelationIds(relationId: String?, status: RelationStatus, companionUser: UserProfile? = null) {
        prefs.edit().apply {
            putString(KEY_STATE_OWNER_ACCOUNT, prefs.getString(KEY_ACCOUNT, null))
            if (relationId.isNullOrBlank() || status == RelationStatus.None || status == RelationStatus.Removed) {
                remove(KEY_RELATION_ID)
                remove(KEY_RELATION_STATUS)
            } else {
                putString(KEY_RELATION_ID, relationId)
                putString(KEY_RELATION_STATUS, status.name)
                putLong(KEY_RELATION_UPDATED_AT, System.currentTimeMillis())
            }
            companionUser?.let {
                putString(KEY_COMPANION_ID, it.userId)
                putString(KEY_COMPANION_ACCOUNT, it.account)
                putString(KEY_COMPANION_NAME, it.displayName)
            }
        }.apply()
        refresh()
    }

    fun logout() {
        sessionLoginAllowed = false
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .remove(KEY_USER_ID)
            .remove(KEY_ACCOUNT)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_ROLE)
            .remove(KEY_IS_DEMO)
            .remove(KEY_LAST_MODE)
            .remove(KEY_FIRST_GUIDE_COMPLETED)
            .remove(KEY_CANE_BOUND)
            .remove(KEY_RELATION_ID)
            .remove(KEY_PENDING_REQUEST_ID)
            .remove(KEY_RELATION_STATUS)
            .remove(KEY_COMPANION_ID)
            .remove(KEY_COMPANION_ACCOUNT)
            .remove(KEY_COMPANION_NAME)
            .remove(KEY_PAIRING_CODE)
            .remove(KEY_PAIRING_CREATED_AT)
            .remove(KEY_PAIRING_EXPIRES_AT)
            .remove(KEY_RELATION_UPDATED_AT)
            .remove(KEY_STATE_OWNER_ACCOUNT)
            .apply()
        refresh()
    }

    fun clearDemoData() {
        val loggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        val rememberLogin = prefs.getBoolean(KEY_REMEMBER_LOGIN, true)
        val userId = prefs.getString(KEY_USER_ID, null)
        val account = prefs.getString(KEY_ACCOUNT, null)
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null)
        val role = prefs.getString(KEY_ROLE, null)
        val isDemo = prefs.getBoolean(KEY_IS_DEMO, true)
        prefs.edit().clear().apply()
        if (loggedIn && userId != null && account != null && displayName != null && role != null) {
            prefs.edit()
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .putBoolean(KEY_REMEMBER_LOGIN, rememberLogin)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_ACCOUNT, account)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putString(KEY_ROLE, role)
                .putBoolean(KEY_IS_DEMO, isDemo)
                .apply()
        }
        refresh()
    }

    private fun refresh() {
        _state.value = readState()
    }

    private fun readState(): StoredAppState {
        val role = prefs.getString(KEY_ROLE, null)?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
        val loginAvailable = prefs.getBoolean(KEY_IS_LOGGED_IN, false) && (prefs.getBoolean(KEY_REMEMBER_LOGIN, true) || sessionLoginAllowed)
        val user = if (loginAvailable && role != null) {
            UserProfile(
                userId = prefs.getString(KEY_USER_ID, "") ?: "",
                account = prefs.getString(KEY_ACCOUNT, "") ?: "",
                displayName = prefs.getString(KEY_DISPLAY_NAME, "") ?: "",
                role = role,
                isDemo = prefs.getBoolean(KEY_IS_DEMO, true)
            )
        } else null
        val ownerAccount = prefs.getString(KEY_STATE_OWNER_ACCOUNT, null)
        val userScopedStateValid = user != null && ownerAccount != null && ownerAccount == user.account
        return StoredAppState(
            isLoggedIn = user != null,
            currentUser = user,
            lastMode = prefs.getString(KEY_LAST_MODE, null)?.let { runCatching { AppMode.valueOf(it) }.getOrNull() },
            isCaneBound = userScopedStateValid && prefs.getBoolean(KEY_CANE_BOUND, false),
            relationId = if (userScopedStateValid) prefs.getString(KEY_RELATION_ID, null) else null,
            pendingRequestId = if (userScopedStateValid) prefs.getString(KEY_PENDING_REQUEST_ID, null) else null,
            relationStatus = if (userScopedStateValid) prefs.getString(KEY_RELATION_STATUS, null)?.let { runCatching { RelationStatus.valueOf(it) }.getOrNull() } ?: RelationStatus.None else RelationStatus.None,
            companionUser = if (userScopedStateValid) readCompanionUser() else null,
            pairingCode = if (userScopedStateValid) prefs.getString(KEY_PAIRING_CODE, null) else null,
            pairingCreatedAtMillis = if (userScopedStateValid) prefs.getLong(KEY_PAIRING_CREATED_AT, 0L).takeIf { it > 0L } else null,
            pairingExpiresAtMillis = if (userScopedStateValid) prefs.getLong(KEY_PAIRING_EXPIRES_AT, 0L).takeIf { it > 0L } else null,
            isFirstGuideCompleted = prefs.getBoolean(KEY_FIRST_GUIDE_COMPLETED, false),
            relationUpdatedAtMillis = if (userScopedStateValid) prefs.getLong(KEY_RELATION_UPDATED_AT, 0L).takeIf { it > 0L } else null
        )
    }

    private fun readCompanionUser(): UserProfile? {
        val id = prefs.getString(KEY_COMPANION_ID, null) ?: return null
        return UserProfile(
            userId = id,
            account = prefs.getString(KEY_COMPANION_ACCOUNT, DemoData.COMPANION_ACCOUNT) ?: DemoData.COMPANION_ACCOUNT,
            displayName = prefs.getString(KEY_COMPANION_NAME, DemoData.companionUser.displayName) ?: DemoData.companionUser.displayName,
            role = UserRole.Companion,
            isDemo = true
        )
    }

    companion object {
        private const val PREF_NAME = "smartcane_demo_state"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_REMEMBER_LOGIN = "remember_login"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ROLE = "role"
        private const val KEY_IS_DEMO = "is_demo"
        private const val KEY_LAST_MODE = "last_mode"
        private const val KEY_CANE_BOUND = "cane_bound"
        private const val KEY_RELATION_ID = "relation_id"
        private const val KEY_PENDING_REQUEST_ID = "pending_request_id"
        private const val KEY_RELATION_STATUS = "relation_status"
        private const val KEY_COMPANION_ID = "companion_id"
        private const val KEY_COMPANION_ACCOUNT = "companion_account"
        private const val KEY_COMPANION_NAME = "companion_name"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_PAIRING_CREATED_AT = "pairing_created_at"
        private const val KEY_PAIRING_EXPIRES_AT = "pairing_expires_at"
        private const val KEY_FIRST_GUIDE_COMPLETED = "first_guide_completed"
        private const val KEY_RELATION_UPDATED_AT = "relation_updated_at"
        private const val KEY_STATE_OWNER_ACCOUNT = "state_owner_account"
    }
}

data class StoredAppState(
    val isLoggedIn: Boolean = false,
    val currentUser: UserProfile? = null,
    val lastMode: AppMode? = null,
    val isCaneBound: Boolean = true,
    val relationId: String? = null,
    val pendingRequestId: String? = null,
    val relationStatus: RelationStatus = RelationStatus.None,
    val companionUser: UserProfile? = null,
    val pairingCode: String? = null,
    val pairingCreatedAtMillis: Long? = null,
    val pairingExpiresAtMillis: Long? = null,
    val isFirstGuideCompleted: Boolean = false,
    val relationUpdatedAtMillis: Long? = null
)
