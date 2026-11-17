package com.aidock.dock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object DockColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF15161A)
    val SurfaceHigh = Color(0xFF202228)
    val Text = Color(0xFFF7F7FA)
    // 유리 카드 위에서도 읽히도록 예전보다 밝게
    val TextDim = Color(0xFFC2C6D0)
    val Track = Color(0x33FFFFFF)
    val Divider = Color(0x1FFFFFFF)

    // 취침 모드: 검은 화면 위 아주 어두운 회색
    val NightText = Color(0xFF5C5C5C)
    val NightTextDim = Color(0xFF3A3A3A)

    // 글래스모피즘 카드
    val GlassTop = Color(0x2EFFFFFF)
    val GlassBottom = Color(0x12FFFFFF)
    val GlassEdgeTop = Color(0x66FFFFFF)
    val GlassEdgeBottom = Color(0x14FFFFFF)
    val Ok = Color(0xFF5BD18B)
    val Warn = Color(0xFFF2B84B)
    val Danger = Color(0xFFF2655A)

    // 서비스별 강조색
    val Claude = Color(0xFFD97757)
    val Codex = Color(0xFF8AB4F8)
    val Gemini = Color(0xFFA78BFA)

    fun provider(id: String) = when (id) {
        "claude" -> Claude
        "codex" -> Codex
        "gemini" -> Gemini
        else -> TextDim
    }

    /** 사용률에 따라 막대 색: 낮으면 서비스 색, 70% 넘으면 경고, 90% 넘으면 위험 */
    fun usage(id: String, pct: Double) = when {
        pct >= 90 -> Danger
        pct >= 70 -> Warn
        else -> provider(id)
    }
}

@Composable
fun DockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DockColors.Background,
            surface = DockColors.Surface,
            onBackground = DockColors.Text,
            onSurface = DockColors.Text,
            primary = DockColors.Text,
        ),
        content = content,
    )
}
