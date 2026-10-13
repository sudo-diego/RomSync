package io.github.sudodiego.romsync

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Pone a descargar archivos de un juego. Antes revisa el espacio (lo que falta, lo que ya está en cola y la reserva).
 * Devuelve null si quedó en cola o el motivo por el que no.
 */
fun startDownload(ctx: Context, g: Game, files: List<FileEntry>): String? {
    if (!Prefs.configured) return "Falta conectar el PC (Ajustes)"
    val need = files.sumOf { it.size - java.io.File(Local.romFile(it.path).path + ".part").length().coerceAtMost(it.size) }
    val free = Repo.space.free - Prefs.reserveGb * 1_000_000_000L - DownloadService.pendingBytes()
    if (need > free) return "No hay espacio: faltan ${formatSize(need - free)}"
    DownloadService.enqueue(ctx, Download(g.id, g.title, g.system, files))
    return null
}

private val partNames = mapOf("base" to "Juego base", "update" to "Actualización", "dlc" to "DLC")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameScreen(g: Game, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = appScope
    // Se vuelve a calcular cuando cambian los archivos de la tablet (al terminar una descarga o al borrar).
    val w = remember(Repo.localFiles, g) { Repo.where(g) }
    val sys = Repo.system(g.system)
    val dl = DownloadService.items.firstOrNull { it.id == g.id && it.status != DlStatus.DONE }
    var askDelete by remember { mutableStateOf(false) }
    var askDeletePc by remember { mutableStateOf(false) }
    var pickArt by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var noSpace by remember { mutableStateOf<String?>(null) }
    // Switch: qué partes descargar (por defecto, todas las que faltan).
    var selected by remember(g, w) {
        mutableStateOf(g.parts.filter { p -> p.files.any { !Repo.has(it.path) } }.map { it.titleId }.toSet())
    }
    val toDownload = if (g.parts.isNotEmpty()) g.parts.filter { it.titleId in selected }.flatMap { it.files }.filter { !Repo.has(it.path) }
    else w.missing
    val downloadSize = toDownload.sumOf { it.size }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Fondo (hero) con degradado hacia el color de la página.
        Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.linearGradient(listOf(Color(sys.color), Color(0xFF0D1220))))) {
            ArtImage(g, "hero", Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
            ArtImage(g, "logo", Modifier.align(Alignment.BottomEnd).padding(24.dp).height(90.dp).width(260.dp), ContentScale.Fit)
            IconButton(onClick = onBack, Modifier.padding(12.dp).clip(RoundedCornerShape(99.dp)).background(Color(0x66000000))) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver", tint = Color.White)
            }
        }
        Row(Modifier.padding(horizontal = 24.dp).offset(y = (-90).dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Cover(g, Modifier.width(190.dp))
            Column(Modifier.weight(1f).padding(top = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(g.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Muted(listOfNotNull(sys.name, Group.ext(g.primary).removePrefix(".").uppercase().ifEmpty { null }, formatSize(g.size), "${g.files.size} archivo(s)", g.serial.ifBlank { null }).joinToString(" · "))
                val versionList = remember(g, Repo.library, Repo.tabletOnly) { listOf(g) + Repo.versions(g) }
                if (versionList.size > 1) {
                    Muted("Versiones de este juego:")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        versionList.sortedBy { it.name }.forEach { v ->
                            androidx.compose.material3.FilterChip(selected = v.id == g.id, onClick = { Nav.game = v }, label = {
                                Text(listOfNotNull(Group.ext(v.primary).removePrefix(".").uppercase().ifEmpty { "Carpeta" },
                                    v.tags.firstOrNull(), Names.get(v.id)).joinToString(" · "))
                            })
                        }
                    }
                }
                if (g.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    g.tags.forEach { Pill(it, Color(0xFF475569)) }
                }

                StateCard(g, w, downloadSize)

                // Acciones, como en la eShop: el tamaño en el botón.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        dl != null && dl.status != DlStatus.FAILED -> {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(progress = { dl.done.toFloat() / dl.total.coerceAtLeast(1) }, Modifier.fillMaxWidth())
                                Muted("${formatSize(dl.done)} de ${formatSize(dl.total)}  ${formatSpeed(dl.speed)}  ${formatEta(dl.total - dl.done, dl.speed)}")
                            }
                            if (dl.status == DlStatus.PAUSED) FilledTonalButton(onClick = { DownloadService.resume(ctx, dl) }) {
                                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Seguir")
                            } else FilledTonalButton(onClick = { DownloadService.pause(dl) }) {
                                Icon(Icons.Rounded.Pause, null); Spacer(Modifier.width(6.dp)); Text("Pausar")
                            }
                            TextButton(onClick = { DownloadService.cancel(dl) }) { Text("Cancelar") }
                        }
                        w.presence != Presence.INSTALLED && w.presence != Presence.TABLET_ONLY && toDownload.isNotEmpty() -> {
                            Button(onClick = {
                                noSpace = startDownload(ctx, g, toDownload)
                            }) {
                                Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp))
                                Text(if (w.presence == Presence.PARTIAL) "Descargar lo que falta · ${formatSize(downloadSize)}" else "Descargar · ${formatSize(downloadSize)}")
                            }
                        }
                    }
                    if (w.presence != Presence.MISSING && dl?.status != DlStatus.RUNNING) {
                        OutlinedButton(onClick = { askDelete = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(6.dp)); Text("Borrar de la tablet")
                        }
                    }
                }
                dl?.takeIf { it.status == DlStatus.FAILED }?.let { Text("La descarga falló: ${it.error}", color = MaterialTheme.colorScheme.error) }

                if (g.parts.size > 1 || g.parts.any { it.kind != "base" }) {
                    Section("Contenido") {
                        g.parts.forEach { p ->
                            val have = p.files.all { Repo.has(it.path) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = have || p.titleId in selected, enabled = !have,
                                    onCheckedChange = { selected = if (it) selected + p.titleId else selected - p.titleId })
                                Column(Modifier.weight(1f)) {
                                    Text(partNames[p.kind] + (if (p.version > 0) " v${p.version}" else ""), fontWeight = FontWeight.SemiBold)
                                    Muted(p.titleId)
                                }
                                Text(formatSize(p.size))
                                Spacer(Modifier.width(12.dp))
                                if (have) Pill("En la tablet", Palette.ok)
                            }
                        }
                        Muted("En Eden, deja las actualizaciones y DLC en la carpeta de contenido externo (Ajustes → Carpetas de juegos) para no instalarlas en la NAND.")
                    }
                }

                val others = remember(g, Repo.library, Repo.tabletOnly) { Repo.versions(g) }
                if (others.isNotEmpty()) Section("Otras versiones de este juego") {
                    val advice = remember(g, others) { Advice.pick(listOf(g) + others) }
                    if (advice != null) {
                        val (best, why) = advice
                        Text("Conviene quedarse con el ${Group.ext(best.primary).removePrefix(".").uppercase().ifEmpty { "de carpeta" }}" +
                            (if (best.id == g.id) " (este)" else "") + ": $why. Los otros los puedes borrar.",
                            color = Palette.ok, fontWeight = FontWeight.SemiBold)
                    } else Muted("Mismo nombre en ${sys.name}: otra región o una versión modificada. Quédate con la que uses.")
                    (listOf(g) + others).forEach { o ->
                        val ow = Repo.where(o)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = o.id != g.id) { Nav.game = o }
                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill(Group.ext(o.primary).removePrefix(".").uppercase().ifEmpty { "CARPETA" }, Color(0xFF475569))
                            Column(Modifier.weight(1f)) {
                                Text(o.name, fontWeight = if (o.id == g.id) FontWeight.Bold else FontWeight.Normal)
                                Muted(formatSize(o.size) + (if (o.id == g.id) " · este" else ""))
                            }
                            when (ow.presence) {
                                Presence.INSTALLED -> Pill("En la tablet", Palette.ok)
                                Presence.PARTIAL -> Pill("A medias", Palette.warn)
                                Presence.TABLET_ONLY -> Pill("Solo tablet", Color(0xFF475569))
                                Presence.MISSING -> Pill("Solo PC", Color(0xFF334155))
                            }
                        }
                    }
                }

                Section("Más") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { renaming = true }) { Text("Cambiar nombre") }
                        OutlinedButton(onClick = { pickArt = true }) { Text("Cambiar carátula") }
                        if (g.kind == "discs" && w.presence != Presence.TABLET_ONLY) OutlinedButton(onClick = {
                            scope.launch {
                                val msg = runCatching { PcEdit.createM3u(g) }.fold({ "Listo: $it. Actualiza la biblioteca para verlo." }, { it.message ?: "Error" })
                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                Repo.refresh()
                            }
                        }) { Text("Crear .m3u (un solo juego para RetroArch)") }
                        if (w.presence != Presence.TABLET_ONLY && Prefs.configured) OutlinedButton(onClick = { askDeletePc = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Borrar del PC") }
                    }
                    if (g.kind == "shortcut") Muted("Es un acceso directo (no un ROM): lo crea el frontend o GameNative.")
                }

                Section(if (g.files.size > 40) "Archivos (${g.files.size})" else "Archivos") {
                    // Una carpeta de Wii U puede tener decenas de miles de archivos: dibujarlos todos cerraba la app.
                    g.files.take(40).forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(formatSize(f.size), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(99.dp))
                                .background(if (Repo.has(f.path)) Palette.ok else MaterialTheme.colorScheme.outline))
                        }
                    }
                    if (g.files.size > 40) Muted("y ${g.files.size - 40} archivos más")
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (askDelete) {
        val onPc = w.presence != Presence.TABLET_ONLY
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("¿Borrar ${g.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Se liberan ${formatSize(w.localSize)} en la tablet.")
                    Text("Tus partidas guardadas no se borran.", fontWeight = FontWeight.SemiBold)
                    if (onPc) Muted("Lo puedes volver a descargar desde el PC cuando quieras.")
                    else Text("Este juego no está en el PC: si lo borras, no lo vas a poder volver a descargar con RomSync.",
                        color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(onClick = {
                    askDelete = false
                    scope.launch {
                        val freed = Repo.delete(g)
                        Toast.makeText(ctx, "Borrado · ${formatSize(freed)} libres", Toast.LENGTH_SHORT).show()
                        if (!onPc) onBack()
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancelar") } },
        )
    }

    if (askDeletePc) {
        var sure by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askDeletePc = false },
            title = { Text("¿Borrar ${g.title} del PC?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Se borran ${g.files.size} archivo(s) (${formatSize(g.size)}) del disco del PC. No van a la papelera.")
                    if (w.presence == Presence.INSTALLED || w.presence == Presence.PARTIAL) Muted("La copia de la tablet se queda.")
                    else Text("No está en la tablet: después no lo vas a tener en ningún lado.", color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = sure, onCheckedChange = { sure = it })
                        Text("Sí, borrarlo del PC")
                    }
                }
            },
            confirmButton = {
                Button(enabled = sure, onClick = {
                    askDeletePc = false
                    scope.launch {
                        val msg = runCatching { Repo.deleteFromPc(g) }.fold({ "Borrado del PC · ${formatSize(it)}" }, { it.message ?: "Error" })
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        if (w.presence == Presence.MISSING) onBack()
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar del PC") }
            },
            dismissButton = { TextButton(onClick = { askDeletePc = false }) { Text("Cancelar") } },
        )
    }

    if (pickArt) ArtPicker(g) { pickArt = false }

    if (renaming) {
        var name by remember { mutableStateOf(Names.get(g.id) ?: g.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nombre del juego") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Muted("Solo cambia cómo se ve en RomSync (y con qué nombre se busca la carátula). El archivo sigue igual: ${g.name}")
                }
            },
            confirmButton = {
                Button(onClick = {
                    Repo.rename(g, name)
                    renaming = false
                    Nav.game = Repo.game(g.id) ?: g
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Repo.rename(g, "")
                    renaming = false
                    Nav.game = Repo.game(g.id) ?: g
                }) { Text("Usar el del archivo") }
            },
        )
    }

    noSpace?.let { msg ->
        val biggest = remember(Repo.localFiles) {
            Repo.allGames().filter { it.id != g.id && Repo.where(it).presence != Presence.MISSING }
                .sortedByDescending { Repo.where(it).localSize }.take(5)
        }
        AlertDialog(
            onDismissRequest = { noSpace = null },
            title = { Text("No se puede descargar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(msg)
                    if (msg.startsWith("No hay espacio") && biggest.isNotEmpty()) {
                        Muted("Los que más ocupan en la tablet:")
                        biggest.forEach { b ->
                            TextButton(onClick = { noSpace = null; Nav.game = b }) {
                                Text("${b.title} · ${formatSize(Repo.where(b).localSize)}", Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { noSpace = null }) { Text("Entendido") } },
        )
    }
}

@Composable
private fun StateCard(g: Game, w: Repo.Where, downloadSize: Long) {
    val (text, color) = when (w.presence) {
        Presence.INSTALLED -> (if (w.differs) "En la tablet · algún archivo es distinto al del PC" else "En la tablet") to Palette.ok
        Presence.PARTIAL -> "A medias en la tablet · faltan ${w.missing.size} archivo(s)" to Palette.warn
        Presence.MISSING -> "No está en la tablet" to MaterialTheme.colorScheme.onSurfaceVariant
        Presence.TABLET_ONLY -> "Solo en la tablet (no está en el PC)" to Color(0xFF94A3B8)
    }
    Section("") {
        Text(text, color = color, fontWeight = FontWeight.Bold)
        if (w.presence == Presence.MISSING || w.presence == Presence.PARTIAL) {
            val free = Repo.space.free
            val after = free - downloadSize
            Muted("Ocupa ${formatSize(downloadSize)} · libres ahora ${formatSize(free)} · después ${formatSize(after.coerceAtLeast(0))}")
            if (Repo.space.total > 0) UsageBar(listOf(
                (Repo.space.total - free).toFloat() / Repo.space.total to MaterialTheme.colorScheme.primary,
                downloadSize.toFloat() / Repo.space.total to if (after < Prefs.reserveGb * 1_000_000_000L) Palette.danger else Palette.sky,
            ))
        }
    }
}
