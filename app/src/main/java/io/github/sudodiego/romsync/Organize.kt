package io.github.sudodiego.romsync

import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * "Ordenar biblioteca": junta en un solo lugar todo lo que desordena las ROMs (archivos que sobran, BIOS fuera de su
 * carpeta, el mismo juego varias veces, juegos que solo están en la tablet) y guía paso a paso. Reemplaza al filtro
 * "Repetidos" de la tienda: con una biblioteca ordenada no hace falta.
 */
object Organize {
    /**
     * Grupos del mismo juego en la misma plataforma (versiones, regiones, formatos). Los que el usuario marcó "me quedo
     * con todas" no cuentan: tener varias versiones en la colección también está bien.
     */
    /** Formatos de ROM conocidos: un archivo con otra extensión (como ".unh") suele ser una copia de otra app. */
    private val knownExt = setOf(".zip", ".7z", ".nes", ".fds", ".sfc", ".smc", ".gb", ".gbc", ".gba", ".nds", ".3ds", ".cia", ".n64",
        ".z64", ".v64", ".md", ".gen", ".sms", ".gg", ".pce", ".iso", ".chd", ".cue", ".rvz", ".wbfs", ".gcm", ".cso", ".pbp",
        ".nsp", ".xci", ".wua", ".vpk", ".m3u", ".gdi", ".ws", ".wsc", ".ngp", ".ngc", ".a26", ".lnx")

    /**
     * Cuál conviene dejar. Si el mismo archivo está en dos plataformas, el de formato conocido (el otro casi siempre
     * quedó en una carpeta equivocada); si no, la recomendación de siempre (región, formato).
     */
    fun recommend(vs: List<Game>): Pair<Game, String>? {
        if (vs.map { it.system }.distinct().size > 1) {
            val ok = vs.filter { Group.ext(it.primary) in knownExt }
            if (ok.size == 1) return ok.first() to "formato normal de ${Repo.system(ok.first().system).short}; el otro parece estar en la carpeta equivocada"
        }
        return Advice.pick(vs)?.let { (g, why) -> g to why.ifBlank { "la mejor de las que tienes" } }
    }

    /** La clave con que se recuerda "me quedo con todas" para un grupo. */
    fun groupKey(vs: List<Game>): String = if (vs.map { Repo.versionKey(it) }.distinct().size == 1) Repo.versionKey(vs.first())
        else "otra|" + Group.stem(vs.first().primary.substringAfterLast('/')).lowercase()

    fun duplicateGroups(): List<List<Game>> {
        val games = Repo.allGames().filter { it.kind != "shortcut" && !Repo.isArchive(it) }
        val same = games.groupBy { Repo.versionKey(it) }.filter { (k, v) -> v.size > 1 && k !in keptVersions }.values
        // El mismo archivo en dos plataformas (Circus Charlie en nes y en snesna): casi siempre uno está de más.
        val seen = same.flatten().map { it.id }.toSet()
        val cross = games.filter { it.id !in seen }.groupBy { "otra|" + Group.stem(it.primary.substringAfterLast('/')).lowercase() }
            .filter { (k, v) -> v.size > 1 && v.map { it.system }.distinct().size > 1 && k !in keptVersions }.values
        return (same + cross).sortedBy { it.first().title.lowercase() }
    }

    /** Estado para que la tarjeta y la lista se actualicen al marcar un grupo. */
    var keptVersions by androidx.compose.runtime.mutableStateOf(Prefs.keepAllVersions)
        private set

    fun keepAll(key: String) {
        keptVersions = keptVersions + key
        Prefs.keepAllVersions = keptVersions
    }

    /** Juegos que solo están en la tablet: si se pierde la tablet, se pierden. */
    fun tabletOnly(): List<Game> = if (!Prefs.configured) emptyList()
    else Repo.tabletOnly.filter { it.kind != "shortcut" && !Repo.isArchive(it) } +
        // Actualizaciones y DLC de Switch solo en la tablet (van con su juego del PC): se copian igual.
        Repo.tabletExtras.mapNotNull { (id, parts) ->
            Repo.game(id)?.let { g -> g.copy(id = "extras:$id", title = g.title + " · actualizaciones/DLC", files = parts.flatMap { it.files },
                size = parts.sumOf { it.size }) }
        }


    fun junkCount() = Repo.localJunk.size + Repo.library?.junk.orEmpty().size
    fun strayBiosCount() = Repo.localStrayBios.size + Repo.library?.strayBios.orEmpty().size
}

@Composable
fun OrganizeCard() {
    var open by remember { mutableStateOf(false) }
    val junk = Organize.junkCount()
    val bios = Organize.strayBiosCount()
    val dups = remember(Repo.library, Repo.tabletOnly, Organize.keptVersions) { Organize.duplicateGroups().size }
    val only = remember(Repo.tabletOnly) { Organize.tabletOnly().size }
    val parts = listOfNotNull(
        junk.takeIf { it > 0 }?.let { "$it archivo(s) que sobran" },
        bios.takeIf { it > 0 }?.let { "$it BIOS fuera de su carpeta" },
        dups.takeIf { it > 0 }?.let { "$it juego(s) repetidos" },
        only.takeIf { it > 0 }?.let { "$it juego(s) sin copia en el servidor" },
    )
    if (parts.isEmpty()) return
    Section("") {
        SpacedRow {
            Column(Modifier.weight(1f)) {
                Text("Ordenar tu biblioteca", fontWeight = FontWeight.Bold)
                Muted(parts.joinToString(" · "))
            }
            Button(onClick = { open = true }) { Text("Ordenar") }
        }
    }
    if (open) OrganizeDialog { open = false }
}

@Composable
private fun OrganizeDialog(onClose: () -> Unit) {
    var step by remember { mutableStateOf("") }
    when (step) {
        "junk" -> { CleanupDialog { step = "" }; return }
        "bios" -> { StrayBiosDialog(Repo.localStrayBios, Repo.library?.strayBios.orEmpty()) { step = "" }; return }
        "dups" -> { DuplicatesDialog { step = "" }; return }
        "only" -> { TabletOnlyDialog { step = "" }; return }
    }
    val junk = Organize.junkCount()
    val bios = Organize.strayBiosCount()
    val dups = Organize.duplicateGroups().size
    val only = Organize.tabletOnly().size
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ordenar tu biblioteca") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Muted("Nada se borra sin confirmar. Las partidas no se tocan.")
                Step(1, "Archivos que sobran", "Descargas a medias y copias viejas de partidas que dejan otras apps.", junk) { step = "junk" }
                Step(2, "BIOS fuera de su carpeta", "BIOS sueltas junto a los juegos: van a la carpeta bios, donde las buscan los emuladores.", bios) { step = "bios" }
                Step(3, "Juegos repetidos", "El mismo juego en varias regiones o formatos. Te digo con cuál quedarte.", dups) { step = "dups" }
                Step(4, "Juegos sin copia en el servidor", "Solo están en el dispositivo: cópialos al servidor para no perderlos.", only) { step = "only" }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Listo") } },
    )
}

@Composable
private fun Step(n: Int, title: String, desc: String, count: Int, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pill(if (count == 0) "✓" else "$n", if (count == 0) Palette.ok else Palette.warn)
        Column(Modifier.weight(1f)) {
            Text(if (count == 0) title else "$title ($count)", fontWeight = FontWeight.SemiBold)
            Muted(if (count == 0) "Todo en orden." else desc)
        }
        if (count > 0) TextButton(onClick = onOpen) { Text("Revisar") }
    }
}

/**
 * Juegos repetidos: una tarjeta por juego, con su carátula, la versión recomendada destacada y dos atajos ("Dejar solo
 * la recomendada", "Me quedo con todas"). Lo marcado se borra del servidor y del dispositivo; las partidas no.
 */
@Composable
fun DuplicatesDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val groups = remember(Repo.library, Repo.tabletOnly, Organize.keptVersions) { Organize.duplicateGroups() }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val chosen = groups.flatten().filter { it.id in selected }
    // ¿Alguna de las marcadas tiene partidas en el dispositivo? (si no, no se habla de partidas).
    var withSaves by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(confirm) {
        if (confirm) withSaves = chosen.any { g ->
            runCatching { GameSaves.links(ctx, g).any { l -> Saves.currentUnits(ctx, l.emu).any { it.key == l.key } } }.getOrDefault(false)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar ${chosen.size} versión(es)?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chosen.take(8).forEach { Text("• ${it.name} · ${formatSize(it.size)}") }
                    if (chosen.size > 8) Muted("y ${chosen.size - 8} más")
                    val where = listOfNotNull(if (chosen.any { Repo.where(it).presence != Presence.TABLET_ONLY }) "del servidor" else null,
                        if (chosen.any { Repo.where(it).presence != Presence.MISSING }) "del dispositivo" else null).joinToString(" y ")
                    Text("Se borran $where (libera ${formatSize(chosen.sumOf { it.size })}).", fontWeight = FontWeight.SemiBold)
                    if (withSaves) Muted("Sus partidas no se borran: quedan en el dispositivo.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    onClose()
                    appScope.launch {
                        var fails = 0
                        for (g in chosen) {
                            val p = Repo.where(g).presence
                            runCatching {
                                if (p != Presence.MISSING) Repo.delete(g)
                                if (p != Presence.TABLET_ONLY && Prefs.configured) Repo.deleteFromPc(g)
                            }.onFailure { fails++ }
                        }
                        Notice.info(if (fails == 0) "Listo: ${chosen.size} versión(es) borradas" else "$fails no se pudieron borrar")
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
        return
    }
    ReviewSheet("Juegos repetidos", "El mismo juego en varias versiones. Marca las que sobran, o quédate con todas si las quieres en tu colección.",
        onClose = onClose,
        footer = {
            Column(Modifier.weight(1f)) {
                Text("${chosen.size} versión(es) para borrar", fontWeight = FontWeight.Bold)
                if (chosen.isNotEmpty()) Muted("Libera ${formatSize(chosen.sumOf { it.size })}")
            }
            Button(enabled = chosen.isNotEmpty(), onClick = { confirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar (${chosen.size})") }
        },
    ) {
        if (groups.isEmpty()) item { Muted("Nada que revisar.") }
        if (Tidy.plan.orEmpty().any { it.kind != Tidy.Kind.INFO }) item {
            Text("Antes conviene «Juegos por ordenar»: varios de estos pueden ser descargas sin ordenar (comprimidos, nombres raros) y se arreglan solos.",
                color = Palette.warn, fontWeight = FontWeight.SemiBold)
        }
        items(groups, key = { Repo.versionKey(it.first()) }) { vs ->
            val advice = remember(vs) { Organize.recommend(vs) }
            val best = advice?.first ?: vs.first()
            SheetCard {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(best, Modifier.width(64.dp), showTitle = false)
                    Column(Modifier.weight(1f)) {
                        Text(best.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val systems = vs.map { Repo.system(it.system).name }.distinct()
                        Muted(if (systems.size > 1) "En ${systems.joinToString(" y ")} · ${vs.size} copias" else "${systems.first()} · ${vs.size} versiones")
                        advice?.let { (b, why) -> Text("Recomendada: ${versionName(b)} ($why)", color = Palette.ok, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        selected = selected - vs.map { it.id }.toSet() + vs.filter { it.id != best.id }.map { it.id }
                    }) { Text("Dejar solo la recomendada") }
                    TextButton(onClick = {
                        selected = selected - vs.map { it.id }.toSet()
                        Organize.keepAll(Organize.groupKey(vs))
                    }) { Text("Me quedo con todas") }
                }
                vs.forEach { v ->
                    val w = Repo.where(v)
                    CheckRow(v.id in selected, { selected = if (v.id in selected) selected - v.id else selected + v.id }) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(versionName(v), fontWeight = if (v.id == best.id) FontWeight.Bold else FontWeight.Normal)
                            Text(v.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (v.id == best.id) Pill("Recomendada", Palette.ok)
                            if (vs.map { it.system }.distinct().size > 1) Pill("Carpeta ${v.primary.substringBefore('/')}", Color(0xFF64748B))
                            Pill(when (w.presence) {
                                Presence.INSTALLED -> "En el dispositivo"
                                Presence.PARTIAL -> "A medias"
                                Presence.TABLET_ONLY -> "Solo en el dispositivo"
                                Presence.MISSING -> "En el servidor"
                            }, if (w.presence == Presence.MISSING) Palette.blue else Palette.warn.takeIf { w.presence == Presence.TABLET_ONLY } ?: Palette.ok)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "USA · RVZ · 1,2 GB": región o etiqueta, formato y tamaño. */
private fun versionName(v: Game) = listOfNotNull(
    Names.get(v.id) ?: v.tags.firstOrNull { t -> t.any { it.isLetter() } && t.length <= 24 },
    Group.ext(v.primary).removePrefix(".").uppercase().ifEmpty { "Carpeta" }, formatSize(v.size),
).joinToString(" · ")

/** Juegos sin copia en el servidor: carátulas en cuadrícula para marcar y copiar todo de una vez. */
@Composable
fun TabletOnlyDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember(Repo.tabletOnly) { Organize.tabletOnly().sortedBy { it.title.lowercase() } }
    // Columnas según el ancho (carátulas de ~170 dp): con 4 fijas quedaban gigantes en horizontal.
    val cols = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.88f / 170f).toInt().coerceIn(2, 9)
    var selected by remember(list) { mutableStateOf(list.map { it.id }.toSet()) }
    var copying by remember { mutableStateOf(false) }
    var stopAll by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<Game>>(emptyList()) }
    var finished by remember { mutableStateOf(setOf<String>()) }
    val chosen = list.filter { it.id in selected }
    val busy = PcEdit.uploading.isNotEmpty() || copying
    var askCancel by remember { mutableStateOf(false) }
    if (askCancel) AlertDialog(
        onDismissRequest = { askCancel = false },
        title = { Text("¿Cancelar la copia?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Los juegos que ya terminaron de copiarse quedan en el servidor.")
                Text("El que se está copiando ahora no queda a medias: se borra lo que alcanzó a subir.", fontWeight = FontWeight.SemiBold)
                Muted("En el dispositivo no cambia nada. Puedes volver a copiarlos cuando quieras.")
            }
        },
        confirmButton = {
            Button(onClick = {
                askCancel = false
                stopAll = true
                PcEdit.uploading.keys.toList().forEach { PcEdit.cancelUpload(it) }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Cancelar copia") }
        },
        dismissButton = { TextButton(onClick = { askCancel = false }) { Text("Seguir copiando") } },
    )
    ReviewSheet("Sin copia en el servidor", "Solo están en este dispositivo: si se pierde o se borra, se pierden. Se copian a la misma carpeta en el servidor; aquí no cambia nada.",
        onClose = onClose,
        footer = {
            if (busy) {
                // Como en Descargas: cuánto va de todo, a qué velocidad y cuánto falta.
                val total = queue.sumOf { it.size }
                val doneAll = queue.sumOf { g -> if (PcEdit.uploading.containsKey(g.id)) PcEdit.uploading[g.id] ?: 0 else if (g.id in finished) g.size else 0L }
                val speed = PcEdit.uploadSpeed.values.sum()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Copiando ${(finished.size + 1).coerceAtMost(queue.size)} de ${queue.size} · ${formatSize(doneAll)} de ${formatSize(total)}",
                        fontWeight = FontWeight.Bold)
                    androidx.compose.material3.LinearProgressIndicator(progress = { doneAll.toFloat() / total.coerceAtLeast(1) }, Modifier.fillMaxWidth())
                    Muted(listOf(formatSpeed(speed), formatEta(total - doneAll, speed)).filter { it.isNotBlank() }.joinToString(" · ")
                        .ifEmpty { "Empezando…" })
                }
                OutlinedButton(onClick = { Sound.play(Sound.Fx.WARN); askCancel = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Cancelar") }
            } else {
                Column(Modifier.weight(1f)) {
                    Text("${chosen.size} juego(s) · ${formatSize(chosen.sumOf { it.size })}", fontWeight = FontWeight.Bold)
                    Muted("Se copian a la misma carpeta en el servidor")
                }
                BigActionButton("Copiar al servidor", Icons.Rounded.CloudUpload, enabled = chosen.isNotEmpty() && Prefs.configured) {
                    queue = chosen
                    finished = emptySet()
                    stopAll = false
                    copying = true
                    appScope.launch {
                        var fails = 0
                        var stopped = 0
                        for (g in queue) {
                            if (stopAll) { stopped++; continue }
                            runCatching { PcEdit.uploadFromTablet(g) }
                                .onSuccess { finished = finished + g.id }
                                .onFailure { if (it is PcEdit.UploadCancelled) stopped++ else fails++ }
                        }
                        copying = false
                        Sound.play(if (fails == 0 && stopped == 0) Sound.Fx.DONE else Sound.Fx.WARN)
                        Notice.info(when {
                            stopped > 0 -> "Cancelado: ${finished.size} copiado(s), $stopped sin copiar"
                            fails == 0 -> "Listo: ${finished.size} juego(s) copiados al servidor"
                            else -> "$fails no se pudieron copiar"
                        })
                        Repo.refresh()
                    }
                }
            }
        },
    ) {
        if (list.isEmpty()) item { Muted("Todo tiene copia en el servidor.") }
        if (Tidy.plan.orEmpty().any { it.kind != Tidy.Kind.INFO }) item {
            Text("Antes conviene «Juegos por ordenar»: varios de estos pueden ser descargas sin ordenar (comprimidos, nombres raros) y se arreglan solos.",
                color = Palette.warn, fontWeight = FontWeight.SemiBold)
        }
        // De a 4 por fila: carátula, nombre, plataforma y tamaño; tocar marca o desmarca.
        items(list.chunked(cols), key = { it.first().id }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { g ->
                    val on = g.id in selected
                    val up = PcEdit.uploading[g.id]
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                        .clickable { selected = if (on) selected - g.id else selected + g.id }.padding(4.dp)) {
                        Box {
                            Cover(g, Modifier.fillMaxWidth().then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier)) {
                                DownloadOverlay(g)
                            }
                            Checkbox(on, { selected = if (it) selected + g.id else selected - g.id }, Modifier.align(Alignment.TopEnd))
                        }
                        Text(g.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Muted("${Repo.system(g.system).short} · ${formatSize(g.size)}")
                        if (up != null) {
                            val sp = PcEdit.uploadSpeed[g.id] ?: 0L
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { up.toFloat() / g.size.coerceAtLeast(1) }, Modifier.fillMaxWidth().padding(top = 4.dp))
                            Muted("${formatSize(up)} de ${formatSize(g.size)}" + (if (sp > 0) " · ${formatSpeed(sp)} · ${formatEta(g.size - up, sp)}" else ""))
                        } else if (g.id in finished) Text("Copiado ✓", color = Palette.ok, fontWeight = FontWeight.SemiBold)
                        else if (copying && g in queue) Muted("En espera")
                    }
                }
                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
