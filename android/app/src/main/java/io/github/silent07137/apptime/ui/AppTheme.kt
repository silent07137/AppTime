// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF176B5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF2E8), onPrimaryContainer = Color(0xFF174E40),
    secondary = Color(0xFF687F76), secondaryContainer = Color(0xFFE8EFE9), onSecondaryContainer = Color(0xFF174E40),
    background = Color(0xFFF5F7F4), onBackground = Color(0xFF202D28),
    surface = Color(0xFFFEFFFC), onSurface = Color(0xFF202D28),
    surfaceContainer = Color(0xFFECF0E9), surfaceContainerHigh = Color(0xFFF0F3EC), surfaceContainerLow = Color(0xFFF5F7F4),
    surfaceVariant = Color(0xFFECF0E9), onSurfaceVariant = Color(0xFF65716A),
    outlineVariant = Color(0xFFDDE4DC))
private val Dark = darkColorScheme(
    primary = Color(0xFF99D8BE), onPrimary = Color(0xFF103E31),
    primaryContainer = Color(0xFF244B3F), onPrimaryContainer = Color(0xFFDAF5E6),
    secondary = Color(0xFFA7BEB2), secondaryContainer = Color(0xFF2C3B33), onSecondaryContainer = Color(0xFFDAF5E6),
    background = Color(0xFF141C18), onBackground = Color(0xFFE2EBE4),
    surface = Color(0xFF1D2721), onSurface = Color(0xFFE2EBE4),
    surfaceContainer = Color(0xFF1D2721), surfaceContainerHigh = Color(0xFF26332B), surfaceContainerLow = Color(0xFF19241D),
    surfaceVariant = Color(0xFF2C3931), onSurfaceVariant = Color(0xFFABBAB0),
    outlineVariant = Color(0xFF3B4940))

@Composable fun AppTimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content)
}
