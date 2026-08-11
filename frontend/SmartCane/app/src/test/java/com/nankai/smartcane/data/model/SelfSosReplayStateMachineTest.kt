package com.nankai.smartcane.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SelfSosReplayStateMachineTest {
    private val first = SelfSosGeneration(7, "2026-08-11T12:00:00+00:00", 1)

    @Test
    fun waitsUntilLeavingThenPlaysOnlyOnceAfterReentry() {
        val machine = SelfSosReplayStateMachine()

        val initial = machine.update(first, 5.0)
        assertEquals(SelfSosReplayState.WAITING_TO_LEAVE, initial.state)
        assertTrue(initial.isNewGeneration)
        assertFalse(initial.shouldPlay)

        repeat(3) {
            val waiting = machine.update(first, 4.0)
            assertEquals(SelfSosReplayState.WAITING_TO_LEAVE, waiting.state)
            assertFalse(waiting.shouldPlay)
        }

        val armed = machine.update(first, 10.0)
        assertEquals(SelfSosReplayState.ARMED, armed.state)
        assertFalse(armed.shouldPlay)

        val stillArmed = machine.update(first, 5.1)
        assertEquals(SelfSosReplayState.ARMED, stillArmed.state)
        assertFalse(stillArmed.shouldPlay)

        val played = machine.update(first, 5.0)
        assertEquals(SelfSosReplayState.PLAYED, played.state)
        assertTrue(played.shouldPlay)

        repeat(3) {
            val repeated = machine.update(first, 3.0)
            assertEquals(SelfSosReplayState.PLAYED, repeated.state)
            assertFalse(repeated.shouldPlay)
        }
    }

    @Test
    fun firstObservationAtExitThresholdStartsArmedWithoutPlaying() {
        val result = SelfSosReplayStateMachine().update(first, 10.0)

        assertEquals(SelfSosReplayState.ARMED, result.state)
        assertTrue(result.isNewGeneration)
        assertFalse(result.shouldPlay)
    }

    @Test
    fun reportCountTimestampAndRiskPointIdEachCreateNewGeneration() {
        val machine = SelfSosReplayStateMachine()
        machine.update(first, 4.0)
        machine.update(first, 10.0)
        assertTrue(machine.update(first, 5.0).shouldPlay)

        val reportCountChanged = machine.update(first.copy(reportCount = 2), 4.0)
        assertTrue(reportCountChanged.isNewGeneration)
        assertEquals(SelfSosReplayState.WAITING_TO_LEAVE, reportCountChanged.state)
        assertFalse(reportCountChanged.shouldPlay)

        val timestampChanged = machine.update(
            first.copy(reportCount = 2, timestamp = "2026-08-11T12:05:00+00:00"),
            10.0
        )
        assertTrue(timestampChanged.isNewGeneration)
        assertEquals(SelfSosReplayState.ARMED, timestampChanged.state)
        assertFalse(timestampChanged.shouldPlay)

        val riskPointChanged = machine.update(
            first.copy(riskPointId = 8, reportCount = 2, timestamp = "2026-08-11T12:05:00+00:00"),
            4.0
        )
        assertTrue(riskPointChanged.isNewGeneration)
        assertEquals(SelfSosReplayState.WAITING_TO_LEAVE, riskPointChanged.state)
        assertFalse(riskPointChanged.shouldPlay)
    }
}
