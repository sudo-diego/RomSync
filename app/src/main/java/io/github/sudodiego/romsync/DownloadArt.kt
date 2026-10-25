package io.github.sudodiego.romsync

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
    val shown by animateFloatAsState(p?.first ?: 1f, tween(400), label = "avance")
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
 * Estilo 3DS: el ícono del juego en un cubo que se llena de bloques de abajo hacia arriba según el avance, y arriba
 * una línea con cubitos colgando que van pasando (al subir, los cubitos van en sentido contrario).
 */
@Composable
private fun ThreeDsFill(g: Game, p: Float, upload: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "linea")
    val move by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "mover")
    val accent = Color(UiState.accent)
    BoxWithConstraints(modifier.background(Color(0xB30D1220))) {
        val side = minOf(maxWidth, maxHeight) * 0.62f
        // Línea de producción arriba: cubitos colgados que avanzan.
        Canvas(Modifier.fillMaxWidth().height(maxHeight * 0.25f).align(Alignment.TopCenter)) {
            val y = size.height * 0.35f
            drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
            val n = 5
            val cube = size.height * 0.32f
            for (i in 0 until n) {
                var f = ((i.toFloat() / n) + (if (upload) -move else move)) % 1f
                if (f < 0) f += 1f
                val x = f * (size.width + cube) - cube
                drawLine(Color.White.copy(alpha = 0.7f), Offset(x + cube / 2, y), Offset(x + cube / 2, y + cube * 0.5f), 1.5.dp.toPx())
                drawRoundRect(accent, Offset(x, y + cube * 0.5f), Size(cube, cube), CornerRadius(cube * 0.18f))
                drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(x, y + cube * 0.5f), Size(cube, cube * 0.3f), CornerRadius(cube * 0.18f))
            }
        }
        // El cubo del ícono, centrado, que se va llenando.
        Box(Modifier.size(side).align(Alignment.Center).clip(RoundedCornerShape(side * 0.16f))) {
            val art = listOf("icon", "grid").firstOrNull { Art.cached(g.id, it) != null }
            if (art != null) ArtImage(g, art, Modifier.fillMaxSize(), ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Color(Repo.system(g.system).color)))
            Canvas(Modifier.fillMaxSize()) {
                val cells = 6
                val c = size.width / cells
                val filled = (p * cells * cells).toInt()
                // Se llena de abajo hacia arriba, fila por fila; lo que falta queda tapado.
                for (row in 0 until cells) for (col in 0 until cells) {
                    val idx = row * cells + col
                    if (idx >= filled) drawRect(Color(0xE60D1220), Offset(col * c, size.height - (row + 1) * c), Size(c + 0.5f, c + 0.5f))
                }
                drawRoundRect(Color.White, Offset.Zero, size, CornerRadius(size.width * 0.16f), style = Stroke(3.dp.toPx()))
            }
        }
        Text("${(p * 100).toInt()}%", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), color = Color.White,
            fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

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
