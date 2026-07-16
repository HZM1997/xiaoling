package com.xiaoling.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 主色白 · 辅色蓝 · 活力亲和智能感:通透留白,一抹活力蓝
val BgTop = Color(0xFFFFFFFF)
val BgMid = Color(0xFFF4F8FF)
val BgBottom = Color(0xFFEAF2FF)
val AccentBlue = Color(0xFF2F6BFF)
val AccentGlow = Color(0xFF4EA8FF)
val InkColor = Color(0xFF16233B)
val DimColor = Color(0xFF8A93A6)
val AlarmRed = Color(0xFFE5484D)
val CardBg = Color(0xFFFFFFFF)

private val Scheme = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = AccentGlow,
    background = BgBottom,
    onBackground = InkColor,
    surface = CardBg,
    onSurface = InkColor,
    onSurfaceVariant = DimColor,
    error = AlarmRed
)

private val AppType = Typography(
    titleLarge = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, color = DimColor)
)

@Composable
fun XiaolingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppType, content = content)
}
