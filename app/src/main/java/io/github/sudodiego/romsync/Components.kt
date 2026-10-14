package io.github.sudodiego.romsync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
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
