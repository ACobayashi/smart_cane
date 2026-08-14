package com.nankai.smartcane.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLocationPolicyTest {
    @Test
    fun navigationStopsAfterOneMinuteWithoutAUsableLocation() {
        assertFalse(navigationLocationTimedOut(59_999L, 1L, 60_000L))
        assertTrue(navigationLocationTimedOut(60_001L, 1L, 60_000L))
    }

    @Test
    fun timeoutDoesNotRunBeforeLocationMonitoringStarts() {
        assertFalse(navigationLocationTimedOut(120_000L, 0L, 60_000L))
    }

    @Test
    fun companionCanStopTheExplicitRemoteNavigationSession() {
        assertEquals("remote-session", navigationSessionToStop(" remote-session ", "local-session"))
        assertEquals("local-session", navigationSessionToStop(null, " local-session "))
        assertNull(navigationSessionToStop(" ", null))
    }
}
