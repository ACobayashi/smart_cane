package com.nankai.smartcane.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nankai.smartcane.data.local.DemoData
import com.nankai.smartcane.data.model.CareRelation
import com.nankai.smartcane.data.model.RelationStatus
import com.nankai.smartcane.data.network.EmergencyAlertDto
import com.nankai.smartcane.ui.components.BigPrimaryButton
import com.nankai.smartcane.ui.components.BigSecondaryButton
import com.nankai.smartcane.ui.components.LabelValueRow
import com.nankai.smartcane.ui.components.SmartBg
import com.nankai.smartcane.ui.components.SmartDark
import com.nankai.smartcane.ui.components.SmartMuted
import com.nankai.smartcane.ui.components.StatusPill

@Composable
fun CompanionHomeScreen(
    userName: String,
    relation: CareRelation?,
    relationUpdateText: String,
    urgentAlert: EmergencyAlertDto?,
    onAddCareTarget: () -> Unit,
    onViewLocation: () -> Unit,
    onViewRiskRecords: () -> Unit,
    onViewDevice: () -> Unit,
    onSwitchMode: () -> Unit,
    onLogout: () -> Unit,
    onDismissAlert: () -> Unit,
    onUnlink: () -> Unit
) {
    var showUnlink by rememberSaveable { mutableStateOf(false) }
    val cane = relation?.caneDevice ?: DemoData.defaultCane
    val active = relation?.status == RelationStatus.Active
    val careTargetName = if (active) relation?.blindUser?.displayName ?: DemoData.blindUser.displayName else "未关联"
    val latestRisk = urgentAlert?.message ?: if (active) DemoData.latestRiskAlert.message else "暂无"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartBg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "陪护人$userName" },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(userName, color = SmartDark, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("陪护人", color = SmartMuted, fontSize = 16.sp, lineHeight = 22.sp)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "关联用户$careTargetName，${if (active) "已关联" else "未关联"}" },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("关联用户", color = SmartMuted, fontSize = 15.sp)
                Text(careTargetName, color = SmartDark, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(if (active) "可以查看位置、风险、SOS 和设备状态。" else "请先添加需要陪护的用户。", color = SmartMuted, fontSize = 16.sp, lineHeight = 23.sp)
                if (!active) BigPrimaryButton("添加用户", onClick = onAddCareTarget)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactMetric("关联", if (active) "已建立" else "未关联", Modifier.weight(1f))
            CompactMetric("设备", if (cane.online) "在线" else "离线", Modifier.weight(1f))
            CompactMetric("SOS", if (urgentAlert != null) "有提醒" else "正常", Modifier.weight(1f))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("功能总览", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SmartDark)
                if (urgentAlert != null) {
                    LabelValueRow(urgentAlert.title, urgentAlert.message)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatusPill(urgentAlert.priority.uppercase())
                        TextButton(onClick = onDismissAlert) { Text("已知晓") }
                    }
                }
                LabelValueRow("位置", if (active) "查看用户当前位置" else "关联后可用")
                LabelValueRow("风险", latestRisk)
                LabelValueRow("设备", if (cane.online) "已连接" else "未连接")
                LabelValueRow("SOS", if (urgentAlert != null) "收到紧急提醒" else "暂无紧急提醒")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatusPill(if (cane.online) "在线" else "离线")
                    Text("最后更新 ${cane.lastSeenText}", color = SmartDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                LabelValueRow("更新时间", if (active) relationUpdateText else "暂无")
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigPrimaryButton("位置", onClick = onViewLocation, modifier = Modifier.weight(1f))
            BigSecondaryButton("风险", onClick = onViewRiskRecords, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigSecondaryButton("设备", onClick = onViewDevice, modifier = Modifier.weight(1f))
            BigSecondaryButton("管理", onClick = onAddCareTarget, modifier = Modifier.weight(1f))
        }
        if (active) BigSecondaryButton("解除关联", onClick = { showUnlink = true })
        BigSecondaryButton("切换到用户入口", onClick = onSwitchMode)
        BigSecondaryButton("退出登录", onClick = onLogout)
    }

    if (showUnlink) {
        AlertDialog(
            onDismissRequest = { showUnlink = false },
            title = { Text("解除关联？") },
            text = { Text("解除后需要重新配对。") },
            confirmButton = { TextButton(onClick = { showUnlink = false; onUnlink() }) { Text("解除") } },
            dismissButton = { TextButton(onClick = { showUnlink = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CompactMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = SmartMuted, fontSize = 13.sp, maxLines = 1)
            Text(value, color = SmartDark, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
