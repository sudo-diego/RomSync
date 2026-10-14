package io.github.sudodiego.romsync

import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
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

// Paleta: la vibra de las eShop de 3DS (naranja) y Wii U (azul), con los verdes/ámbar de siempre para los estados.
object Palette {
    val orange = Color(0xFFFF7A1A)       // eShop de 3DS
    val orangeLight = Color(0xFFFFB37A)
    val blue = Color(0xFF1AA3E8)         // eShop de Wii U
    val blueLight = Color(0xFF7FD3FF)
    val indigo = Color(0xFF3A55C8)
    val indigoLight = Color(0xFF8FB2FF)
    val sky = Color(0xFF1E8FD6)
    val skyLight = Color(0xFF5CC8FF)
    val ok = Color(0xFF16A34A)
    val warn = Color(0xFFD97706)
    val danger = Color(0xFFDC2626)
}

/** Tema elegido en Ajustes (se puede cambiar sin reiniciar). */
object UiState {
    var theme by mutableStateOf("claro")
    /** Panel lateral abierto (con nombres) o colapsado (solo íconos). */
    var sidebarOpen by mutableStateOf(true)
    val dark get() = theme == "oscuro"
}

private val dark = darkColorScheme(
    primary = Palette.orangeLight,
    onPrimary = Color(0xFF2A1406),
    primaryContainer = Color(0xFF4A2A14),
    onPrimaryContainer = Color(0xFFFFE3CF),
    secondary = Palette.blueLight,
    onSecondary = Color(0xFF0D1220),
    background = Color(0xFF0E131F),
    onBackground = Color(0xFFE9EEF8),
    surface = Color(0xFF171E2E),
    onSurface = Color(0xFFE9EEF8),
    surfaceVariant = Color(0xFF1E2638),
    onSurfaceVariant = Color(0xFF97A4BF),
    surfaceContainer = Color(0xFF171E2E),
    surfaceContainerHigh = Color(0xFF1E2638),
    surfaceContainerHighest = Color(0xFF263048),
    outline = Color(0xFF2A3450),
    outlineVariant = Color(0xFF2A3450),
    error = Color(0xFFF87171),
    secondaryContainer = Color(0xFF20384F),
    onSecondaryContainer = Color(0xFFE3F4FF),
)

private val light = lightColorScheme(
    primary = Palette.orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE8D6),
    onPrimaryContainer = Color(0xFF5A2A00),
    secondary = Palette.blue,
    onSecondary = Color.White,
    background = Color(0xFFEEF2F6),
    onBackground = Color(0xFF1B2433),
    surface = Color.White,
    onSurface = Color(0xFF1B2433),
    surfaceVariant = Color(0xFFF3F6FA),
    onSurfaceVariant = Color(0xFF66738B),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF3F6FA),
    surfaceContainerHighest = Color(0xFFE6ECF3),
    outline = Color(0xFFDCE3EC),
    outlineVariant = Color(0xFFDCE3EC),
    error = Palette.danger,
    secondaryContainer = Color(0xFFDDF2FD),
    onSecondaryContainer = Color(0xFF07344D),
)

/** Nunito (redondeada, licencia OFL): más cercana a las tiendas de Wii U y 3DS que la letra del sistema. */
@OptIn(ExperimentalTextApi::class)
private val nunito = FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
    Font(R.font.nunito, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})

private val typography = Typography().let { t ->
    fun TextStyle.n() = copy(fontFamily = nunito)
    Typography(
        displayLarge = t.displayLarge.n(), displayMedium = t.displayMedium.n(), displaySmall = t.displaySmall.n(),
        headlineLarge = t.headlineLarge.n().copy(fontWeight = FontWeight.ExtraBold),
        headlineMedium = t.headlineMedium.n().copy(fontWeight = FontWeight.ExtraBold),
        headlineSmall = t.headlineSmall.n().copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.n().copy(fontWeight = FontWeight.Bold), titleMedium = t.titleMedium.n(), titleSmall = t.titleSmall.n(),
        bodyLarge = t.bodyLarge.n(), bodyMedium = t.bodyMedium.n(), bodySmall = t.bodySmall.n(),
        labelLarge = t.labelLarge.n().copy(fontWeight = FontWeight.Bold), labelMedium = t.labelMedium.n(), labelSmall = t.labelSmall.n(),
    )
}

/**
 * Sonido en todos los botones de Material sin tocar cada uno: cada toque con la onda de Material (botones, chips,
 * casillas, interruptores, menús) pasa por aquí y suena el "tic". Los que tienen sonido propio (abrir un juego,
 * volver) usan su propia indicación y no suenan dos veces.
 */
private class SoundIndication(private val base: androidx.compose.foundation.IndicationNodeFactory) :
    androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode {
        val node = base.create(interactionSource)
        return object : androidx.compose.ui.node.DelegatingNode() {
            init { delegate(node) }
            override fun onAttach() {
                coroutineScope.launch {
                    interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.PressInteraction.Release) Sound.play(Sound.Fx.MOVE) }
                }
            }
        }
    }
    override fun equals(other: Any?) = other is SoundIndication && other.base == base
    override fun hashCode() = base.hashCode()
}

@Composable
fun RomSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (UiState.dark) dark else light,
        typography = typography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
        ),
    ) {
        val ripple = androidx.compose.material3.ripple()
        val withSound = remember(ripple) { SoundIndication(ripple) }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides withSound,
            content = content)
    }
}

/**
 * Fondo con burbujas que flotan despacio (como el fondo animado de la eShop de Wii U). Se dibuja en un Canvas
 * detrás de todo; con pocas figuras y sin recomponer nada más.
 */
@Composable
fun BubblesBackground(modifier: Modifier = Modifier) {
    val dark = UiState.dark
    val t by rememberInfiniteTransition(label = "fondo").animateFloat(0f, 1f,
        infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t")
    val bubbles = remember { List(18) { i -> Triple((i * 0.137f + 0.05f) % 1f, (i * 0.291f + 0.1f) % 1f, 40f + (i * 37 % 120)) } }
    val top = if (dark) Color(0xFF111827) else Color(0xFFF7F9FC)
    val bottom = if (dark) Color(0xFF0B101A) else Color(0xFFE6EDF5)
    val dot = if (dark) Color.White.copy(alpha = 0.025f) else Color.White.copy(alpha = 0.55f)
    val ring = if (dark) Palette.blueLight.copy(alpha = 0.04f) else Palette.blue.copy(alpha = 0.05f)
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        bubbles.forEachIndexed { i, (x0, y0, r) ->
            val speed = 0.5f + (i % 5) * 0.15f
            val y = ((y0 - t * speed) % 1f + 1f) % 1f
            val x = x0 + 0.02f * kotlin.math.sin((t * 6.283f * 3) + i)
            val c = Offset(x * size.width, y * size.height * 1.2f - size.height * 0.1f)
            drawCircle(dot, r.dp.toPx(), c)
            drawCircle(ring, r.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}

val accentBrush get() = Brush.horizontalGradient(listOf(Palette.orange, Palette.blue))

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
