package io.github.sudodiego.romsync

import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Cómo se ve una descarga (o una copia al servidor) sobre la carátula, elegible en Ajustes → Aspecto:
 * - "clasico": la carátula oscurecida con un anillo y el porcentaje.
 * - "3ds": el ícono centrado dentro de un cubo que se llena de bloques; arriba pasan cubitos colgados de una línea
 *   (como en la eShop de 3DS). Al terminar queda envuelto en un regalo hasta que se abre la ficha.
 * - "switch": la carátula un poco apagada con una barra abajo, como el menú de Switch.
 */
object DownloadArt {
    val styles = linkedMapOf("clasico" to "Clásica", "3ds" to "3DS", "switch" to "Switch")
    var style by mutableStateOf("clasico")

    /** Juegos de estilo 3DS recién descargados que siguen "envueltos" (se abren al entrar a su ficha). */
    var gifts by mutableStateOf<Set<String>>(emptySet())
        private set

    fun init() { style = Prefs.dlStyle; gifts = Prefs.gifts }
    fun wrap(id: String) { if (style == "3ds") { gifts = gifts + id; Prefs.gifts = gifts } }
    fun unwrap(id: String) { if (id in gifts) { gifts = gifts - id; Prefs.gifts = gifts } }

    /** Avance actual de un juego (descarga o subida), o null si no se está moviendo nada. */
    fun progressOf(g: Game): Pair<Float, Boolean>? {
        PcEdit.uploading[g.id]?.let { up -> return up.toFloat() / g.size.coerceAtLeast(1) to true }
        val d = DownloadService.items.firstOrNull { it.id == g.id && it.status != DlStatus.DONE && it.status != DlStatus.FAILED } ?: return null
        return d.done.toFloat() / d.total.coerceAtLeast(1) to false
    }
}

/** La capa que va encima de la carátula mientras se mueve el juego (o el regalo, si recién terminó en estilo 3DS). */
@Composable
fun DownloadOverlay(g: Game, modifier: Modifier = Modifier) {
    val p = DownloadArt.progressOf(g)
    val gift = g.id in DownloadArt.gifts
    if (p == null && !gift) return
    val shown by animateFloatAsState(p?.first ?: 1f,
        androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "avance")
    val upload = p?.second == true
    Box(modifier.fillMaxSize()) {
        when {
            p == null -> GiftBox(Modifier.fillMaxSize())
            DownloadArt.style == "3ds" -> ThreeDsFill(g, shown, upload, Modifier.fillMaxSize())
            DownloadArt.style == "switch" -> SwitchBar(shown, upload, Modifier.fillMaxSize())
            else -> ClassicRing(shown, upload, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ClassicRing(p: Float, upload: Boolean, modifier: Modifier) {
    Box(modifier.background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { p }, Modifier.size(64.dp), color = Color.White, strokeWidth = 5.dp,
            trackColor = Color.White.copy(alpha = 0.25f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (upload) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text("${(p * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SwitchBar(p: Float, upload: Boolean, modifier: Modifier) {
    Box(modifier.background(Color(0x55000000))) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)) {
            Text((if (upload) "Subiendo " else "") + "${(p * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(99.dp)).background(Color(UiState.accent)))
            }
        }
    }
}

/**
 * Estilo 3DS: fondo claro, un riel arriba con cubitos colgando que avanzan hacia el centro y caen dentro de un cubo
 * grande en perspectiva; en la cara de adelante del cubo está el ícono del juego, que se va revelando de abajo hacia
 * arriba según el avance. Al subir al servidor, los cubitos salen del cubo y se van por el riel.
 */
@Composable
private fun ThreeDsFill(g: Game, p: Float, upload: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "riel")
    val move by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "mover")
    val accent = Color(UiState.accent)
    val light = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.55f)
    val dark = androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.25f)
    BoxWithConstraints(modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFF2F9FF), Color(0xFFD6ECFF))))) {
        val side = minOf(maxWidth, maxHeight) * 0.5f
        val sidePx = with(androidx.compose.ui.platform.LocalDensity.current) { side.toPx() }
        // Riel y cubitos (dibujados sobre toda la tarjeta).
        Canvas(Modifier.fillMaxSize()) {
            val railY = size.height * 0.16f
            drawLine(Color(0xFF9AB3C9), Offset(0f, railY), Offset(size.width, railY), 2.dp.toPx())
            val cube = sidePx * 0.22f
            val cx = size.width / 2
            val boxTop = size.height / 2 - sidePx / 2
            fun isoCube(x: Float, y: Float, s: Float) {
                val h = s * 0.3f
                val top = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, y + h); lineTo(x + s / 2, y); lineTo(x + s, y + h); lineTo(x + s / 2, y + 2 * h); close()
                }
                val left = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, y + h); lineTo(x + s / 2, y + 2 * h); lineTo(x + s / 2, y + 2 * h + s * 0.55f); lineTo(x, y + h + s * 0.55f); close()
                }
                val right = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x + s, y + h); lineTo(x + s / 2, y + 2 * h); lineTo(x + s / 2, y + 2 * h + s * 0.55f); lineTo(x + s, y + h + s * 0.55f); close()
                }
                drawPath(top, light); drawPath(left, accent); drawPath(right, dark)
            }
            // 3 cubitos en el riel: llegan al centro y bajan al cubo (o al revés si se sube).
            for (i in 0 until 3) {
                var f = (move + i / 3f) % 1f
                if (upload) f = 1f - f
                val x: Float; val y: Float
                if (f < 0.7f) {
                    // Por el riel, desde el borde hasta el centro, colgando de un gancho.
                    val k = f / 0.7f
                    x = -cube + k * (cx - cube / 2 + cube)
                    y = railY + 6.dp.toPx()
                    drawLine(Color(0xFF9AB3C9), Offset(x + cube / 2, railY), Offset(x + cube / 2, y), 1.5.dp.toPx())
                } else {
                    // Se suelta y cae dentro del cubo grande.
                    val k = (f - 0.7f) / 0.3f
                    x = cx - cube / 2
                    y = railY + 6.dp.toPx() + k * k * (boxTop - railY)
                }
                if (y < boxTop + sidePx * 0.15f) isoCube(x, y, cube)
            }
        }
        // El cubo grande: cara de adelante con el ícono que se revela, techo y lado en perspectiva.
        Box(Modifier.size(side).align(Alignment.Center)) {
            Canvas(Modifier.fillMaxSize()) {
                val d = size.width * 0.16f
                // techo
                drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, d); lineTo(d, 0f); lineTo(size.width, 0f); lineTo(size.width - d, d); close()
                }, light.copy(alpha = 0.9f))
                // lado derecho
                drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width - d, d); lineTo(size.width, 0f); lineTo(size.width, size.height - d); lineTo(size.width - d, size.height); close()
                }, dark.copy(alpha = 0.9f))
            }
            val face = side * 0.84f
            Box(Modifier.size(face).align(Alignment.BottomStart).clip(RoundedCornerShape(6.dp)).background(Color.White)) {
                val art = listOf("icon", "grid").firstOrNull { Art.cached(g.id, it) != null }
                // Lo que falta, gris claro; lo descargado, el ícono a color (se revela de abajo hacia arriba).
                if (art != null) ArtImage(g, art, Modifier.fillMaxSize().graphicsLayer { alpha = 0.18f }, ContentScale.Crop)
                Box(Modifier.fillMaxSize().drawWithClipBottom(p)) {
                    if (art != null) ArtImage(g, art, Modifier.fillMaxSize(), ContentScale.Crop)
                    else Box(Modifier.fillMaxSize().background(Color(Repo.system(g.system).color)))
                }
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(accent, style = Stroke(3.dp.toPx()))
                }
            }
        }
        Text("${(p * 100).toInt()}%", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), color = Color(0xFF2B3A4A),
            fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Muestra solo la parte de abajo del contenido (fracción [p] de la altura). */
private fun Modifier.drawWithClipBottom(p: Float) = this.then(Modifier.drawWithContent {
    clipRect(top = size.height * (1f - p.coerceIn(0f, 1f))) { this@drawWithContent.drawContent() }
})

/** El regalo de la 3DS: caja del color destacado con cintas y lazo; se abre (escala y se desvanece) al tocar. */
@Composable
fun GiftBox(modifier: Modifier, opening: Boolean = false) {
    val open = remember { Animatable(0f) }
    LaunchedEffect(opening) { if (opening) open.animateTo(1f, tween(600)) }
    val wobble = rememberInfiniteTransition(label = "regalo")
    val w by wobble.animateFloat(-3f, 3f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "mover")
    val accent = Color(UiState.accent)
    Canvas(modifier.background(Color(0x660D1220)).graphicsLayer {
        rotationZ = if (opening) 0f else w; scaleX = 1f + open.value * 0.6f; scaleY = 1f + open.value * 0.6f; alpha = 1f - open.value
    }) {
        val s = minOf(size.width, size.height) * 0.6f
        val o = Offset((size.width - s) / 2, (size.height - s) / 2 + s * 0.08f)
        drawRoundRect(accent, o, Size(s, s), CornerRadius(s * 0.12f))
        drawRoundRect(Color.White.copy(alpha = 0.18f), o, Size(s, s * 0.22f), CornerRadius(s * 0.12f))
        val rib = s * 0.16f
        drawRect(Color.White, Offset(o.x + (s - rib) / 2, o.y), Size(rib, s))
        drawRect(Color.White, Offset(o.x, o.y + (s - rib) / 2), Size(s, rib))
        // Lazo: dos óvalos arriba al centro.
        val cx = o.x + s / 2
        drawOval(Color.White, Offset(cx - s * 0.34f, o.y - s * 0.2f), Size(s * 0.32f, s * 0.22f))
        drawOval(Color.White, Offset(cx + s * 0.02f, o.y - s * 0.2f), Size(s * 0.32f, s * 0.22f))
        drawCircle(accent, s * 0.06f, Offset(cx, o.y - s * 0.09f))
    }
}
