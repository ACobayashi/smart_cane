package com.nankai.smartcane.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEpisodeTrackerTest {
    @Test
    fun sustainedRiskOnlyEntersOnce() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("ground_step_down"))
        assertFalse(tracker.enter("ground_step_down"))
        assertFalse(tracker.enter("ground_step_down"))
    }

    @Test
    fun clearedRiskCanEnterAgain() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("ground_step_down"))
        tracker.clear()
        assertTrue(tracker.enter("ground_step_down"))
    }

    @Test
    fun differentRiskTypeStartsNewEpisode() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("front_obstacle"))
        assertTrue(tracker.enter("ground_step_down"))
        assertFalse(tracker.enter("ground_step_down"))
        assertTrue(tracker.enter("front_obstacle"))
    }
}
