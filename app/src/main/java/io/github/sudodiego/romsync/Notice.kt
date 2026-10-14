package io.github.sudodiego.romsync

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
    data class Item(val gameId: String, val title: String, val text: String, val at: Long = System.nanoTime())

    var current by mutableStateOf<Item?>(null)
        private set

    fun show(gameId: String, title: String, text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            current = Item(gameId, title, text)
            Sound.play(Sound.Fx.JINGLE)
        }
    }

    fun dismiss() { current = null }
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
                        if (game != null && kind != null)
                            ArtImage(game, kind, Modifier.fillMaxSize(), if (kind == "logo") ContentScale.Fit else ContentScale.Crop)
                        else Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                    }
                    Column(Modifier.padding(end = 8.dp)) {
                        Text(n.text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge)
                        Text(n.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
