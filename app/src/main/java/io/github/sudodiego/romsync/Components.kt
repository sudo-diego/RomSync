package io.github.sudodiego.romsync

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Carátula 2:3: un degradado con el color de la plataforma y el título; encima, la imagen del PC si hay. */
@Composable
fun Cover(game: Game, modifier: Modifier = Modifier, showTitle: Boolean = true, overlay: @Composable () -> Unit = {}) {
    val sys = Repo.system(game.system)
    Box(
        modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(sys.color), Color(0xFF0D1220))))
    ) {
        if (showTitle) Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(sys.short.uppercase(), color = Color.White.copy(alpha = .8f), fontSize = 10.sp, letterSpacing = 1.sp)
            Text(game.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        ArtImage(game, "grid", Modifier.fillMaxSize())
        overlay()
    }
}

/**
 * El juego como ícono cuadrado, como en el menú de Wii U: el ícono de SteamGridDB; si no tiene, la carátula recortada;
 * y si tampoco, el nombre sobre el color de la plataforma.
 */
@Composable
fun IconCover(game: Game, modifier: Modifier = Modifier, overlay: @Composable () -> Unit = {}) {
    val sys = Repo.system(game.system)
    val icon = artFile(game, "icon")
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(22.dp))
            .background(if (icon != null) Color.White else Color(sys.color))
    ) {
        when {
            icon != null -> ArtImage(game, "icon", Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(16.dp)), ContentScale.Fit)
            else -> {
                Text(game.title, Modifier.align(Alignment.Center).padding(10.dp), color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                ArtImage(game, "grid", Modifier.fillMaxSize(), ContentScale.Crop, Alignment.TopCenter)
            }
        }
        overlay()
    }
}

/** La imagen de un juego (la busca en SteamGridDB si no está); se recarga si se cambia desde la web. */
@Composable
fun artFile(game: Game, kind: String): java.io.File? {
    val version = Art.versions[game.id] ?: 0
    val state = androidx.compose.runtime.produceState(Art.cached(game.id, kind), game.id, kind, version) {
        value = Art.cached(game.id, kind) ?: Art.get(game, kind)
    }
    return state.value
}

@Composable
fun ArtImage(game: Game, kind: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop,
             alignment: Alignment = Alignment.Center) {
    val img = artFile(game, kind) ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val req = coil.request.ImageRequest.Builder(ctx).data(img)
        .memoryCacheKey("${img.path}:${img.lastModified()}:${Art.versions[game.id] ?: 0}").crossfade(true).build()
    AsyncImage(model = req, contentDescription = null, contentScale = contentScale, alignment = alignment, modifier = modifier)
}

/** Tocar con rebote: se hunde un poco al presionar y vuelve (sin la onda gris de Material). */
fun Modifier.pressable(onClick: () -> Unit): Modifier = composed {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.96f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f), label = "toque")
    graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interactionSource = press, indication = null, onClick = onClick)
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(99.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp),
        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

@Composable
fun SpacedRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)

/**
 * Para el control (Xbox, G9): cuando el elemento tiene el foco se agranda un poco, lleva un borde del color principal
 * y suena el "tic". Va ANTES del clickable en la cadena de modificadores (así ve el foco del clickable).
 */
/**
 * Para campos de texto con control: la cruceta arriba/abajo sale del campo hacia lo de al lado, y B (o Atrás) cierra
 * el teclado y suelta el campo. Sin esto el foco quedaba atrapado adentro.
 */
fun Modifier.gamepadText(): Modifier = composed {
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    this.onPreviewKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.nativeKeyEvent.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { kb?.hide(); fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> { kb?.hide(); fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Up) }
            android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_ESCAPE -> {
                kb?.hide(); fm.clearFocus(); true
            }
            else -> false
        }
    }
}

/** Con mando, este control no entra al recorrido de la cruceta (se usa con un botón del mando). */
fun Modifier.notForDpad(): Modifier = this.focusProperties { canFocus = !Nav.keys } // con el dedo (Nav.keys = false) se puede tocar siempre

/**
 * Sombra dibujada como parte del contenido (no la del sistema). Las sombras de Android se dibujan aparte y no se
 * desvanecen con la pantalla: al entrar o salir de una pantalla con carátulas "saltaban". Esta se desvanece con todo.
 */
fun Modifier.softShadow(radius: androidx.compose.ui.unit.Dp, elevation: androidx.compose.ui.unit.Dp = 8.dp, strength: Float = 0.28f): Modifier =
    this.drawBehind {
        if (elevation.value <= 0f) return@drawBehind
        drawIntoCanvas { c ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(elevation.toPx() * 1.4f, 0f, elevation.toPx() * 0.45f,
                    android.graphics.Color.argb((255 * strength).toInt(), 0, 0, 0))
            }
            val r = radius.toPx()
            c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        }
    }

/** Un botón del mando dibujado como en consola: círculo con la letra (o una píldora para L, R, ZL, ZR, −, +). */
@Composable
fun PadGlyph(logical: String) {
    // Los nombres internos (los de Nintendo) se muestran como los del mando que se está usando.
    val ps = Nav.padStyle == "ps"
    val label = when (logical) {
        "A" -> if (ps) "✕" else "A"; "B" -> if (ps) "○" else "B"; "X" -> if (ps) "□" else "X"; "Y" -> if (ps) "△" else "Y"
        "L" -> if (ps) "L1" else "LB"; "R" -> if (ps) "R1" else "RB"; "ZL" -> if (ps) "L2" else "LT"; "ZR" -> if (ps) "R2" else "RT"
        "−" -> if (ps) "Create" else "View"; "+" -> if (ps) "Options" else "Menu"
        else -> logical
    }
    val tint = if (!ps) Color.White else when (logical) {
        "A" -> Color(0xFF8FB4FF); "B" -> Color(0xFFFF7B7B); "X" -> Color(0xFFF5A3D6); "Y" -> Color(0xFF6FE3B4); else -> Color.White
    }
    val round = label.length == 1
    Box(Modifier.then(if (round) Modifier.size(20.dp) else Modifier.height(20.dp)).clip(RoundedCornerShape(99.dp))
        .background(Color(0xFF2B2F36)).padding(horizontal = if (round) 0.dp else 6.dp), contentAlignment = Alignment.Center) {
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Lo que muestra la barra de botones de la pantalla actual (la pone cada pantalla con [PadHints]). */
object PadBarState {
    val default = listOf("A" to "Elegir", "B" to "Atrás", "L R" to "Sección")
    var hints by androidx.compose.runtime.mutableStateOf(default)
}

/** Cada pantalla dice qué hacen los botones ahí; al salir vuelve lo de siempre. */
@Composable
fun PadHints(hints: List<Pair<String, String>>) {
    androidx.compose.runtime.SideEffect { PadBarState.hints = hints }
}

/** La barra de botones abajo a la derecha, como en Switch o Steam Deck. Solo con mando conectado; si no cabe, baja de línea. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun PadBar(modifier: Modifier = Modifier) {
    if (!Nav.pad) return
    androidx.compose.foundation.layout.FlowRow(
        modifier.widthIn(max = 900.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PadBarState.hints.forEach { (btn, what) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                btn.split(' ').forEach { PadGlyph(it) }
                Text(what, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

fun Modifier.focusHighlight(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(18.dp), grow: Float = 1.05f): Modifier = composed {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) grow else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f), label = "foco")
    val color = MaterialTheme.colorScheme.primary
    this.onFocusChanged {
        if (it.isFocused && !focused) Sound.play(Sound.Fx.MOVE)
        focused = it.isFocused
    }.graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (focused) Modifier.border(4.dp, color, shape) else Modifier)
}
