package com.xiaoling.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 高级 · 亲和 · 智能感:柔和渐变紫蓝,通透留白
val BgTop = Color(0xFFEAF0FF)
val BgMid = Color(0xFFF3EEFF)
val BgBottom = Color(0xFFFBFCFF)
val AccentBlue = Color(0xFF4C6FFF)
val AccentGlow = Color(0xFF7C5CFF)
val InkColor = Color(0xFF1A1F36)
val DimColor = Color(0xFF7A8296)
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
