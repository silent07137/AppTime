// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

enum class Symbol { CLOCK, APPS, CHART, SETTINGS, REFRESH, BACK, INFO, SEARCH, CHEVRON }

@Composable fun Glyph(symbol: Symbol, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, Offset(x, y), Offset(a, b), 1.8f, StrokeCap.Round)
            when (symbol) {
                Symbol.CLOCK -> { drawCircle(color, 8.5f, Offset(12f, 12f), style = stroke); line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f) }
                Symbol.APPS -> for (x in listOf(4f, 14f)) for (y in listOf(4f, 14f)) drawRoundRect(color, Offset(x, y), Size(6f, 6f), androidx.compose.ui.geometry.CornerRadius(1.5f), style = stroke)
                Symbol.CHART -> { line(4f, 20f, 20f, 20f); line(6f, 16f, 6f, 10f); line(12f, 16f, 12f, 4f); line(18f, 16f, 18f, 8f) }
                Symbol.SETTINGS -> { drawCircle(color, 3f, Offset(12f, 12f), style = stroke); val p = Path().apply { moveTo(9f, 3f); lineTo(15f, 3f); lineTo(16f, 6f); lineTo(20f, 8f); lineTo(20f, 16f); lineTo(16f, 18f); lineTo(15f, 21f); lineTo(9f, 21f); lineTo(8f, 18f); lineTo(4f, 16f); lineTo(4f, 8f); lineTo(8f, 6f); close() }; drawPath(p, color, style = stroke) }
                Symbol.REFRESH -> { drawArc(color, 35f, 290f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke); line(20f, 4f, 20f, 9f); line(20f, 9f, 15f, 9f) }
                Symbol.BACK -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
                Symbol.INFO -> { drawCircle(color, 8.5f, Offset(12f, 12f), style = stroke); drawCircle(color, 1f, Offset(12f, 7.5f)); line(12f, 11f, 12f, 16f) }
                Symbol.SEARCH -> { drawCircle(color, 6.5f, Offset(10f, 10f), style = stroke); line(15f, 15f, 21f, 21f) }
                Symbol.CHEVRON -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            }
        }
    }
}
