package com.nankai.smartcane.viewmodel

import com.nankai.smartcane.data.network.LatestRiskEventDto
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
    fun realtimeSensorSpeechOnlyDescribesTheSituation() {
        val front = LatestRiskEventDto(1, "cane", "front_obstacle", "high", 320, "", null, null, "", "")
        val left = LatestRiskEventDto(2, "cane", "left_obstacle", "medium", 450, "", null, null, "", "")
        val up = LatestRiskEventDto(3, "cane", "ground_step_up", "medium", null, "", null, null, "", "")
        val drop = LatestRiskEventDto(4, "cane", "ground_drop", "high", null, "", null, null, "", "")
        val sensor = LatestRiskEventDto(5, "cane", "down_sensor_unavailable", "high", null, "", null, null, "", "")

        assertEquals("前方32厘米有障碍", realtimeHardwareEventPrompt(front))
        assertEquals("左侧45厘米有障碍", realtimeHardwareEventPrompt(left))
        assertEquals("前方上台阶", realtimeHardwareEventPrompt(up))
        assertEquals("前方有落差", realtimeHardwareEventPrompt(drop))
        assertEquals("下视传感器异常", realtimeHardwareEventPrompt(sensor))
        assertEquals(
            "前方障碍持续",
            alertSpeechForRole(
                role = "blind",
                riskType = "prolonged_obstacle",
                voicePrompt = "同一障碍持续出现，已通知陪护端，请停止并重新探测",
                message = "持续障碍",
                sosAlarmActive = false
            )
        )
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
    fun fallDetectedKeepsExistingBlindSpeech() {
        assertEquals(
            "检测到跌倒",
            alertSpeechForRole(
                role = "blind",
                riskType = "fall_detected",
                voicePrompt = "原始跌倒提示",
                message = "原始跌倒消息",
                sosAlarmActive = false
            )
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
}
