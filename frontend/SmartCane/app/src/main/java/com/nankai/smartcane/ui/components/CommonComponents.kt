package com.nankai.smartcane.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val SmartTeal = Color(0xFF0F766E)
val SmartDark = Color(0xFF0F172A)
val SmartMuted = Color(0xFF64748B)
val SmartBg = Color(0xFFEEF6F7)
val SmartCard = Color.White
val BlindBg = Color(0xFF050816)
val BlindCard = Color(0xFF111827)
val BlindAccent = Color(0xFFFACC15)

@Composable
fun ScreenTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    dark: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$title。$subtitle"
            }
    ) {
        Text(
            text = title,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            color = if (dark) Color.White else SmartDark
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            color = if (dark) Color(0xFFE5E7EB) else SmartMuted
        )
    }
}

@Composable
fun DemoBanner(text: String, modifier: Modifier = Modifier, dark: Boolean = false) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (dark) Color(0xFF3B2F05) else Color(0xFFFFF7ED),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(14.dp),
            color = if (dark) Color(0xFFFFF7CC) else Color(0xFF9A3412),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun BigPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = SmartTeal,
    contentColor: Color = Color.White,
    minHeight: Dp = 56.dp
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(text = text, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
fun BigSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 56.dp
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(text = text, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    containerColor: Color = SmartCard,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                if (description != null) contentDescription = description
            },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = title,
                fontSize = 19.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Bold,
                color = if (containerColor == BlindCard) Color.White else SmartDark
            )
            content()
        }
    }
}

@Composable
fun LabelValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = SmartDark,
    dark: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = if (dark) Color(0xFFD1D5DB) else SmartMuted,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.3f)
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFDCFCE7),
    textColor: Color = Color(0xFF166534)
) {
    Row(
        modifier = modifier
            .background(color, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = textColor, shape = RoundedCornerShape(999.dp), modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.size(8.dp))
        Text(text = text, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}


