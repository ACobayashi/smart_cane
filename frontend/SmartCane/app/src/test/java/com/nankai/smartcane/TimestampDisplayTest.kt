package com.nankai.smartcane

import org.junit.Assert.assertEquals
import org.junit.Test

class TimestampDisplayTest {
    @Test
    fun utcServerTimestampDisplaysInChinaTime() {
        assertEquals("18:22", displayTimestamp("2026-08-13T10:22:00+00:00"))
    }

    @Test
    fun alreadyOffsetTimestampKeepsTheSameInstant() {
        assertEquals("18:22", displayTimestamp("2026-08-13T18:22:00+08:00"))
    }
}
