package com.nankai.smartcane.data.local

import com.nankai.smartcane.data.model.CaneDevice
import com.nankai.smartcane.data.model.DemoNavigationScenario
import com.nankai.smartcane.data.model.DemoRiskAlert
import com.nankai.smartcane.data.model.UserProfile
import com.nankai.smartcane.data.model.UserRole

object DemoData {
    const val TEST_ACCOUNT = "demo"
    const val BLIND_ACCOUNT = "blind_demo"
    const val COMPANION_ACCOUNT = "companion_demo"
    const val DEMO_PASSWORD = "123456"
    const val DEFAULT_PAIRING_CODE = "583216"

    val blindUser = UserProfile(
        userId = "user_demo_001",
        account = TEST_ACCOUNT,
        displayName = "演示用户",
        role = UserRole.Blind,
        isDemo = true
    )

    val companionUser = UserProfile(
        userId = "user_companion_001",
        account = COMPANION_ACCOUNT,
        displayName = "李华",
        role = UserRole.Companion,
        isDemo = true
    )

    val defaultCane = CaneDevice(
        deviceId = "cane_001",
        name = "SmartCane 001",
        online = true,
        lastSeenText = "刚刚"
    )

    val navigationScenario = DemoNavigationScenario(
        destination = "南开大学图书馆",
        nextInstruction = "前方十八米进入十字路口，请保持直行并靠右等待语音提示。",
        distanceToIntersectionMeters = 18,
        recommendedDirection = "直行",
        riskLevel = "较低风险",
        riskReason = listOf(
            "该方向设有人行横道",
            "近期障碍事件较少",
            "左转方向存在临时施工"
        )
    )

    val latestRiskAlert = DemoRiskAlert(
        level = "较低风险",
        message = "前方路口人行横道通行条件较好，左转方向存在临时施工，建议直行。",
        timeText = "刚刚更新"
    )
}
