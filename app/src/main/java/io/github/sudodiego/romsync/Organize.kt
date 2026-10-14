package io.github.sudodiego.romsync

import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    fun duplicateGroups(): List<List<Game>> = Repo.allGames().filter { it.kind != "shortcut" }
        .groupBy { Repo.versionKey(it) }.filter { (k, v) -> v.size > 1 && k !in keptVersions }.values
        .sortedBy { it.first().title.lowercase() }

    /** Estado para que la tarjeta y la lista se actualicen al marcar un grupo. */
    var keptVersions by androidx.compose.runtime.mutableStateOf(Prefs.keepAllVersions)
        private set

    fun keepAll(key: String) {
        keptVersions = keptVersions + key
        Prefs.keepAllVersions = keptVersions
    }

    /** Juegos que solo están en la tablet: si se pierde la tablet, se pierden. */
    fun tabletOnly(): List<Game> = if (!Prefs.configured) emptyList()
    else Repo.tabletOnly.filter { it.kind != "shortcut" }


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
        only.takeIf { it > 0 }?.let { "$it juego(s) sin copia en el PC" },
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
                Muted("Paso a paso. Nada se borra sin que lo confirmes, y las partidas nunca se tocan.")
                Step(1, "Archivos que sobran", "Descargas a medias y copias viejas de partidas que dejan otras apps.", junk) { step = "junk" }
                Step(2, "BIOS fuera de su carpeta", "BIOS sueltas junto a los juegos: van a la carpeta bios, donde las buscan los emuladores.", bios) { step = "bios" }
                Step(3, "Juegos repetidos", "El mismo juego en varias regiones o formatos. Te digo con cuál quedarte.", dups) { step = "dups" }
                Step(4, "Juegos sin copia en el PC", "Solo están en la tablet: cópialos al PC para no perderlos.", only) { step = "only" }
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

/** Cada juego repetido con la versión recomendada; marcas las que sobran y se borran del PC y de la tablet. */
@Composable
private fun DuplicatesDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val groups = remember(Repo.library, Repo.tabletOnly, Organize.keptVersions) { Organize.duplicateGroups() }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val chosen = groups.flatten().filter { it.id in selected }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar ${chosen.size} versión(es)?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chosen.take(8).forEach { Text("• ${it.name} · ${formatSize(it.size)}") }
                    if (chosen.size > 8) Muted("y ${chosen.size - 8} más")
                    Text("Se borran del PC y de la tablet (libera ${formatSize(chosen.sumOf { it.size })} en el PC).", fontWeight = FontWeight.SemiBold)
                    Muted("Las partidas no se borran.")
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
                        Toast.makeText(ctx, if (fails == 0) "Listo: ${chosen.size} versión(es) borradas" else "$fails no se pudieron borrar", Toast.LENGTH_LONG).show()
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Juegos repetidos") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Muted("Marca las versiones que sobran, o toca «Me quedo con todas» si las quieres en tu colección (no se vuelve a preguntar).") }
                if (groups.isEmpty()) item { Muted("Nada que revisar.") }
                items(groups, key = { Repo.versionKey(it.first()) }) { vs ->
                    val advice = remember(vs) { Advice.pick(vs) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SpacedRow {
                            Text("${Group.cleanTitle(vs.first().name).first} · ${Repo.system(vs.first().system).short}",
                                Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = {
                                selected = selected - vs.map { it.id }.toSet()
                                Organize.keepAll(Repo.versionKey(vs.first()))
                            }) { Text("Me quedo con todas") }
                        }
                        advice?.let { (best, why) ->
                            Text("Conviene quedarse con: ${best.name} ($why)", color = Palette.ok, style = MaterialTheme.typography.bodySmall)
                        }
                        vs.forEach { v ->
                            val w = Repo.where(v)
                            Row(Modifier.fillMaxWidth().clickable { selected = if (v.id in selected) selected - v.id else selected + v.id },
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = v.id in selected, onCheckedChange = { selected = if (it) selected + v.id else selected - v.id })
                                Column(Modifier.weight(1f)) {
                                    Text(v.name, style = MaterialTheme.typography.bodyMedium)
                                    Muted(listOfNotNull(Group.ext(v.primary).removePrefix(".").uppercase().ifEmpty { "Carpeta" }, formatSize(v.size),
                                        when (w.presence) {
                                            Presence.INSTALLED -> "en la tablet"
                                            Presence.PARTIAL -> "a medias en la tablet"
                                            Presence.TABLET_ONLY -> "solo en la tablet"
                                            Presence.MISSING -> "solo en el PC"
                                        }, if (advice?.first?.id == v.id) "recomendada" else null).joinToString(" · "))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(enabled = selected.isNotEmpty(), onClick = { confirm = true }) { Text("Borrar (${selected.size})") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
    )
}

/** Los juegos que solo están en la tablet, para copiarlos todos al PC de una vez. */
@Composable
private fun TabletOnlyDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember(Repo.tabletOnly) { Organize.tabletOnly() }
    var selected by remember(list) { mutableStateOf(list.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Juegos sin copia en el PC") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                item { Muted("Se copian a la misma carpeta en el PC. La tablet no cambia.", Modifier.padding(bottom = 8.dp)) }
                items(list, key = { it.id }) { g ->
                    val up = PcEdit.uploading[g.id]
                    Row(Modifier.fillMaxWidth().clickable { selected = if (g.id in selected) selected - g.id else selected + g.id },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = g.id in selected, onCheckedChange = { selected = if (it) selected + g.id else selected - g.id })
                        Column(Modifier.weight(1f)) {
                            Text(g.title, fontWeight = FontWeight.SemiBold)
                            Muted("${Repo.system(g.system).short} · ${formatSize(g.size)}" + (up?.let { " · copiando ${formatSize(it)}" } ?: ""))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = selected.isNotEmpty() && PcEdit.uploading.isEmpty(), onClick = {
                val todo = list.filter { it.id in selected }
                appScope.launch {
                    var fails = 0
                    for (g in todo) runCatching { PcEdit.uploadFromTablet(g) }.onFailure { fails++ }
                    Toast.makeText(ctx, if (fails == 0) "Listo: ${todo.size} juego(s) copiados al PC" else "$fails no se pudieron copiar", Toast.LENGTH_LONG).show()
                    Repo.refresh()
                }
            }) { Text("Copiar al PC (${formatSize(list.filter { it.id in selected }.sumOf { it.size })})") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
    )
}
