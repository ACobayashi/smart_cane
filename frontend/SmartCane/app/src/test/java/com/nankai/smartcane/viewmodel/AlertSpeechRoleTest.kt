package com.nankai.smartcane.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertSpeechRoleTest {
    @Test
    fun companionSosUsesCaregiverPrompt() {
        assertEquals(
            "盲人用户发起紧急求助，请查看",
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
