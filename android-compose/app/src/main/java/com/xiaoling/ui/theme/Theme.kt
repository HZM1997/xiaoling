package com.xiaoling.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 亲民 + 高级极简:浅色、柔和蓝、留白
val BgTop = Color(0xFFDCE8FB)
val BgBottom = Color(0xFFF4F8FF)
val AccentBlue = Color(0xFF4C82F7)
val InkColor = Color(0xFF1B2430)
val DimColor = Color(0xFF6B7787)
val AlarmRed = Color(0xFFE5534B)

private val Scheme = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    background = BgBottom,
    onBackground = InkColor,
    surface = Color.White,
    onSurface = InkColor,
    onSurfaceVariant = DimColor,
    error = AlarmRed
)

private val AppType = Typography(
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 15.sp)
)

@Composable
fun XiaolingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppType, content = content)
}
