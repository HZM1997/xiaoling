package com.xiaoling.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val Scheme = darkColorScheme(
    primary = Color(0xFFF2B705),
    onPrimary = Color(0xFF3A2412),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    error = Color(0xFFE5534B)
)

// 适老:整体字号偏大
private val BigType = Typography(
    bodyLarge = TextStyle(fontSize = 20.sp),
    titleLarge = TextStyle(fontSize = 28.sp)
)

@Composable
fun XiaolingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = BigType, content = content)
}
