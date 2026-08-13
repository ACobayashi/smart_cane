package com.nankai.smartcane.data.repository

import com.nankai.smartcane.data.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingRepositoryRoleTest {
    @Test
    fun companionModeFallsBackToTheAccountsBlindRelation() {
        assertEquals(
            listOf(UserRole.Companion, UserRole.Blind),
            relationRoleFallbackOrder(UserRole.Companion)
        )
    }

    @Test
    fun blindModeFallsBackToTheAccountsCompanionRelation() {
        assertEquals(
            listOf(UserRole.Blind, UserRole.Companion),
            relationRoleFallbackOrder(UserRole.Blind)
        )
    }
}
