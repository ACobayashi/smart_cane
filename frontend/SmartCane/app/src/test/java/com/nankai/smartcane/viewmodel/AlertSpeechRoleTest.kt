package com.nankai.smartcane.viewmodel

import com.nankai.smartcane.data.network.LocalCueDto
import com.nankai.smartcane.data.network.LocalCueFallDto
import com.nankai.smartcane.data.network.LocalCueMetadataDto
import com.nankai.smartcane.data.network.LocalCueRiskDto
import com.nankai.smartcane.data.network.LocalCueSpeechDto
import com.nankai.smartcane.data.local.DemoData
import com.nankai.smartcane.data.model.CareRelation
import com.nankai.smartcane.data.model.RelationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSpeechRoleTest {

    @Test
    fun eventSpeechGateAllowsEachPositiveEventOnlyOnce() {
        val gate = EventSpeechGate()

        assertTrue(gate.tryAcquire(41))
        assertFalse(gate.tryAcquire(41))
        assertTrue(gate.tryAcquire(42))
        assertFalse(gate.tryAcquire(0))
    }

    @Test
    fun cueSpeechGateUsesTheFirmwareCueId() {
        val gate = CueIdSpeechGate()

        assertTrue(gate.tryAcquire("cue-001"))
        assertFalse(gate.tryAcquire("cue-001"))
        assertFalse(gate.tryAcquire(" "))
        assertTrue(gate.tryAcquire("cue-002"))
    }

    @Test
    fun localCueSpeechRequiresCurrentFreshNonRepeatCue() {
        val cue = localCueForTest()

        assertTrue(shouldSpeakLocalCue(cue, "cane_001"))
        assertFalse(shouldSpeakLocalCue(cue.copy(deviceId = "cane_002"), "cane_001"))
        assertFalse(shouldSpeakLocalCue(cue.copy(cue = cue.cue.copy(repeat = true)), "cane_001"))
        assertFalse(shouldSpeakLocalCue(cue.copy(timestamp = "2026-08-12T08:00:05Z"), "cane_001"))
    }

    @Test
    fun fallCueMustBeFormalAndMatchItsCueId() {
        val base = localCueForTest().copy(
            risk = localCueForTest().risk.copy(type = "fall_detected"),
            cue = localCueForTest().cue.copy(id = "fall-001", source = "formal_fall"),
            fall = LocalCueFallDto(detected = true, eventId = "fall-001"),
            speech = LocalCueSpeechDto(true, "检测到跌倒")
        )

        assertTrue(shouldSpeakLocalCue(base, "cane_001"))
        assertFalse(shouldSpeakLocalCue(base.copy(fall = null), "cane_001"))
        assertFalse(shouldSpeakLocalCue(base.copy(cue = base.cue.copy(source = "risk_feedback")), "cane_001"))
        assertFalse(shouldSpeakLocalCue(base.copy(fall = LocalCueFallDto(true, "fall-other")), "cane_001"))
    }

    @Test
    fun onlyFreshServerEventsCanBeSpoken() {
        assertTrue(isFreshDeviceEvent("2026-08-12T08:00:05Z", "2026-08-12T08:00:10Z"))
        assertFalse(isFreshDeviceEvent("2026-08-12T07:59:00Z", "2026-08-12T08:00:10Z"))
        assertFalse(isFreshDeviceEvent("not-a-time", "2026-08-12T08:00:10Z"))
    }

    @Test
    fun companionSosUsesCaregiverPrompt() {
        assertEquals(
            "用户发起紧急求助",
            alertSpeechForRole(
                role = "companion",
                riskType = "sos",
                voicePrompt = "用户端发起 SOS 紧急求助，请立即联系并查看地图位置",
                message = "用户端发起 SOS 紧急求助，请立即联系并查看地图位置",
                sosAlarmActive = false
            )
        )
    }

    @Test
    fun speechKeepsNavigationAndRiskPointContentAndCorrectsLegacyText() {
        assertEquals("用户发起紧急求助", compactSpeechText("收到 Android App 紧急求助，请尽快联系使用者。"))
        assertEquals("", compactSpeechText("检测到疑似跌倒，请恢复正常握杖姿态后继续使用。"))
        assertEquals(
            "沿卫津路向北步行三百米，经过两个风险点后右转。 注意施工区域。",
            compactSpeechText("沿卫津路向北步行三百米，经过两个风险点后右转。 注意施工区域。")
        )
    }

    @Test
    fun ordinaryAlertsCannotBypassTheLocalCueStream() {
        assertNull(alertSpeechForRole("blind", "prolonged_obstacle", "旧提示", "旧状态", false))
        assertNull(alertSpeechForRole("blind", "front_obstacle", "旧提示", "旧状态", false, 320))
        assertNull(alertSpeechForRole("blind", "fall_detected", "旧提示", "旧状态", false))
    }

    @Test
    fun sameRiskPointCanOnlySpeakOnceWithinFiveMinutes() {
        val cooldown = RiskPointSpeechCooldown()
        assertEquals(true, cooldown.tryAcquire(42, 1_000L))
        assertEquals(false, cooldown.tryAcquire(42, 300_999L))
        assertEquals(true, cooldown.tryAcquire(42, 301_000L))
        assertEquals(true, cooldown.tryAcquire(43, 301_001L))
    }

    @Test
    fun startupRiskPointBecomesSilentBaselineUntilUserLeavesAndReturns() {
        val gate = RiskPointApproachGate()

        assertFalse(gate.shouldSpeak(42))
        assertFalse(gate.shouldSpeak(42))
        assertFalse(gate.shouldSpeak(null))
        assertTrue(gate.shouldSpeak(42))
        assertTrue(gate.shouldSpeak(43))
    }

    @Test
    fun speechRequiresAnActiveOnlineBoundCane() {
        val active = CareRelation(
            relationId = "relation-001",
            blindUser = DemoData.blindUser,
            companionUser = DemoData.companionUser,
            caneDevice = DemoData.defaultCane,
            status = RelationStatus.Active,
            requestedAtMillis = 1L,
            updatedAtMillis = 1L
        )

        assertEquals(active.caneDevice.deviceId, speechCaneDeviceId(active))
        assertNull(speechCaneDeviceId(active.copy(caneDevice = active.caneDevice.copy(online = false))))
        assertNull(speechCaneDeviceId(active.copy(status = RelationStatus.Removed)))
        assertNull(speechCaneDeviceId(null))
    }

    @Test
    fun staleHeartbeatCannotEnableCaneSpeech() {
        val now = 1_786_521_610_000L

        assertTrue(isRecentDeviceHeartbeat("2026-08-12T08:00:05Z", nowMillis = now))
        assertFalse(isRecentDeviceHeartbeat("2026-08-12T07:59:00Z", nowMillis = now))
    }

    @Test
    fun navigationSpeechCannotBeInterruptedByRiskSpeech() {
        assertFalse(shouldInterruptCurrentSpeech(TtsPriority.NAVIGATION, TtsPriority.ROAD_RISK))
        assertFalse(shouldInterruptCurrentSpeech(TtsPriority.NAVIGATION, TtsPriority.OBSTACLE_STOP))
        assertFalse(shouldInterruptCurrentSpeech(TtsPriority.NAVIGATION, TtsPriority.STEP))
        assertFalse(shouldInterruptCurrentSpeech(TtsPriority.NAVIGATION, TtsPriority.EMERGENCY))
        assertTrue(shouldInterruptCurrentSpeech(TtsPriority.NORMAL, TtsPriority.STEP))
    }

    @Test
    fun blindSosNeverUsesCaregiverPrompt() {
        assertNull(
            alertSpeechForRole(
                role = "blind",
                riskType = "sos",
                voicePrompt = "用户端发起 SOS 紧急求助，请立即联系并查看地图位置",
                message = "用户端发起 SOS 紧急求助，请立即联系并查看地图位置",
                sosAlarmActive = false
            )
        )
    }

    @Test
    fun blindHardwareSosDoesNotUseCaregiverPrompt() {
        assertNull(
            alertSpeechForRole(
                role = "blind",
                riskType = "sos",
                voicePrompt = "SOS 已发送，已通知陪护端，请停在安全位置等待联系。",
                message = "盲杖 SOS 长按触发",
                sosAlarmActive = false
            )
        )
    }

    @Test
    fun fallDetectedIsOnlySpokenByTheLocalCueStream() {
        assertNull(
            alertSpeechForRole("blind", "fall_detected", "原始跌倒提示", "原始跌倒消息", false)
        )
        assertNull(
            alertSpeechForRole(
                role = "companion",
                riskType = "fall_detected",
                voicePrompt = "原始跌倒提示",
                message = "原始跌倒消息",
                sosAlarmActive = false
            )
        )
    }

    @Test
    fun activeBlindSosAlarmStillSuppressesOtherAlertSpeech() {
        assertNull(
            alertSpeechForRole(
                role = "blind",
                riskType = "fall_detected",
                voicePrompt = "原始跌倒提示",
                message = "原始跌倒消息",
                sosAlarmActive = true
            )
        )
    }


    private fun localCueForTest() = LocalCueDto(
        id = 301,
        deviceId = "cane_001",
        timestamp = "2026-08-12T08:00:08Z",
        serverTime = "2026-08-12T08:00:10Z",
        eventKind = "local_cue",
        risk = LocalCueRiskDto("ground_step", "medium", "down", "tof_down", 680),
        cue = LocalCueMetadataDto("cue-001", "risk_feedback", false, true, true),
        fall = null,
        speech = LocalCueSpeechDto(true, "前方下台阶，请减速")
    )
}
