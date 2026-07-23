package com.nankai.smartcane.ui.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nankai.smartcane.data.model.CareRequest
import com.nankai.smartcane.data.model.PairingCode
import com.nankai.smartcane.data.model.PairingFlowStatus
import com.nankai.smartcane.data.model.RelationStatus
import com.nankai.smartcane.ui.components.BigPrimaryButton
import com.nankai.smartcane.ui.components.BigSecondaryButton
import com.nankai.smartcane.ui.components.LabelValueRow
import com.nankai.smartcane.ui.components.ScreenTitle
import com.nankai.smartcane.ui.design.SmartCaneColors
import com.nankai.smartcane.ui.design.SmartCaneShapes
import kotlinx.coroutines.delay

@Composable
fun BlindPairingScreen(
    pairingCode: String?,
    pairingExpiresAtMillis: Long?,
    pendingRequest: CareRequest?,
    pairingStatus: PairingFlowStatus,
    relationStatus: RelationStatus,
    companionName: String?,
    isBusy: Boolean,
    onGenerateCode: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onUnlink: () -> Unit,
    onBack: () -> Unit,
    onSwitchToCompanion: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var showUnlinkConfirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pairingExpiresAtMillis) {
        while (true) { now = System.currentTimeMillis(); delay(1000L) }
    }
    val remainingSeconds = ((pairingExpiresAtMillis ?: 0L) - now).coerceAtLeast(0L) / 1000L
    val remainingText = "${remainingSeconds / 60}分${remainingSeconds % 60}秒"
    val connected = relationStatus == RelationStatus.Active

    Column(
        modifier = Modifier.fillMaxSize().background(SmartCaneColors.Background).statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenTitle("邀请陪护人", if (connected) "已建立陪护关系" else "让陪护端输入你的配对码")

        Card(shape = SmartCaneShapes.PageCard, colors = CardDefaults.cardColors(containerColor = SmartCaneColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (connected) "已关联" else "你的邀请码", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SmartCaneColors.TextPrimary)
                when {
                    connected -> {
                        Text(companionName ?: "陪护用户", fontSize = 34.sp, fontWeight = FontWeight.Black, color = SmartCaneColors.Primary)
                        Text("对方可以查看你的导航状态和 SOS 信息。", color = SmartCaneColors.TextSecondary, fontSize = 15.sp)
                        BigSecondaryButton("解除关联", onClick = { showUnlinkConfirm = true })
                    }
                    pairingCode != null && remainingSeconds > 0 -> {
                        Text(
                            pairingCode,
                            fontSize = 50.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 6.sp,
                            color = SmartCaneColors.TextPrimary,
                            modifier = Modifier.semantics { contentDescription = "配对码${pairingCode.toSpokenDigits()}，剩余$remainingText" }
                        )
                        Text("有效期 $remainingText", color = SmartCaneColors.TextSecondary, fontSize = 16.sp)
                        BigPrimaryButton("复制邀请码", onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SmartCane 配对码", pairingCode))
                        })
                    }
                    else -> {
                        Text("生成后 10 分钟内有效。", color = SmartCaneColors.TextSecondary, fontSize = 16.sp)
                        BigPrimaryButton(if (isBusy) "生成中…" else "生成邀请码", enabled = !isBusy, onClick = onGenerateCode)
                    }
                }
            }
        }

        Card(shape = SmartCaneShapes.Card, colors = CardDefaults.cardColors(containerColor = SmartCaneColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("申请", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SmartCaneColors.TextPrimary)
                if (pendingRequest != null) {
                    Text("${pendingRequest.companionUser.displayName} 请求成为你的陪护人", color = SmartCaneColors.TextPrimary, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigPrimaryButton("同意", enabled = !isBusy, onClick = onApprove, modifier = Modifier.weight(1f))
                        BigSecondaryButton("拒绝", enabled = !isBusy, onClick = onReject, modifier = Modifier.weight(1f))
                    }
                } else {
                    Text(if (pairingStatus == PairingFlowStatus.Waiting) "正在等待陪护人发送申请。" else "暂无新的申请。", color = SmartCaneColors.TextSecondary, fontSize = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        BigSecondaryButton("返回首页", onClick = onBack)
        BigPrimaryButton("切换到陪护人界面", onClick = onSwitchToCompanion)
        BigSecondaryButton("退出登录", onClick = onLogout)
    }

    if (showUnlinkConfirm) {
        AlertDialog(
            onDismissRequest = { showUnlinkConfirm = false },
            title = { Text("解除关联？") },
            text = { Text("解除后陪护端将不再看到你的状态。") },
            confirmButton = { TextButton(onClick = { showUnlinkConfirm = false; onUnlink() }) { Text("解除") } },
            dismissButton = { TextButton(onClick = { showUnlinkConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
fun CompanionPairingScreen(
    preview: PairingCode?,
    pairingStatus: PairingFlowStatus,
    relationStatus: RelationStatus,
    isBusy: Boolean,
    message: String?,
    onFindCode: (String) -> Unit,
    onSendRequest: (String) -> Unit,
    onUnlink: () -> Unit,
    onBack: () -> Unit
) {
    var code by rememberSaveable { mutableStateOf("") }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var showUnlinkConfirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(message) { if (!message.isNullOrBlank()) localMessage = message }
    val matchedPreview = preview?.takeIf { it.code == code }
    val step = when {
        relationStatus == RelationStatus.Active || pairingStatus == PairingFlowStatus.Connected -> 3
        pairingStatus == PairingFlowStatus.Waiting -> 3
        matchedPreview != null -> 2
        else -> 1
    }

    Column(
        modifier = Modifier.fillMaxSize().background(SmartCaneColors.Background).statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenTitle("\u901a\u8fc7\u9080\u8bf7\u7801\u6dfb\u52a0\u966a\u62a4\u5bf9\u8c61", "\u8f93\u5165\u5bf9\u65b9\u7684\u9080\u8bf7\u7801\u5b8c\u6210\u5173\u8054")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepPill("1 输入", step >= 1, Modifier.weight(1f))
            StepPill("2 确认", step >= 2, Modifier.weight(1f))
            StepPill("3 等待", step >= 3, Modifier.weight(1f))
        }

        Card(shape = SmartCaneShapes.PageCard, colors = CardDefaults.cardColors(containerColor = SmartCaneColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("六位邀请码", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SmartCaneColors.TextPrimary)
                OutlinedTextField(
                    value = code,
                    onValueChange = { input -> code = input.filter(Char::isDigit).take(6); localMessage = null },
                    label = { Text("配对码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                BigPrimaryButton(if (isBusy) "\u67e5\u627e\u4e2d…" else "\u67e5\u627e\u7528\u6237", enabled = code.length == 6 && !isBusy && relationStatus != RelationStatus.Active, onClick = { onFindCode(code) })
            }
        }

        if (matchedPreview != null) {
            Card(shape = SmartCaneShapes.Card, colors = CardDefaults.cardColors(containerColor = SmartCaneColors.Surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("\u786e\u8ba4\u5bf9\u8c61", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SmartCaneColors.TextPrimary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.size(46.dp), shape = CircleShape, color = SmartCaneColors.Primary) {
                            Box(contentAlignment = Alignment.Center) { Text("\u76f2", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(matchedPreview.blindUser.displayName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SmartCaneColors.TextPrimary)
                            Text("\u786e\u8ba4\u540e\u5c06\u5411\u5bf9\u65b9\u53d1\u9001\u5173\u8054\u7533\u8bf7\u3002", color = SmartCaneColors.TextSecondary, fontSize = 14.sp)
                        }
                    }
                    LabelValueRow("\u8bbe\u5907", matchedPreview.caneDevice.name)
                    BigPrimaryButton(if (isBusy) "\u63d0\u4ea4\u4e2d?" else "\u786e\u8ba4\u5173\u8054", enabled = !isBusy && relationStatus != RelationStatus.Active, onClick = { onSendRequest(matchedPreview.code) })
                }
            }
        }

        Card(shape = SmartCaneShapes.Card, colors = CardDefaults.cardColors(containerColor = SmartCaneColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前状态", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SmartCaneColors.TextPrimary)
                Text(pairingStatus.displayName, fontSize = 26.sp, fontWeight = FontWeight.Black, color = if (pairingStatus == PairingFlowStatus.Error || pairingStatus == PairingFlowStatus.Rejected) SmartCaneColors.Danger else SmartCaneColors.Primary)
                Text(statusText(pairingStatus), color = SmartCaneColors.TextSecondary, fontSize = 16.sp, lineHeight = 23.sp)
                localMessage?.takeIf { pairingStatus == PairingFlowStatus.Error }?.let { Text(it, color = SmartCaneColors.Danger, fontSize = 14.sp, maxLines = 2) }
                if (relationStatus == RelationStatus.Active) BigSecondaryButton("解除关联", onClick = { showUnlinkConfirm = true })
            }
        }
        BigSecondaryButton("返回陪护首页", onClick = onBack)
    }

    if (showUnlinkConfirm) {
        AlertDialog(
            onDismissRequest = { showUnlinkConfirm = false },
            title = { Text("解除关联？") },
            text = { Text("解除后双方需要重新配对。") },
            confirmButton = { TextButton(onClick = { showUnlinkConfirm = false; onUnlink() }) { Text("解除") } },
            dismissButton = { TextButton(onClick = { showUnlinkConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun StepPill(text: String, active: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = SmartCaneShapes.Pill, color = if (active) SmartCaneColors.Primary else SmartCaneColors.Surface) {
        Text(text, modifier = Modifier.padding(vertical = 9.dp), color = if (active) androidx.compose.ui.graphics.Color.White else SmartCaneColors.TextSecondary, textAlign = TextAlign.Center, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun statusText(status: PairingFlowStatus): String = when (status) {
    PairingFlowStatus.Waiting -> "申请已发送，等待对方同意。"
    PairingFlowStatus.PendingApproval -> "对方正在确认。"
    PairingFlowStatus.Connected -> "关联成功，可以返回首页查看状态。"
    PairingFlowStatus.Rejected -> "对方已拒绝，请重新确认。"
    PairingFlowStatus.Expired -> "邀请码已过期，请让对方重新生成。"
    PairingFlowStatus.Error -> "操作失败，请检查网络或重试。"
    PairingFlowStatus.Loading -> "正在处理。"
    PairingFlowStatus.Idle -> "等待输入邀请码。"
}

private fun String.toSpokenDigits(): String = toCharArray().joinToString("") { it.toString() }
