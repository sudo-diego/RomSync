package io.github.sudodiego.romsync

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Paleta de los fondos de la Y700 (la misma de TBF Companion).
object Palette {
    val indigo = Color(0xFF3A55C8)
    val indigoLight = Color(0xFF8FB2FF)
    val sky = Color(0xFF1E8FD6)
    val skyLight = Color(0xFF5CC8FF)
    val ok = Color(0xFF16A34A)
    val warn = Color(0xFFD97706)
    val danger = Color(0xFFDC2626)
}

private val dark = darkColorScheme(
    primary = Palette.indigoLight,
    onPrimary = Color(0xFF0D1220),
    secondary = Palette.skyLight,
    onSecondary = Color(0xFF0D1220),
    background = Color(0xFF0D1220),
    onBackground = Color(0xFFE8EEFC),
    surface = Color(0xFF151C2F),
    onSurface = Color(0xFFE8EEFC),
    surfaceVariant = Color(0xFF1B2338),
    onSurfaceVariant = Color(0xFF93A2C3),
    surfaceContainer = Color(0xFF151C2F),
    surfaceContainerHigh = Color(0xFF1B2338),
    surfaceContainerHighest = Color(0xFF232C45),
    outline = Color(0xFF26304A),
    outlineVariant = Color(0xFF26304A),
    error = Color(0xFFF87171),
    secondaryContainer = Color(0xFF26345F),
    onSecondaryContainer = Color(0xFFE8EEFC),
)

private val light = lightColorScheme(
    primary = Palette.indigo,
    onPrimary = Color.White,
    secondary = Palette.sky,
    onSecondary = Color.White,
    background = Color(0xFFEDF2F8),
    onBackground = Color(0xFF0F1A33),
    surface = Color.White,
    onSurface = Color(0xFF0F1A33),
    surfaceVariant = Color(0xFFF5F8FC),
    onSurfaceVariant = Color(0xFF5B6B86),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF5F8FC),
    surfaceContainerHighest = Color(0xFFE9EFF7),
    outline = Color(0xFFDBE3EE),
    outlineVariant = Color(0xFFDBE3EE),
    error = Palette.danger,
    secondaryContainer = Color(0xFFDCE5FB),
    onSecondaryContainer = Color(0xFF0F1A33),
)

@Composable
fun RomSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dark else light,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}

val accentBrush get() = Brush.horizontalGradient(listOf(Palette.indigo, Palette.sky))

/** Barra de uso: segmentos (fracción del total, color) sobre un fondo. */
@Composable
fun UsageBar(segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val parts = segments.filter { it.first > 0.0005f }
    val used = parts.sumOf { it.first.toDouble() }.toFloat().coerceAtMost(1f)
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(99.dp))
            .background(MaterialTheme.colorScheme.outline)
    ) {
        parts.forEach { (frac, color) -> Box(Modifier.weight(frac).fillMaxHeight().background(color)) }
        if (used < 1f) Spacer(Modifier.weight(1f - used))
    }
}
