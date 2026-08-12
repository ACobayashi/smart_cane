package com.nankai.smartcane.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEpisodeTrackerTest {
    @Test
    fun nonNavigationRiskWarningUsesTenMeterRadius() {
        assertEquals(10, NON_NAVIGATION_RISK_WARNING_RADIUS_M)
    }

    @Test
    fun navigationStateDisablesNonNavigationRiskPolling() {
        assertTrue(isNavigationInProgress("active"))
        assertTrue(isNavigationInProgress("replanning"))
        assertTrue(isNavigationInProgress("off_route"))
        assertFalse(isNavigationInProgress("idle"))
        assertFalse(isNavigationInProgress("arrived"))
    }

    @Test
    fun realtimeRisksOutrankHistoricalRoadWarnings() {
        assertTrue(hardwareRiskTtsPriority("front_obstacle").rank > TtsPriority.ROAD_RISK.rank)
        assertTrue(hardwareRiskTtsPriority("ground_drop").rank > TtsPriority.ROAD_RISK.rank)
        assertEquals(TtsPriority.STEP, hardwareRiskTtsPriority("ground_step"))
    }

    @Test
    fun sustainedRiskOnlyEntersOnce() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("ground_step_down"))
        assertFalse(tracker.enter("ground_step_down"))
        assertFalse(tracker.enter("ground_step_down"))
    }

    @Test
    fun threeTrustedClearsAllowRiskToEnterAgain() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("ground_step_down"))
        assertFalse(tracker.observeTrustedClear())
        assertFalse(tracker.observeTrustedClear())
        assertTrue(tracker.observeTrustedClear())
        assertTrue(tracker.enter("ground_step_down"))
    }

    @Test
    fun oneOrTwoTrustedClearsDoNotEndEpisode() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("ground_step_down"))
        assertFalse(tracker.observeTrustedClear())
        assertFalse(tracker.enter("ground_step_down"))
        assertFalse(tracker.observeTrustedClear())
        assertFalse(tracker.observeTrustedClear())
        assertFalse(tracker.enter("ground_step_down"))
    }

    @Test
    fun unknownInterruptsConsecutiveTrustedClears() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("ground_step_down"))
        assertFalse(tracker.observeTrustedClear())
        assertFalse(tracker.observeTrustedClear())
        tracker.observeUnknown()
        assertFalse(tracker.observeTrustedClear())
        assertFalse(tracker.enter("ground_step_down"))
    }

    @Test
    fun activeRiskInterruptsConsecutiveTrustedClearsEvenBeforeSpeechDecision() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("ground_step_down"))
        assertFalse(tracker.observeTrustedClear())
        assertFalse(tracker.observeTrustedClear())
        tracker.observeActive()
        assertFalse(tracker.observeTrustedClear())
        assertFalse(tracker.enter("ground_step_down"))
    }

    @Test
    fun transientUnknownDoesNotEndEpisode() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("ground_step_down"))
        tracker.observeUnknown()
        assertFalse(tracker.enter("ground_step_down"))
    }

    @Test
    fun differentRiskTypeStartsNewEpisode() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("front_obstacle"))
        assertTrue(tracker.enter("ground_step_down"))
        assertFalse(tracker.enter("ground_step_down"))
        assertTrue(tracker.enter("front_obstacle"))
    }

    @Test
    fun fallNeedsThreeTrustedClearsBeforeNewEpisode() {
        val tracker = RiskEpisodeTracker()

        assertTrue(tracker.enter("fall_detected"))
        assertFalse(tracker.observeTrustedClear())
        assertFalse(tracker.enter("fall_detected"))
        assertFalse(tracker.observeTrustedClear())
        assertFalse(tracker.observeTrustedClear())
        assertTrue(tracker.observeTrustedClear())
        assertTrue(tracker.enter("fall_detected"))
    }
}
