package com.nankai.smartcane.ui.blind

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nankai.smartcane.data.network.EmergencyAlertDto
import com.nankai.smartcane.ui.design.SmartCaneColors
import com.nankai.smartcane.viewmodel.SosActionState
import com.nankai.smartcane.viewmodel.VoiceState
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun BlindHomeScreen(
    voiceState: VoiceState,
    sosState: SosActionState,
    message: String?,
    voiceTranscript: String?,
    urgentAlert: EmergencyAlertDto?,
    fallPending: Boolean,
    navigationPreference: String,
    onVoicePressStart: () -> Unit,
    onVoicePressEnd: () -> Unit,
    onRepeat: () -> Unit,
    onSos: () -> Unit,
    onDismissAlert: () -> Unit,
    onCancelFall: () -> Unit,
    onNavigationPreference: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var showSosConfirm by rememberSaveable { mutableStateOf(false) }
    var startAfterMicPermission by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startAfterMicPermission) {
            onVoicePressStart()
        }
        startAfterMicPermission = false
    }
    val handleVoicePressStart = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onVoicePressStart()
        } else {
            startAfterMicPermission = true
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val prompt = "前方路口建议直行"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SmartCaneColors.BlindTop, Color(0xFF172554), SmartCaneColors.BlindBottom)))
            .statusBarsPadding()
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(0.20f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(urgentAlert?.title ?: "你好，我已准备好。", color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        urgentAlert?.message ?: prompt,
                        color = if (urgentAlert != null) Color(0xFFFECACA) else Color(0xFFC7D2FE),
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2
                    )
                }
                TextButton(onClick = if (urgentAlert != null) onDismissAlert else onOpenSettings, modifier = Modifier.semantics { contentDescription = "我的和设置" }) {
                    Text(if (urgentAlert != null) "知道了" else "设置", color = Color(0xFFE0E7FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.52f)
                    .clip(RoundedCornerShape(36.dp))
                    .background(SmartCaneColors.BlindPanel)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                VoiceOrb(
                    state = voiceState,
                    onPressStart = handleVoicePressStart,
                    onPressEnd = onVoicePressEnd
                )
                Spacer(Modifier.height(18.dp))
                VoiceCaption(
                    voiceState = voiceState,
                    transcript = voiceTranscript,
                    message = message
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onRepeat,
                    enabled = voiceState == VoiceState.Idle,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White,
                        disabledContentColor = Color.White.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .semantics { contentDescription = "重复播报上一条提示" }
                ) {
                    Text("重复播报", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onNavigationPreference("safe") },
                    modifier = Modifier.weight(1f)
                ) { Text(if (navigationPreference == "safe") "✓ 安全优先" else "安全优先") }
                OutlinedButton(
                    onClick = { onNavigationPreference("distance") },
                    modifier = Modifier.weight(1f)
                ) { Text(if (navigationPreference == "distance") "✓ 距离优先" else "距离优先") }
            }
            Spacer(Modifier.height(10.dp))
            if (fallPending) {
                OutlinedButton(
                    onClick = onCancelFall,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFECACA))
                ) {
                    Text("取消疑似跌倒", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
            }
            Box(modifier = Modifier.fillMaxWidth().weight(0.28f), contentAlignment = Alignment.Center) {
                SosButton(sosState = sosState, onClick = { if (sosState != SosActionState.Sending) showSosConfirm = true })
            }
        }
    }

    if (showSosConfirm) {
        AlertDialog(
            onDismissRequest = { showSosConfirm = false },
            title = { Text("确认紧急求助？") },
            text = { Text("确认后提交 SOS 求助。") },
            confirmButton = { TextButton(onClick = { showSosConfirm = false; onSos() }) { Text("确认发送") } },
            dismissButton = { TextButton(onClick = { showSosConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun VoiceOrb(
    state: VoiceState,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val label = when (state) {
        VoiceState.Idle -> "按住说话"
        VoiceState.Listening -> "正在录音"
        VoiceState.Processing -> "正在识别"
        VoiceState.Speaking -> "正在播报"
    }
    val currentState by rememberUpdatedState(state)
    val currentOnPressStart by rememberUpdatedState(onPressStart)
    val currentOnPressEnd by rememberUpdatedState(onPressEnd)
    Box(
        modifier = Modifier
            .size(218.dp)
            .shadow(28.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFFA5B4FC), Color(0xFF6366F1), Color(0xFF312E81))))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val releasedBeforeLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (releasedBeforeLongPress == null && currentState == VoiceState.Idle) {
                        var started = false
                        try {
                            currentOnPressStart()
                            started = true
                            waitForUpOrCancellation()
                        } finally {
                            if (started) currentOnPressEnd()
                        }
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = if (state == VoiceState.Idle) "语音按钮，按住开始说话，松开结束" else "语音处理中，按钮暂不可用"
                stateDescription = label
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            SoundWave(active = state == VoiceState.Listening || state == VoiceState.Processing || state == VoiceState.Speaking)
            Spacer(Modifier.height(14.dp))
            Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
@Composable
private fun SoundWave(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "voice-wave")
    val a by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "a")
    val b by transition.animateFloat(1f, 0.45f, infiniteRepeatable(tween(680), RepeatMode.Reverse), label = "b")
    val c by transition.animateFloat(0.5f, 1f, infiniteRepeatable(tween(440), RepeatMode.Reverse), label = "c")
    val scales = if (active) listOf(a, b, c, b, a) else listOf(0.45f, 0.65f, 0.85f, 0.65f, 0.45f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        scales.forEach { scale ->
            Box(Modifier.size(width = 10.dp, height = (58 * scale).dp).clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.92f)))
        }
    }
}

@Composable
private fun SosButton(sosState: SosActionState, onClick: () -> Unit) {
    val statusText = when (sosState) {
        SosActionState.Idle -> "紧急求助"
        SosActionState.Sending -> "发送中"
        SosActionState.Success -> "已发送"
        SosActionState.Error -> "重试求助"
    }
    Box(
        modifier = Modifier
            .size(168.dp)
            .shadow(20.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFFFF6B6B), Color(0xFFDC2626), Color(0xFF7F1D1D))))
            .clickable(enabled = sosState != SosActionState.Sending, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "紧急求助按钮，双击后需要再次确认"
                stateDescription = statusText
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOS", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(statusText, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun VoiceCaption(
    voiceState: VoiceState,
    transcript: String?,
    message: String?
) {
    val caption = when {
        !transcript.isNullOrBlank() -> transcript
        voiceState == VoiceState.Listening -> "正在录音，松开后识别…"
        voiceState == VoiceState.Processing -> "正在上传并识别语音…"
        voiceState == VoiceState.Speaking -> "\u6b63\u5728\u64ad\u62a5\u5bfc\u822a\u63d0\u793a"
        !message.isNullOrBlank() && message.length <= 24 -> message
        else -> "\u6309\u4f4f\u4e0a\u65b9\u5927\u5706\u8bf4\u8bdd\uff0c\u8bc6\u522b\u6587\u5b57\u4f1a\u663e\u793a\u5728\u8fd9\u91cc"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            if (voiceState == VoiceState.Speaking) "播报频谱" else "\u5b9e\u65f6\u5b57\u5e55",
            color = Color(0xFFC7D2FE),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = caption,
            color = Color.White,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2
        )
    }
}
