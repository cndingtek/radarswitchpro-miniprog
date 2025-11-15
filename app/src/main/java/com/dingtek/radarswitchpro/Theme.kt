package com.dingtek.radarswitchpro

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable

@Composable
fun RadarSwitchTheme(content: @Composable () -> Unit) {
    val blue = Color(0xFF2d7bf3)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = blue,
            onPrimary = Color.White,
            outline = blue
        ),
        content = content
    )
}
