package com.nankai.smartcane

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLocationPolicyTest {
    @Test
    fun mapCentersOnceOnTheFirstUsablePhoneLocation() {
        assertTrue(shouldAutoCenterMap(false, true, 39.0, 117.0, 20f))
        assertFalse(shouldAutoCenterMap(true, true, 39.0, 117.0, 20f))
    }

    @Test
    fun mapWaitsForPermissionAndAUsableLocation() {
        assertFalse(shouldAutoCenterMap(false, false, 39.0, 117.0, 20f))
        assertFalse(shouldAutoCenterMap(false, true, 0.0, 0.0, 20f))
        assertFalse(shouldAutoCenterMap(false, true, 39.0, 117.0, 101f))
        assertFalse(shouldAutoCenterMap(false, true, Double.NaN, 117.0, 20f))
    }
}
