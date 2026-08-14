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

    @Test
    fun walkingBearingPrefersGpsCourseThenMovementThenPhoneHeading() {
        assertEquals(91f, navigationWalkingBearing(91f, 1.2f, 88f, 270f))
        assertEquals(88f, navigationWalkingBearing(null, null, 88f, 270f))
        assertEquals(270f, navigationWalkingBearing(null, null, null, 270f))
        assertEquals(359f, navigationWalkingBearing(-1f, null, null, null))
        assertNull(navigationWalkingBearing(null, null, null, null))
    }
}
