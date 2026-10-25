package io.github.sudodiego.romsync

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Aviso dentro de la app, arriba a la derecha (como los logros de una consola): "Descarga terminada" con la imagen del
 * juego (su ícono, o si no la carátula o el logo de SteamGridDB) y un jingle. Tocarlo abre el juego.
 */
object Notice {
    enum class Kind { DONE, DELETE, ERROR, INFO }
    data class Item(val gameId: String, val title: String, val text: String, val kind: Kind = Kind.DONE, val at: Long = System.nanoTime())

    var current by mutableStateOf<Item?>(null)
        private set

    fun show(gameId: String, title: String, text: String, kind: Kind = Kind.DONE) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            current = Item(gameId, title, text, kind)
            if (kind == Kind.DONE) Sound.play(Sound.Fx.JINGLE)
        }
    }

    fun dismiss() { current = null }

    /** Reemplaza a los Toast de Android: un aviso igual al de "descarga terminada", sin juego. */
    fun info(message: String) {
        val bad = Regex("^(no |error|falló|sin )|no se pudo|no se pudieron|necesita|cancelad", RegexOption.IGNORE_CASE).containsMatchIn(message)
        show("", message, if (bad) "Ojo" else "Listo", if (bad) Kind.ERROR else Kind.INFO)
    }
}

@Composable
fun NoticeOverlay() {
    val item = Notice.current
    var shown by androidx.compose.runtime.remember { mutableStateOf<Notice.Item?>(null) }
    if (item != null) shown = item
    LaunchedEffect(item) {
        if (item != null) { delay(5000); if (Notice.current == item) Notice.dismiss() }
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(18.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(item != null, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
            val n = shown ?: return@AnimatedVisibility
            val game = Repo.game(n.gameId)
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, shadowElevation = 12.dp,
                modifier = Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(20.dp)).clickable {
                    Notice.dismiss(); game?.let { Sound.play(Sound.Fx.OPEN); Nav.game = it }
                }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center) {
                        val kind = game?.let { g -> listOf("icon", "grid", "logo").firstOrNull { Art.cached(g.id, it) != null } }
                        val gone = androidx.compose.runtime.remember(n.at) { androidx.compose.animation.core.Animatable(0f) }
                        LaunchedEffect(n.at) {
                            if (n.kind == Notice.Kind.DELETE) { delay(250); gone.animateTo(1f, androidx.compose.animation.core.tween(700)) }
                        }
                        if (game != null && kind != null) ArtImage(game, kind, Modifier.fillMaxSize().graphicsLayer {
                            val k = gone.value
                            scaleX = 1f - 0.8f * k; scaleY = 1f - 0.8f * k; rotationZ = -25f * k; alpha = 1f - k
                            translationY = 30f * k
                        }, if (kind == "logo") ContentScale.Fit else ContentScale.Crop)
                        when (n.kind) {
                            Notice.Kind.DELETE -> Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(34.dp).graphicsLayer { val k = gone.value; scaleX = k; scaleY = k; alpha = k })
                            Notice.Kind.ERROR -> Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(34.dp))
                            Notice.Kind.INFO -> Icon(Icons.Rounded.CheckCircle, null, tint = Palette.ok, modifier = Modifier.size(34.dp))
                            Notice.Kind.DONE -> if (game == null || kind == null)
                                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                        }
                    }
                    Column(Modifier.padding(end = 8.dp)) {
                        Text(n.text, color = when (n.kind) { Notice.Kind.DONE -> MaterialTheme.colorScheme.primary; Notice.Kind.INFO -> Palette.ok
                            else -> MaterialTheme.colorScheme.error },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge)
                        Text(n.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
