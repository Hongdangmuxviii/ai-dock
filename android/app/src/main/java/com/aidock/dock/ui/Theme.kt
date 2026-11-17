package com.aidock.dock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object DockColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF15161A)
    val SurfaceHigh = Color(0xFF202228)
    val Text = Color(0xFFF2F2F2)
    val TextDim = Color(0xFF8B8F98)
    val Track = Color(0xFF2C2F36)
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
