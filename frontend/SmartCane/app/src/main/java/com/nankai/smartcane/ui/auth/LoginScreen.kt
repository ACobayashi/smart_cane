package com.nankai.smartcane.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nankai.smartcane.data.model.UserRole
import com.nankai.smartcane.ui.components.SmartTeal

@Composable
fun LoginScreen(
    isBusy: Boolean,
    message: String?,
    onLogin: (String, String, Boolean) -> Unit,
    onRegister: (String, String, String, UserRole, Boolean) -> Unit
) {
    var isRegister by rememberSaveable { mutableStateOf(false) }
    var account by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(UserRole.Blind) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        if (!message.isNullOrBlank() && !message.contains("\u6210\u529f")) localError = message
    }

    LaunchedEffect(isRegister) {
        if (isRegister) {
            account = ""
            password = ""
            confirmPassword = ""
            displayName = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFEAF7F6), Color(0xFFF7FAFC), Color(0xFFEFF6FF))
                )
            )
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.size(72.dp).shadow(10.dp, CircleShape),
                shape = CircleShape,
                color = Color.White
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("S", color = SmartTeal, fontSize = 36.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("SmartCane", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A))
            Text(if (isRegister) "\u521b\u5efa\u8d26\u53f7" else "\u667a\u80fd\u51fa\u884c\u4e0e\u8fdc\u7a0b\u966a\u62a4", fontSize = 16.sp, color = Color(0xFF64748B), lineHeight = 22.sp)

            Spacer(Modifier.height(if (isRegister) 22.dp else 34.dp))
            OutlinedTextField(
                value = account,
                onValueChange = { account = it; localError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("\u8d26\u53f7") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; localError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("\u5bc6\u7801") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White),
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.semantics { contentDescription = if (passwordVisible) "\u9690\u85cf\u5bc6\u7801" else "\u663e\u793a\u5bc6\u7801" }
                    ) { Text(if (passwordVisible) "\u9690\u85cf" else "\u663e\u793a", fontSize = 13.sp, color = SmartTeal) }
                }
            )
            if (isRegister) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; localError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("\u786e\u8ba4\u5bc6\u7801") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; localError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("\u6635\u79f0") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White)
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { role = UserRole.Blind }, modifier = Modifier.weight(1f)) { Text(if (role == UserRole.Blind) "\u2713 \u7528\u6237" else "\u7528\u6237") }
                    OutlinedButton(onClick = { role = UserRole.Companion }, modifier = Modifier.weight(1f)) { Text(if (role == UserRole.Companion) "\u2713 \u966a\u62a4" else "\u966a\u62a4") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.heightIn(min = 22.dp), contentAlignment = Alignment.CenterStart) {
                localError?.let { Text(it, color = Color(0xFFDC2626), fontSize = 14.sp, maxLines = 1) }
            }
            Button(
                onClick = {
                    localError = when {
                        account.isBlank() -> "\u8bf7\u8f93\u5165\u8d26\u53f7"
                        account.length < 3 -> "\u8d26\u53f7\u81f3\u5c11 3 \u4f4d"
                        password.isBlank() -> "\u8bf7\u8f93\u5165\u5bc6\u7801"
                        password.length < 6 -> "\u5bc6\u7801\u81f3\u5c11 6 \u4f4d"
                        isRegister && confirmPassword != password -> "\u4e24\u6b21\u5bc6\u7801\u4e0d\u4e00\u81f4"
                        isRegister && displayName.isBlank() -> "\u8bf7\u8f93\u5165\u6635\u79f0"
                        else -> null
                    }
                    if (localError == null) {
                        if (isRegister) {
                            onRegister(account, password, displayName, role, true)
                        } else {
                            onLogin(account, password, true)
                        }
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SmartTeal)
            ) {
                Text(
                    if (isBusy) {
                        if (isRegister) "\u6ce8\u518c\u4e2d\u2026" else "\u767b\u5f55\u4e2d\u2026"
                    } else {
                        if (isRegister) "\u6ce8\u518c" else "\u767b\u5f55"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = {
                    isRegister = !isRegister
                    localError = null
                    if (isRegister) {
                        if (account == "demo") account = ""
                        if (password == "123456") password = ""
                        confirmPassword = ""
                    }
                }
            ) {
                Text(if (isRegister) "\u5df2\u6709\u8d26\u53f7\uff0c\u53bb\u767b\u5f55" else "\u6ca1\u6709\u8d26\u53f7\uff0c\u7acb\u5373\u6ce8\u518c", color = SmartTeal, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))
            if (!isRegister) {
                Surface(color = Color.White.copy(alpha = 0.78f), shape = RoundedCornerShape(18.dp)) {
                    Text(
                        "\u53ef\u7528\u8d26\u53f7\uff1Ademo / 123456",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        color = Color(0xFF64748B),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(if (isRegister) "\u6ce8\u518c\u540e\u53ef\u9009\u62e9\u7528\u6237\u6216\u966a\u62a4\u4eba\u5165\u53e3" else "\u767b\u5f55\u540e\u9009\u62e9\u7528\u6237\u6216\u966a\u62a4\u4eba\u5165\u53e3", color = Color(0xFF94A3B8), fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
        }
    }
}
