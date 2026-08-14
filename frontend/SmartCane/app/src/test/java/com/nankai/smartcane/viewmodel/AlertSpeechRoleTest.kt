package com.nankai.smartcane.viewmodel

import com.nankai.smartcane.data.network.LocalCueDto
import com.nankai.smartcane.data.network.LocalCueFallDto
import com.nankai.smartcane.data.network.LocalCueMetadataDto
import com.nankai.smartcane.data.network.LocalCueRiskDto
import com.nankai.smartcane.data.network.LocalCueSpeechDto
import com.nankai.smartcane.data.network.EmergencyAlertDto
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
    fun startupSelectsOnlyTheLatestFreshVoiceRequest() {
        val stale = voiceRequestForTest(id = 40, fresh = false)
        val firstFresh = voiceRequestForTest(id = 41, fresh = true)
        val latestFresh = voiceRequestForTest(id = 42, fresh = true)

        assertEquals(latestFresh, latestFreshVoiceRequest(listOf(stale, latestFresh, firstFresh)))
        assertNull(latestFreshVoiceRequest(listOf(stale)))
    }

    @Test
    fun caneVoicePromptStillRequestsAutomaticListening() {
        assertTrue(shouldListenAfterCaneVoiceRequest("voice_request"))
        assertFalse(shouldListenAfterCaneVoiceRequest("front_obstacle"))
    }

    @Test
    fun caneVoicePromptInterruptsNonEmergencySpeech() {
        assertTrue(shouldInterruptCurrentSpeech(TtsPriority.NAVIGATION, TtsPriority.VOICE_REQUEST))
        assertTrue(shouldInterruptCurrentSpeech(TtsPriority.STEP, TtsPriority.VOICE_REQUEST))
        assertFalse(shouldInterruptCurrentSpeech(TtsPriority.EMERGENCY, TtsPriority.VOICE_REQUEST))
    }

    @Test
    fun manualPressCanTakeOverOnlyAutomaticListening() {
        assertEquals(VoicePressStartAction.START_MANUAL, voicePressStartAction(VoiceState.Idle, false))
        assertEquals(VoicePressStartAction.TAKE_OVER_AUTOMATIC, voicePressStartAction(VoiceState.Listening, true))
        assertEquals(VoicePressStartAction.IGNORE, voicePressStartAction(VoiceState.Listening, false))
        assertEquals(VoicePressStartAction.IGNORE, voicePressStartAction(VoiceState.Processing, true))
    }

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
    fun hazardSpeechUsesOneFrontObstaclePromptAndSilencesDownwardGroundRisks() {
        assertEquals(FRONT_OBSTACLE_SPEECH, hazardSpeechText("front_obstacle", "turn_left", "前方障碍，请向左避让"))
        assertEquals(FRONT_OBSTACLE_SPEECH, hazardSpeechText("front_obstacle", "turn_right", "前方障碍，请向右避让"))
        assertEquals(FRONT_OBSTACLE_SPEECH, hazardSpeechText("ground_step", "up", "前方上台阶，注意抬脚"))
        assertEquals(GROUND_DROP_SPEECH, hazardSpeechText("ground_step", "down", "前方下台阶，请减速"))
        assertEquals(GROUND_DROP_SPEECH, hazardSpeechText("ground_drop", "down", "前方有下台阶或落差，请停下"))
        assertEquals(GROUND_DROP_SPEECH, hazardSpeechText("down_no_target", "down", "前方坑洼，请停下"))
        assertEquals("左侧有障碍", hazardSpeechText("left_obstacle", "keep_right", "左侧有障碍，请向右避让"))
        assertEquals("右侧有障碍", hazardSpeechText("right_obstacle", "keep_left", "右侧有障碍，请向左避让"))
    }

    @Test
    fun nearbyRiskPointSpeechKeepsServerLevelTextAndNeverRestoresObstacleWording() {
        assertEquals(
            "前方3米有高风险点",
            nearbyRiskPointSpeechText("front_obstacle", "前方3米有高风险点")
        )
        assertEquals(
            "左侧6米有中风险点",
            nearbyRiskPointSpeechText("left_obstacle", "左侧6米有中风险点")
        )
        assertEquals("前方2米有高风险点", nearbyRiskPointSpeechText("ground_step_down", "前方2米有高风险点"))
        assertEquals("前方2米有中风险点", nearbyRiskPointSpeechText("ground_step", "前方2米有中风险点"))
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
    fun successfulRouteSpeaksConfirmationBeforeTheOverview() {
        assertEquals(
            "收到，已规划好最佳路线。全程八百米，先向北走三百米",
            plannedRouteSpeech("全程八百米，先向北走三百米")
        )
        assertEquals(ROUTE_PLANNED_CONFIRMATION, plannedRouteSpeech("  "))
    }

    @Test
    fun successfulRouteSpeaksCurrentEightPointHeadingAfterRouteDetails() {
        assertEquals(
            "收到，已规划好最佳路线。全程八百米，先向北走三百米。当前朝向为东北",
            plannedRouteSpeech("全程八百米，先向北走三百米", 45f)
        )
        assertEquals("北", eightPointCompassDirection(0f))
        assertEquals("东北", eightPointCompassDirection(22.5f))
        assertEquals("东", eightPointCompassDirection(90f))
        assertEquals("东南", eightPointCompassDirection(135f))
        assertEquals("南", eightPointCompassDirection(180f))
        assertEquals("西南", eightPointCompassDirection(225f))
        assertEquals("西", eightPointCompassDirection(270f))
        assertEquals("西北", eightPointCompassDirection(315f))
        assertEquals("北", eightPointCompassDirection(359.9f))
        assertNull(eightPointCompassDirection(null))
    }

    @Test
    fun crossingRemindersUseTwoDistanceStagesWithoutTrafficData() {
        assertEquals(
            CrossingReminder(30, "前方30米有斑马线，请减速"),
            crossingReminderSpeech("crosswalk", 25.0)
        )
        assertEquals(
            CrossingReminder(10, "前方即将进入斑马线，请停下确认安全后通过"),
            crossingReminderSpeech("crosswalk", 8.0)
        )
        assertEquals(
            CrossingReminder(30, "前方30米有路口，请减速"),
            crossingReminderSpeech("intersection", 30.0)
        )
        assertEquals(
            CrossingReminder(10, "前方即将进入路口，请停下确认安全后通过"),
            crossingReminderSpeech("intersection", 10.0)
        )
        assertNull(crossingReminderSpeech("intersection", 30.1))
        assertNull(crossingReminderSpeech("unknown", 5.0))
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

    private fun voiceRequestForTest(id: Int, fresh: Boolean) = EmergencyAlertDto(
        id = id,
        deviceId = "cane_001",
        riskType = "voice_request",
        riskLevel = "low",
        priority = "info",
        title = "语音交互请求",
        message = "请说目的地或指令",
        voicePrompt = "请说目的地或指令",
        latitude = null,
        longitude = null,
        timestamp = "2026-08-13T10:54:12Z",
        freshForSpeech = fresh
    )
}
