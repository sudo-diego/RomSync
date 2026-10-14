package io.github.sudodiego.romsync

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.luminance
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

/** Aspecto elegido en Ajustes (cambia en vivo, sin reiniciar). */
object UiState {
    /** "claro", "oscuro" o "sistema". */
    var theme by mutableStateOf("claro")
    /** Color principal (ARGB). */
    var accent by mutableStateOf(0xFFFF7A1AL)
    /** Fondo animado: "burbujas", "ondas" o "ninguno". */
    var background by mutableStateOf("burbujas")
    /** Panel lateral abierto (con nombres) o colapsado (solo íconos). */
    var sidebarOpen by mutableStateOf(true)
}

/** Colores para elegir en Ajustes (además de escribir un código). */
val accentPresets = listOf(
    "Naranja 3DS" to 0xFFFF7A1AL, "Azul Wii U" to 0xFF1AA3E8L, "Rojo Switch" to 0xFFE60012L, "Verde" to 0xFF22A355L,
    "Morado" to 0xFF7C4DFFL, "Rosa" to 0xFFEC4899L, "Turquesa" to 0xFF0FB5AEL, "Ámbar" to 0xFFF2A900L,
)

@Composable
fun isDark(): Boolean = when (UiState.theme) {
    "oscuro" -> true
    "sistema" -> androidx.compose.foundation.isSystemInDarkTheme()
    else -> false
}

private fun mix(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/** Esquema de colores a partir del color elegido (texto blanco o negro sobre él, según cuán claro sea). */
private fun schemeFor(dark: Boolean, accent: Color) = if (dark) {
    val p = mix(accent, Color.White, 0.30f)
    darkScheme.copy(
        primary = p, onPrimary = if (p.luminance() > 0.5f) Color(0xFF14110F) else Color.White,
        primaryContainer = mix(accent, Color(0xFF0E131F), 0.65f), onPrimaryContainer = mix(accent, Color.White, 0.85f),
        secondaryContainer = mix(accent, Color(0xFF0E131F), 0.70f), onSecondaryContainer = mix(accent, Color.White, 0.88f),
    )
} else lightScheme.copy(
    primary = accent, onPrimary = if (accent.luminance() > 0.6f) Color(0xFF1B2433) else Color.White,
    primaryContainer = mix(accent, Color.White, 0.85f), onPrimaryContainer = mix(accent, Color.Black, 0.55f),
    secondaryContainer = mix(accent, Color.White, 0.82f), onSecondaryContainer = mix(accent, Color.Black, 0.6f),
)

private val darkScheme = darkColorScheme(
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

private val lightScheme = lightColorScheme(
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
        colorScheme = schemeFor(isDark(), Color(UiState.accent)),
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

/** El fondo animado elegido en Ajustes. */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val dark = isDark()
    val accent = Color(UiState.accent)
    // Tiempo continuo en segundos (no un ciclo que vuelve a 0): así nada "salta" al repetirse.
    val time = remember { mutableFloatStateOf(0f) }
    if (UiState.background != "ninguno") LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
    }
    val top = if (dark) Color(0xFF111827) else Color(0xFFF7F9FC)
    val bottom = if (dark) Color(0xFF0B101A) else mix(Color(0xFFE6EDF5), accent, 0.05f)
    when (UiState.background) {
        "ondas" -> WavesCanvas(modifier, time, dark, accent, top, bottom)
        "ninguno" -> Canvas(modifier.fillMaxSize()) { drawRect(Brush.verticalGradient(listOf(top, bottom))) }
        else -> BubblesCanvas(modifier, time, dark, accent, top, bottom)
    }
}

private val bubbles = List(18) { i -> Triple((i * 0.137f + 0.05f) % 1f, (i * 0.291f + 0.1f) % 1f, 40f + (i * 37 % 120)) }

/** Burbujas que suben despacio, como el fondo de la eShop de Wii U. */
@Composable
private fun BubblesCanvas(modifier: Modifier, time: androidx.compose.runtime.MutableFloatState, dark: Boolean, accent: Color, top: Color, bottom: Color) {
    val dot = if (dark) Color.White.copy(alpha = 0.025f) else Color.White.copy(alpha = 0.55f)
    val ring = accent.copy(alpha = if (dark) 0.05f else 0.06f)
    Canvas(modifier.fillMaxSize()) {
        val t = time.floatValue // se lee solo al dibujar: no recompone la pantalla en cada cuadro
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        bubbles.forEachIndexed { i, (x0, y0, r) ->
            val period = 70f - (i % 5) * 9f // segundos en cruzar la pantalla
            val y = ((y0 - t / period) % 1f + 1f) % 1f
            val x = x0 + 0.02f * kotlin.math.sin(t * 0.3f + i)
            val c = Offset(x * size.width, y * size.height * 1.3f - size.height * 0.15f)
            drawCircle(dot, r.dp.toPx(), c)
            drawCircle(ring, r.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}

/** Ondas de luz que cruzan la pantalla, como el menú XMB de PlayStation. */
@Composable
private fun WavesCanvas(modifier: Modifier, time: androidx.compose.runtime.MutableFloatState, dark: Boolean, accent: Color, top: Color, bottom: Color) {
    val tint = if (dark) mix(accent, Color.Black, 0.75f) else mix(accent, Color.White, 0.82f)
    Canvas(modifier.fillMaxSize()) {
        val t = time.floatValue
        drawRect(Brush.verticalGradient(listOf(top, tint, bottom)))
        val w = size.width; val h = size.height
        for (k in 0 until 5) {
            val path = androidx.compose.ui.graphics.Path()
            val base = h * (0.52f + k * 0.035f)
            val amp = h * (0.06f + k * 0.015f)
            val speed = 0.12f + k * 0.035f
            val freq = 1.6f + k * 0.35f
            var x = 0f
            path.moveTo(0f, base)
            while (x <= w) {
                val u = x / w
                path.lineTo(x, base + amp * kotlin.math.sin(u * freq * 6.283f + t * speed + k * 1.3f) *
                    (0.6f + 0.4f * kotlin.math.sin(u * 3.1f + t * 0.05f)))
                x += 12f
            }
            // La onda es una cinta: se vuelve por debajo con otra fase.
            x = w
            while (x >= 0f) {
                val u = x / w
                path.lineTo(x, base + h * 0.02f + amp * 0.8f * kotlin.math.sin(u * freq * 6.283f + t * speed + k * 1.3f + 0.5f))
                x -= 12f
            }
            path.close()
            drawPath(path, Color.White.copy(alpha = if (dark) 0.04f else 0.35f))
            drawPath(path, accent.copy(alpha = if (dark) 0.08f else 0.10f))
        }
    }
}

val accentBrush get() = Brush.horizontalGradient(listOf(Color(UiState.accent), Palette.blue))

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
