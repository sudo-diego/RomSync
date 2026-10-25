package io.github.sudodiego.romsync

import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.border
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
    /** Regalos que ya se vieron envolverse (la animación de envolver es una vez, no cada vez que aparece la carátula). */
    val wrappedSeen = HashSet<String>()

    /** Avance actual de un juego (descarga o subida), o null si no se está moviendo nada. */
    fun progressOf(g: Game): Pair<Float, Boolean>? {
        PcEdit.uploading[g.id]?.let { up -> return up.toFloat() / g.size.coerceAtLeast(1) to true }
        val d = DownloadService.items.firstOrNull { it.id == g.id && it.status != DlStatus.DONE && it.status != DlStatus.FAILED } ?: return null
        return d.done.toFloat() / d.total.coerceAtLeast(1) to false
    }
}

/** La capa que va encima de la carátula mientras se mueve el juego (o el regalo, si recién terminó en estilo 3DS). */
@Composable
fun DownloadOverlay(g: Game, modifier: Modifier = Modifier, sound: Boolean = false) {
    val p = DownloadArt.progressOf(g)
    val gift = g.id in DownloadArt.gifts
    if (p == null && !gift) return
    val shown by animateFloatAsState(p?.first ?: 1f,
        androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "avance")
    val upload = p?.second == true
    Box(modifier.fillMaxSize()) {
        when {
            p == null -> GiftBox(g, Modifier.fillMaxSize())
            DownloadArt.style == "3ds" -> ThreeDsFill(g, shown, upload, Modifier.fillMaxSize(), sound)
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

/** Tiempos de una tanda de 4 bloques (ms): cada uno sale con 220 ms de diferencia, viaja y cae; luego una pausa. */
private const val CYCLE = 2600L
private const val GAP = 220L
private const val TRAVEL = 900L
private const val FALL = 280L

/** Dibuja un cubito en perspectiva (techo claro, lado izquierdo y derecho). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.isoCube(x: Float, y: Float, s: Float, base: Color) {
    val light = androidx.compose.ui.graphics.lerp(base, Color.White, 0.45f)
    val dark = androidx.compose.ui.graphics.lerp(base, Color.Black, 0.25f)
    val h = s * 0.28f
    val body = s * 0.58f
    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(x, y + h); lineTo(x + s / 2, y); lineTo(x + s, y + h); lineTo(x + s / 2, y + 2 * h); close() }, light)
    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(x, y + h); lineTo(x + s / 2, y + 2 * h); lineTo(x + s / 2, y + 2 * h + body); lineTo(x, y + h + body); close() }, base)
    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(x + s, y + h); lineTo(x + s / 2, y + 2 * h); lineTo(x + s / 2, y + 2 * h + body); lineTo(x + s, y + h + body); close() }, dark)
}

/** El cubo grande en perspectiva (techo y lado derecho); la cara de adelante la pone quien llama. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.bigCube(accent: Color, depth: Float) {
    val light = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.55f)
    val dark = androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.25f)
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, depth); lineTo(depth, 0f); lineTo(size.width, 0f); lineTo(size.width - depth, depth); close()
    }, light)
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(size.width - depth, depth); lineTo(size.width, 0f); lineTo(size.width, size.height - depth); lineTo(size.width - depth, size.height); close()
    }, dark)
}

/**
 * Estilo 3DS: el ícono del juego en la cara de adelante de un cubo; por un riel arriba llegan tandas de 4 cubitos
 * colgando, con ritmo, que caen dentro del cubo (con un "tic" cada uno si [sound]); el ícono se revela de abajo hacia
 * arriba con el avance. Al subir al servidor, los cubitos salen del cubo y se van.
 */
@Composable
private fun ThreeDsFill(g: Game, p: Float, upload: Boolean, modifier: Modifier, sound: Boolean) {
    val accent = Color(UiState.accent)
    // Reloj de la tanda: milisegundos desde que empezó.
    var clock by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) androidx.compose.runtime.withFrameMillis { clock = (it - start) % CYCLE }
    }
    // Un "tic" cuando cae cada cubito (solo en la vista grande, para que no suenen varias carátulas a la vez).
    if (sound) LaunchedEffect(Unit) {
        while (true) {
            for (k in 0 until 4) {
                kotlinx.coroutines.delay(if (k == 0) TRAVEL + FALL else GAP)
                Sound.play(Sound.Fx.TOGGLE)
            }
            kotlinx.coroutines.delay(CYCLE - TRAVEL - FALL - 3 * GAP)
        }
    }
    BoxWithConstraints(modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFF2F9FF), Color(0xFFD6ECFF))))) {
        val side = minOf(maxWidth, maxHeight) * 0.5f
        val sidePx = with(androidx.compose.ui.platform.LocalDensity.current) { side.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val railY = size.height * 0.15f
            drawLine(Color(0xFF9AB3C9), Offset(0f, railY), Offset(size.width, railY), 2.dp.toPx())
            val cube = sidePx * 0.2f
            val cx = size.width / 2 - cube / 2
            val boxTop = size.height / 2 - sidePx / 2
            for (k in 0 until 4) {
                var t = clock - k * GAP
                if (t < 0) continue
                if (upload) t = TRAVEL + FALL - t
                if (t !in 0..(TRAVEL + FALL)) continue
                val hook = railY + 5.dp.toPx()
                if (t < TRAVEL) {
                    // Por el riel, colgando del gancho.
                    val f = t.toFloat() / TRAVEL
                    val ease = 1f - (1f - f) * (1f - f)
                    val x = -cube + ease * (cx + cube)
                    drawLine(Color(0xFF9AB3C9), Offset(x + cube / 2, railY), Offset(x + cube / 2, hook), 1.5.dp.toPx())
                    isoCube(x, hook, cube, accent)
                } else {
                    // Se suelta y cae dentro del cubo (acelerando).
                    val f = (t - TRAVEL).toFloat() / FALL
                    val y = hook + f * f * (boxTop + sidePx * 0.1f - hook)
                    if (y < boxTop + sidePx * 0.12f) isoCube(cx, y, cube, accent)
                }
            }
        }
        Box(Modifier.size(side).align(Alignment.Center)) {
            Canvas(Modifier.fillMaxSize()) { bigCube(accent, size.width * 0.16f) }
            val face = side * 0.84f
            val corner = RoundedCornerShape(face * 0.2f)
            // La cara de adelante con las mismas esquinas que el ícono (sin espacio blanco alrededor).
            Box(Modifier.size(face).align(Alignment.BottomStart).clip(corner).background(Color(0xFFE3EEF8))) {
                val art = listOf("icon", "grid").firstOrNull { Art.cached(g.id, it) != null }
                if (art != null) ArtImage(g, art, Modifier.fillMaxSize().graphicsLayer { alpha = 0.22f }, ContentScale.Crop)
                Box(Modifier.fillMaxSize().drawWithClipBottom(p)) {
                    if (art != null) ArtImage(g, art, Modifier.fillMaxSize(), ContentScale.Crop)
                    else Box(Modifier.fillMaxSize().background(Color(Repo.system(g.system).color)))
                }
            }
            Box(Modifier.size(face).align(Alignment.BottomStart).border(3.dp, accent, corner))
        }
        Text("${(p * 100).toInt()}%", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), color = Color(0xFF2B3A4A),
            fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Muestra solo la parte de abajo del contenido (fracción [p] de la altura). */
private fun Modifier.drawWithClipBottom(p: Float) = this.then(Modifier.drawWithContent {
    clipRect(top = size.height * (1f - p.coerceIn(0f, 1f))) { this@drawWithContent.drawContent() }
})

/**
 * El regalo de la 3DS: el mismo cubo del ícono, que se envuelve al terminar (el papel sube por las caras, se cruzan las
 * cintas y aparece el lazo con un rebote). En la ficha se abre: el lazo sale volando y el papel se va.
 */
@Composable
fun GiftBox(g: Game, modifier: Modifier, opening: Boolean = false) {
    val first = remember(g.id) { g.id !in DownloadArt.wrappedSeen }
    val wrap = remember(g.id) { Animatable(if (first) 0f else 1f) }
    val open = remember { Animatable(0f) }
    LaunchedEffect(g.id) { if (first) { DownloadArt.wrappedSeen += g.id; wrap.animateTo(1f, tween(1100)) } }
    LaunchedEffect(opening) { if (opening) open.animateTo(1f, tween(650)) }
    val accent = Color(UiState.accent)
    val paper = androidx.compose.ui.graphics.lerp(accent, Color(0xFFFF4D8D), 0.35f)
    BoxWithConstraints(modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFF2F9FF), Color(0xFFD6ECFF))))
        .graphicsLayer { alpha = 1f - open.value * 0.9f }) {
        val side = minOf(maxWidth, maxHeight) * 0.56f
        Box(Modifier.size(side).align(Alignment.Center).graphicsLayer {
            val o = open.value; scaleX = 1f + o * 0.25f; scaleY = 1f + o * 0.25f
        }) {
            Canvas(Modifier.fillMaxSize()) { bigCube(accent, size.width * 0.16f) }
            val face = side * 0.84f
            val corner = RoundedCornerShape(face * 0.2f)
            Box(Modifier.size(face).align(Alignment.BottomStart).clip(corner)) {
                val art = listOf("icon", "grid").firstOrNull { Art.cached(g.id, it) != null }
                if (art != null) ArtImage(g, art, Modifier.fillMaxSize(), ContentScale.Crop)
                else Box(Modifier.fillMaxSize().background(Color(Repo.system(g.system).color)))
            }
            // Papel, cintas y lazo encima del cubo.
            Canvas(Modifier.fillMaxSize()) {
                val w = wrap.value
                val o = open.value
                val d = size.width * 0.16f
                val fw = size.width - d
                val faceTop = d
                // El papel sube de abajo hacia arriba por la cara de adelante y por el lado.
                val paperK = (w / 0.6f).coerceIn(0f, 1f) * (1f - o)
                if (paperK > 0f) {
                    val top = size.height - (size.height - faceTop) * paperK
                    drawRoundRect(paper, Offset(0f, top), Size(fw, size.height - top), CornerRadius(fw * 0.2f))
                    drawPath(androidx.compose.ui.graphics.Path().apply {
                        moveTo(fw, maxOf(top, d)); lineTo(size.width, maxOf(top - d, 0f)); lineTo(size.width, size.height - d); lineTo(fw, size.height); close()
                    }, androidx.compose.ui.graphics.lerp(paper, Color.Black, 0.2f))
                    if (paperK >= 1f) drawPath(androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, d); lineTo(d, 0f); lineTo(size.width, 0f); lineTo(fw, d); close()
                    }, androidx.compose.ui.graphics.lerp(paper, Color.White, 0.3f))
                }
                // Cintas que se cruzan.
                val ribbonK = ((w - 0.55f) / 0.25f).coerceIn(0f, 1f) * (1f - o)
                if (ribbonK > 0f) {
                    val rib = fw * 0.14f
                    drawRect(Color.White, Offset(fw / 2 - rib / 2, size.height - (size.height - d) * ribbonK), Size(rib, (size.height - d) * ribbonK))
                    val my = d + (size.height - d) / 2 - rib / 2
                    drawRect(Color.White, Offset(0f, my), Size(fw * ribbonK, rib))
                }
                // El lazo, con un rebote al final.
                val bowK = ((w - 0.8f) / 0.2f).coerceIn(0f, 1f)
                if (bowK > 0f) {
                    val k = (if (bowK < 1f) bowK * 1.15f else 1f) * (1f - o)
                    val cx = fw / 2 + d / 2
                    val by = d * 0.6f - o * size.height * 0.4f
                    val bs = fw * 0.22f * k
                    drawOval(Color.White, Offset(cx - bs * 1.4f, by - bs * 0.7f), Size(bs * 1.3f, bs))
                    drawOval(Color.White, Offset(cx + bs * 0.1f, by - bs * 0.7f), Size(bs * 1.3f, bs))
                    drawCircle(paper, bs * 0.32f, Offset(cx, by - bs * 0.2f))
                }
            }
        }
    }
}
