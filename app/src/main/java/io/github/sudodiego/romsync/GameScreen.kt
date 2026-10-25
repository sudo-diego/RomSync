package io.github.sudodiego.romsync

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.border
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import android.content.Context
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
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    if (!Prefs.configured) return "Falta conectar el servidor (Ajustes)"
    val need = files.sumOf { it.size - java.io.File(Local.romFile(it.path).path + ".part").length().coerceAtMost(it.size) }
    val free = Repo.space.free - Prefs.reserveGb * 1_000_000_000L - DownloadService.pendingBytes()
    if (need > free) return "No hay espacio: faltan ${formatSize(need - free)}"
    DownloadService.enqueue(ctx, Download(g.id, g.title, g.system, files))
    return null
}

private val partNames = mapOf("base" to "Juego base", "update" to "Actualización", "dlc" to "DLC")

/** "DLC · Challenger Pack 1" (el nombre que trae el archivo, sin el del juego) o "Actualización · versión 30". */
private fun partLabel(g: Game, p: Part): String {
    if (p.kind == "dlc") {
        val stem = p.files.firstOrNull()?.path?.let(Group::stem).orEmpty()
        val title = Group.cleanTitle(stem.replace("[DLC]", "")).first
        val base = Group.cleanTitle(g.name).first
        val name = title.removePrefix(base).trim(' ', '-', ':')
        if (name.isNotEmpty() && !name.equals(title, true)) return "DLC · $name"
    }
    return partNames[p.kind] + (if (p.version > 0) " · ${SwitchDb.label(p.version)}" else "")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameScreen(g: Game, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = appScope
    // Se vuelve a calcular cuando cambian los archivos de la tablet (al terminar una descarga o al borrar).
    val w = remember(Repo.localFiles, g) { Repo.where(g) }
    val sys = Repo.system(g.system)
    val dl = DownloadService.items.firstOrNull { it.id == g.id && it.status != DlStatus.DONE }
    var askDelete by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var noSpace by remember { mutableStateOf<String?>(null) }
    // Switch: qué partes descargar (por defecto, todas las que faltan).
    var selected by remember(g, w) {
        mutableStateOf(g.parts.filter { p -> p.files.any { !Repo.has(it.path) } }.map { it.titleId }.toSet())
    }
    val toDownload = if (g.parts.isNotEmpty()) g.parts.filter { it.titleId in selected }.flatMap { it.files }.filter { !Repo.has(it.path) }
    else w.missing
    val downloadSize = toDownload.sumOf { it.size }

    PadHints(listOf("A" to "Elegir", "B" to "Volver", "L R" to "Sección"))
    val scroll = rememberScrollState()
    // Al entrar con mando, el foco cae en el botón principal pero la ficha queda arriba (carátula y fondo a la vista).
    LaunchedEffect(g.id) { if (Nav.keys || Nav.pad) { kotlinx.coroutines.delay(150); scroll.scrollTo(0) } }
    val enter = remember(g.id) { Animatable(0f) }
    LaunchedEffect(g.id) { enter.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 260f)) }
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollConfiguration provides null) {
    Column(Modifier.fillMaxSize().screenFocus { scroll.animateScrollBy(it) }.verticalScroll(scroll)) {
        // Fondo (hero) con degradado hacia la página; se mueve más lento que el scroll (paralaje).
        Box(Modifier.fillMaxWidth().height(300.dp).graphicsLayer { translationY = kotlin.math.round(scroll.value * 0.45f); clip = true }) {
            // El fondo se vuelve transparente hacia abajo (máscara), en vez de fundirse a un color fijo: así se mezcla
            // con el fondo animado de la app y no queda una línea blanca en el borde.
            Box(Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    // Y hacia la izquierda: se funde con la barra lateral en vez de cortarse de golpe.
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.07f to Color.Black, 1f to Color.Black),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                }
                .background(Brush.linearGradient(listOf(Color(sys.color), Color(0xFF0D1220))))) {
                ArtImage(g, "hero", Modifier.fillMaxSize())
            }
            ArtImage(g, "logo", Modifier.align(Alignment.BottomEnd).padding(28.dp).height(100.dp).width(300.dp), ContentScale.Fit)
            IconButton(onClick = onBack, Modifier.notForDpad().padding(14.dp).size(48.dp).clip(RoundedCornerShape(99.dp)).background(Color(0x88000000))) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver", tint = Color.White)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(14.dp)) {
                IconButton(onClick = { Sound.play(Sound.Fx.MOVE); menu = true },
                    Modifier.notForDpad().size(48.dp).clip(RoundedCornerShape(99.dp)).background(Color(0x88000000))) {
                    Icon(Icons.Rounded.MoreVert, "Más", tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Editar nombre e imágenes") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; editing = true })
                    if (g.kind == "discs") DropdownMenuItem(text = { Text("Crear .m3u (un solo juego)") }, leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) },
                        onClick = {
                            menu = false
                            scope.launch {
                                val msg = runCatching { PcEdit.createM3u(g, onPc = w.presence != Presence.TABLET_ONLY && Prefs.configured) }
                                    .fold({ "Listo: $it" }, { it.message ?: "Error" })
                                Notice.info(msg)
                                Repo.refresh()
                            }
                        })
                    // Del servidor solo desde aquí (a propósito menos a mano que el botón grande).
                    if (w.presence != Presence.TABLET_ONLY && Prefs.configured)
                        DropdownMenuItem(text = { Text("Borrar del servidor…", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; askDelete = "srv" })
                }
            }
        }
        Row(Modifier.padding(horizontal = 28.dp).offset(y = (-100).dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // Tocar la portada abre el editor (nombre, carátula, fondo y logo).
            // La sombra crece con la animación, en la misma capa (las sombras de Android no se desvanecen con la
            // transparencia: se veía una sombra entera bajo una carátula todavía transparente).
            Box(Modifier.width(200.dp).graphicsLayer {
                val k = enter.value.coerceIn(0f, 1f)
                alpha = k; translationY = (1f - k) * 80f
                rotationZ = (1f - k) * -4f
            }.notForDpad().softShadow(18.dp, 16.dp).clip(RoundedCornerShape(18.dp)).pressable { Sound.play(Sound.Fx.OPEN); editing = true }) {
                Cover(g, Modifier.fillMaxWidth()) {
                    // Un solo dibujo para todo (descarga, final y regalo), así al terminar no se corta nada.
                    // Estilo 3DS: si hay regalo, primero termina de envolverse, una pausa y recién ahí se abre.
                    var opening by remember(g.id) { mutableStateOf(false) }
                    DownloadOverlay(g, sound = true, opening = opening)
                    if (g.id in DownloadArt.gifts) LaunchedEffect(g.id) {
                        kotlinx.coroutines.delay(DownloadArt.untilWrapped(g.id) + 500)
                        opening = true
                        kotlinx.coroutines.delay(750); DownloadArt.unwrap(g.id)
                    }
                }
                Icon(Icons.Rounded.Edit, "Editar", Modifier.align(Alignment.TopEnd).padding(8.dp).size(34.dp)
                    .clip(RoundedCornerShape(99.dp)).background(Color(0xAA000000)).padding(7.dp), tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(top = 108.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(g.title, style = MaterialTheme.typography.headlineMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(sys.name, Color(sys.color))
                    Pill(formatSize(g.size), Color(0xFF64748B))
                    Group.ext(g.primary).removePrefix(".").uppercase().ifEmpty { null }?.let { Pill(it, Color(0xFF64748B)) }
                }
                val versionList = remember(g, Repo.library, Repo.tabletOnly) { listOf(g) + Repo.versions(g) }
                if (versionList.size > 1) VersionPicker(g, versionList)

                StateCard(g, w, downloadSize)

                // Acciones, como en la eShop: el tamaño en el botón.
                var askCancel by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        dl != null && dl.status != DlStatus.FAILED -> {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(progress = { dl.done.toFloat() / dl.total.coerceAtLeast(1) }, Modifier.fillMaxWidth().height(8.dp))
                                Text("${formatSize(dl.done)} de ${formatSize(dl.total)}", fontWeight = FontWeight.SemiBold)
                                Muted(listOf(formatSpeed(dl.speed), formatEta(dl.total - dl.done, dl.speed)).filter { it.isNotBlank() }.joinToString(" · "))
                            }
                            val pill = RoundedCornerShape(99.dp)
                            if (dl.status == DlStatus.PAUSED) FilledTonalButton(onClick = { Sound.play(Sound.Fx.MOVE); DownloadService.resume(ctx, dl) },
                                Modifier.focusHighlight(pill)) {
                                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Seguir")
                            } else FilledTonalButton(onClick = { Sound.play(Sound.Fx.MOVE); DownloadService.pause(dl) }, Modifier.focusHighlight(pill).autoFocusPad()) {
                                Icon(Icons.Rounded.Pause, null); Spacer(Modifier.width(6.dp)); Text("Pausar")
                            }
                            TextButton(onClick = { Sound.play(Sound.Fx.WARN); askCancel = true }, Modifier.focusHighlight(pill)) { Text("Cancelar") }
                            if (askCancel) CancelDownloadDialog(dl) { askCancel = false }
                        }
                        // Recién terminada, mientras se registra el archivo nuevo, no vuelve a asomar "Descargar".
                        w.presence != Presence.INSTALLED && w.presence != Presence.TABLET_ONLY && toDownload.isNotEmpty() &&
                            DownloadService.items.none { it.id == g.id && it.status == DlStatus.DONE } -> {
                            BigDownloadButton(
                                if (w.presence == Presence.PARTIAL) "Descargar lo que falta" else "Descargar", formatSize(downloadSize),
                                Modifier.weight(1f),
                            ) {
                                noSpace = startDownload(ctx, g, toDownload)
                                Sound.play(if (noSpace == null) Sound.Fx.DONE else Sound.Fx.WARN)
                            }
                        }
                    }
                    // Un juego que solo está en la tablet se puede dejar guardado en el PC desde aquí mismo.
                    if (w.presence == Presence.TABLET_ONLY && Prefs.configured && g.kind != "shortcut") {
                        val up = PcEdit.uploading[g.id]
                        if (up != null) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(progress = { up.toFloat() / g.size.coerceAtLeast(1) }, Modifier.fillMaxWidth())
                                val sp = PcEdit.uploadSpeed[g.id] ?: 0L
                                Muted("Copiando al servidor: ${formatSize(up)} de ${formatSize(g.size)}" +
                                    (if (sp > 0) " · ${formatSpeed(sp)} · ${formatEta(g.size - up, sp)}" else ""))
                            }
                            // Cancelar no deja nada a medias en el servidor (se borra lo que alcanzó a subir).
                            TextButton(onClick = { Sound.play(Sound.Fx.WARN); PcEdit.cancelUpload(g.id) },
                                modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp))) { Text("Cancelar") }
                        } else FilledTonalButton(modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp)), onClick = {
                            Sound.play(Sound.Fx.MOVE)
                            scope.launch {
                                val msg = runCatching { PcEdit.uploadFromTablet(g) }.fold({ "${g.title} ya está en el servidor" },
                                    { if (it is PcEdit.UploadCancelled) "Copia cancelada: no quedó nada a medias en el servidor" else "No se pudo copiar: ${it.message}" })
                                Notice.info(msg)
                                Repo.refresh()
                            }
                        }) { Text("Copiar al servidor · ${formatSize(g.size)}") }
                    }
                    if (w.presence != Presence.MISSING && dl?.status != DlStatus.RUNNING) {
                        // Sin nada para descargar, Borrar ocupa el lugar grande (igual que Descargar).
                        val alone = !(w.presence != Presence.INSTALLED && w.presence != Presence.TABLET_ONLY && toDownload.isNotEmpty()) &&
                            dl == null && !(w.presence == Presence.TABLET_ONLY && Prefs.configured && g.kind != "shortcut")
                        BigDeleteButton(if (alone) Modifier.weight(1f) else Modifier, autoFocus = alone) { Sound.play(Sound.Fx.WARN); askDelete = "dev" }
                    }
                }
                dl?.takeIf { it.status == DlStatus.FAILED }?.let { Text("La descarga falló: ${it.error}", color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { Sound.play(Sound.Fx.OPEN); editing = true }) {
                        Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Editar")
                    }
                    if (g.kind == "discs") TextButton(onClick = {
                        scope.launch {
                            val msg = runCatching { PcEdit.createM3u(g, onPc = w.presence != Presence.TABLET_ONLY && Prefs.configured) }
                                .fold({ "Listo: $it" }, { it.message ?: "Error" })
                            Notice.info(msg); Repo.refresh()
                        }
                    }) { Text("Crear .m3u") }
                    if (w.presence != Presence.TABLET_ONLY && Prefs.configured) TextButton(onClick = { Sound.play(Sound.Fx.WARN); askDelete = "srv" },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Borrar del servidor…") }
                }

                if (g.kind == "switch") SwitchSummary(g)
                if (g.parts.size > 1 || g.parts.any { it.kind != "base" }) {
                    Section(if (g.kind == "switch") "Lo que tienes" else "Contenido") {
                        // Smash tiene ~100 DLC: marcar o desmarcar de a uno era imposible.
                        val missing = g.parts.filter { p -> !p.files.all { Repo.has(it.path) } }.map { it.titleId }.toSet()
                        if (missing.size > 1) SpacedRow {
                            Muted("${missing.size} partes que no están en el dispositivo", Modifier.weight(1f))
                            TextButton(onClick = { selected = missing }) { Text("Marcar todo") }
                            TextButton(onClick = { selected = emptySet() }) { Text("Desmarcar todo") }
                        }
                        g.parts.forEach { p ->
                            val have = p.files.all { Repo.has(it.path) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = have || p.titleId in selected, enabled = !have,
                                    onCheckedChange = { selected = if (it) selected + p.titleId else selected - p.titleId })
                                Column(Modifier.weight(1f)) {
                                    Text(partLabel(g, p), fontWeight = FontWeight.SemiBold)
                                    Muted(p.titleId)
                                }
                                Text(formatSize(p.size))
                                Spacer(Modifier.width(12.dp))
                                if (have) Pill("En el dispositivo", Palette.ok) else Pill("En el servidor", Palette.blue)
                            }
                        }
                    }
                }
                if ((g.kind == "discs" || g.kind == "multi") && g.files.size > 1) Section("Contenido") {
                    g.files.sortedBy { it.path }.forEach { f ->
                        val have = Repo.has(f.path)
                        val name = f.path.substringAfterLast('/')
                        val label = Group.discOf(Group.stem(name))?.let { "Disco ${it.second}" } ?: when (Group.ext(name)) {
                            ".m3u" -> "Lista de discos (.m3u)"; ".cue", ".gdi" -> "Índice del disco (${Group.ext(name)})"; else -> name
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (Group.ext(name) == ".m3u") Icons.Rounded.PlaylistPlay else Icons.Rounded.Album, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text(formatSize(f.size))
                            Spacer(Modifier.width(12.dp))
                            if (have) Icon(Icons.Rounded.CheckCircle, "En el dispositivo", tint = Palette.ok)
                            else Icon(Icons.Rounded.CloudDownload, "Para descargar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TabletExtras(g)

                VitaInstallSection(g)
                GameSavesSection(g)

                if (g.kind == "shortcut") Muted("Acceso directo (lo crea el frontend o GameNative).")

                var showFiles by remember(g.id) { mutableStateOf(false) }
                Section("") {
                    SpacedRow(Modifier.fillMaxWidth().clickable { Sound.play(Sound.Fx.MOVE); showFiles = !showFiles }) {
                        Text("Archivos (${g.files.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        if (g.serial.isNotBlank()) Muted(g.serial)
                        Icon(if (showFiles) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    }
                    if (g.tags.isNotEmpty() && showFiles) Muted(g.tags.joinToString(" · "))
                    // Una carpeta de Wii U puede tener decenas de miles de archivos: dibujarlos todos cerraba la app.
                    if (showFiles) g.files.take(40).forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(formatSize(f.size), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(99.dp))
                                .background(if (Repo.has(f.path)) Palette.ok else MaterialTheme.colorScheme.outline))
                        }
                    }
                    if (showFiles && g.files.size > 40) Muted("y ${g.files.size - 40} archivos más")
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    }

    askDelete?.let { what -> DeleteNow(g, w, what) { askDelete = null; if (it) onBack() } }
    if (editing) GameEditor(g) { editing = false }

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
                        Muted("Los que más ocupan en el dispositivo:")
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
        Presence.INSTALLED -> (if (w.differs) "En el dispositivo (distinto al del servidor)" else "En el dispositivo") to Palette.ok
        Presence.PARTIAL -> "A medias en el dispositivo" to Palette.warn
        Presence.MISSING -> "No está en el dispositivo" to MaterialTheme.colorScheme.onSurfaceVariant
        Presence.TABLET_ONLY -> "Solo en el dispositivo" to Color(0xFF94A3B8)
    }
    Section("") {
        SpacedRow {
            Icon(when (w.presence) {
                Presence.INSTALLED -> Icons.Rounded.CheckCircle
                Presence.PARTIAL -> Icons.Rounded.Downloading
                Presence.MISSING -> Icons.Rounded.CloudDownload
                Presence.TABLET_ONLY -> Icons.Rounded.TabletAndroid
            }, null, tint = color)
            Text(text, color = color, fontWeight = FontWeight.Bold)
        }
        if (w.presence == Presence.MISSING || w.presence == Presence.PARTIAL) {
            val free = Repo.space.free
            val after = free - downloadSize
            Muted("${formatSize(downloadSize)} · quedan ${formatSize(after.coerceAtLeast(0))} libres")
            if (Repo.space.total > 0) UsageBar(listOf(
                (Repo.space.total - free).toFloat() / Repo.space.total to MaterialTheme.colorScheme.primary,
                downloadSize.toFloat() / Repo.space.total to if (after < Prefs.reserveGb * 1_000_000_000L) Palette.danger else Palette.sky,
            ))
        }
    }
}

/**
 * Las partidas de este juego, en cada emulador que lo abre: respaldarlas en el PC o restaurarlas desde un respaldo,
 * sin ir a la pantalla Partidas (que sigue sirviendo para respaldar todo un emulador).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun GameSavesSection(g: Game) {
    val ctx = LocalContext.current
    if (g.system in GameSaves.sharedCards) {
        Section("Partidas") {
            Muted("Las de PS2 van en tarjetas compartidas: se respaldan desde Partidas.")
        }
        return
    }
    var links by remember(g) { mutableStateOf<List<GameSaves.Link>?>(null) }
    var units by remember(g) { mutableStateOf<Map<String, Saves.Unit?>>(emptyMap()) }
    var restoreFor by remember { mutableStateOf<GameSaves.Link?>(null) }
    var deleteFor by remember { mutableStateOf<Pair<GameSaves.Link, Saves.Unit>?>(null) }
    var reload by remember { mutableStateOf(0) }
    // Último respaldo en el servidor que trae este juego (por emulador); null = no se pudo ver (sin conexión).
    var lastBackup by remember(g) { mutableStateOf<Map<String, SaveBackupInfo?>?>(null) }
    LaunchedEffect(g, reload) {
        val ls = GameSaves.links(ctx, g)
        links = ls
        units = ls.associate { l -> l.emu.id to runCatching { Saves.currentUnits(ctx, l.emu).firstOrNull { it.key == l.key } }.getOrNull() }
        if (Prefs.configured) lastBackup = runCatching {
            Pc.io { c -> ls.associate { l -> l.emu.id to Backups.list(c, l.emu.id).firstOrNull { b -> b.keys.isEmpty() || l.key in b.keys } } }
        }.getOrNull()
    }
    val ls = links ?: return
    if (ls.isEmpty()) return
    Section("Partidas") {
        ls.forEach { l ->
            val u = units[l.emu.id]
            val busy = Saves.busy[l.emu.id]
            val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(l.emu.name, fontWeight = FontWeight.SemiBold)
                // Dos estados, cada uno en su línea: en el dispositivo y en el servidor.
                SpacedRow {
                    Icon(Icons.Rounded.TabletAndroid, null, tint = if (u != null) Palette.ok else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when {
                        busy != null -> busy
                        u == null -> "Sin partidas en el dispositivo"
                        else -> "En el dispositivo: ${formatSize(u.bytes)} · jugaste ${formatAgo(u.newest)}"
                    })
                }
                val b = lastBackup?.get(l.emu.id)
                val bAt = b?.let { runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT).parse(it.at)?.time }.getOrNull() }
                val (bOk, bText) = when {
                    !Prefs.configured -> false to "Sin servidor: no hay respaldo"
                    lastBackup == null -> null to "Respaldo: no se pudo revisar (sin conexión)"
                    // Sin partidas no hay nada que respaldar: no es una advertencia.
                    b == null && u == null -> null to "Todavía no hay partidas que respaldar"
                    b == null -> false to "Sin respaldo en el servidor"
                    u != null && bAt != null && u.newest > bAt + 60_000 -> false to "Respaldo desactualizado (${formatAgo(bAt)}): jugaste después"
                    else -> true to "Respaldada en el servidor" + (bAt?.let { " · ${formatAgo(it)}" } ?: "")
                }
                SpacedRow {
                    Icon(Icons.Rounded.CloudDone, null, tint = when (bOk) { true -> Palette.ok; false -> Palette.warn; null -> MaterialTheme.colorScheme.onSurfaceVariant })
                    Text(bText, color = if (bOk == false) Palette.warn else MaterialTheme.colorScheme.onSurface)
                }
                // Aviso: jugaste y hace más de dos semanas que no se respalda (o nunca).
                if (u != null && Prefs.configured && lastBackup != null && (bAt == null || (u.newest > bAt + 60_000 && daysSince(bAt) >= 14)))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.warn.copy(alpha = 0.14f)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Warning, null, tint = Palette.warn)
                        Text(if (bAt == null) "Esta partida nunca se ha respaldado: si algo le pasa al dispositivo, se pierde."
                            else "Hace ${formatAgo(bAt).removePrefix("hace ")} que no respaldas este juego y has seguido jugando.",
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = u != null && busy == null && Prefs.configured, onClick = {
                        appScope.launch {
                            val msg = when (val r = Saves.backup(ctx, l.emu, auto = false, force = true, only = setOf(l.key))) {
                                is Saves.Result.Done -> { Sound.play(Sound.Fx.JINGLE); "Partidas de ${g.title} respaldadas en el servidor" }
                                Saves.Result.Unchanged, Saves.Result.Empty -> "No había nada que respaldar"
                                is Saves.Result.Failed -> r.message
                            }
                            Notice.info(msg)
                            reload++
                        }
                    }) { Text("Respaldar en el servidor") }
                    OutlinedButton(enabled = busy == null && Prefs.configured, onClick = { restoreFor = l }) { Text("Restaurar…") }
                    if (u != null) TextButton(enabled = busy == null, onClick = { Sound.play(Sound.Fx.WARN); deleteFor = l to u },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Borrar del dispositivo…") }
                }
            }
        }
    }
    restoreFor?.let { l -> RestoreDialog(l.emu, onlyKey = l.key) { restoreFor = null; reload++ } }
    deleteFor?.let { (l, u) ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("¿Borrar las partidas de ${g.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("De ${l.emu.name} en este dispositivo (${formatSize(u.bytes)}). El juego no se toca.")
                    if (Prefs.configured) Text("Antes se respaldan en el servidor: las recuperas con Restaurar.", fontWeight = FontWeight.SemiBold)
                    else Text("No hay servidor para respaldarlas: se pierden para siempre.", color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Button(onClick = {
                    deleteFor = null
                    appScope.launch {
                        val err = Saves.deleteUnit(ctx, l.emu, l.key)
                        Notice.info(err ?: "Partidas borradas del dispositivo")
                        reload++
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar partidas") }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancelar") } },
        )
    }
}

/** Vita: el juego se instala DENTRO de Vita3K; el archivo de ROMs es solo el instalador o un acceso directo. */
@Composable
private fun VitaInstallSection(g: Game) {
    if (g.system != "psvita") return
    val ctx = LocalContext.current
    var inst by remember(g) { mutableStateOf<Pair<String, Long>?>(null) }
    var checked by remember(g) { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(g, reload) { inst = runCatching { GameSaves.vitaInstall(ctx, g) }.getOrNull(); checked = true }
    if (!checked) return
    Section("Vita3K") {
        val i = inst
        if (i == null) Muted("No está instalado en Vita3K.")
        else {
            Text("Instalado en Vita3K · ${formatSize(i.second)}", fontWeight = FontWeight.SemiBold, color = Palette.ok)
            Muted("El archivo de ROMs es solo un acceso directo.")
            OutlinedButton(onClick = { ask = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Quitar de Vita3K")
            }
        }
    }
    val i = inst
    if (ask && i != null) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("¿Quitar ${g.title} de Vita3K?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Se liberan ${formatSize(i.second)}.")
                Text("Tus partidas no se borran (están aparte, en ux0/user).", fontWeight = FontWeight.SemiBold)
                Muted("Para volver a jugarlo hay que instalarlo otra vez en Vita3K.")
            }
        },
        confirmButton = {
            Button(onClick = {
                ask = false
                appScope.launch {
                    val msg = runCatching { GameSaves.removeVitaInstall(i.first) }.fold({ "Quitado de Vita3K" }, { it.message ?: "Error" })
                    Notice.info(msg)
                    reload++
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Quitar") }
        },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancelar") } },
    )
}

/** Cómo se llama una versión para elegirla: región o nombre propio, formato y tamaño ("USA · RVZ · 278 MB"). */
private fun versionLabel(v: Game): String = listOfNotNull(
    Names.get(v.id) ?: v.tags.firstOrNull { t -> t.any { it.isLetter() } && t.length <= 24 },
    Group.ext(v.primary).removePrefix(".").uppercase().ifEmpty { "Carpeta" },
    formatSize(v.size),
).joinToString(" · ")

/**
 * Elegir versión: un botón que dice cuál estás viendo y abre la lista (antes eran chips "RVZ · Europe" y no se
 * entendía que eran versiones ni cuál estaba en la tablet).
 */
@Composable
private fun VersionPicker(g: Game, versions: List<Game>) {
    var open by remember { mutableStateOf(false) }
    val advice = remember(versions) { Advice.pick(versions) }
    val best = advice?.first
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Box {
        OutlinedButton(onClick = { open = true }) {
            Column {
                Text("Versión: ${versionLabel(g)}", fontWeight = FontWeight.SemiBold)
                Text("Tienes ${versions.size} versiones de este juego · tocar para cambiar", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ArrowDropDown, null)
        }
        // Lo que antes era la sección "Otras versiones": solo la recomendación, debajo del botón.

        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            versions.sortedBy { it.name }.forEach { v ->
                val p = Repo.where(v).presence
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(versionLabel(v), fontWeight = if (v.id == g.id) FontWeight.Bold else FontWeight.Normal)
                            Muted(listOfNotNull(when (p) {
                                Presence.INSTALLED -> "En el dispositivo"
                                Presence.PARTIAL -> "A medias en el dispositivo"
                                Presence.TABLET_ONLY -> "Solo en el dispositivo"
                                Presence.MISSING -> "Solo en el servidor"
                            }, if (v.id == best?.id) "recomendada" else null, if (v.id == g.id) "la que estás viendo" else null).joinToString(" · "))
                        }
                    },
                    leadingIcon = { if (v.id == g.id) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.width(24.dp)) },
                    onClick = { open = false; if (v.id != g.id) Nav.game = v },
                )
            }
        }
    }
    if (best != null) Muted("Recomendada: ${versionLabel(best)} (${advice.second})" + if (best.id == g.id) " · es esta" else "")
    }
}

/**
 * Switch, en tres líneas claras: la actualización (al día o cuál falta), los DLC (cuántos tienes de los que existen,
 * cuántos están en el dispositivo) y los que te faltan (según titledb; no los tienes ni en el servidor).
 */
@Composable
private fun SwitchSummary(g: Game) {
    val base = (g.parts.firstOrNull { it.kind == "base" }?.titleId ?: g.serial).takeIf { it.length == 16 } ?: return
    LaunchedEffect(base) {
        // Todos los de Switch de una vez: así se baja una sola vez por semana y no por cada ficha.
        SwitchDb.refresh(Repo.allGames().filter { it.kind == "switch" }
            .mapNotNull { gg -> (gg.parts.firstOrNull { it.kind == "base" }?.titleId ?: gg.serial).takeIf { it.length == 16 } }.toSet() + base)
    }
    val info = remember(base, SwitchDb.updatedAt) { SwitchDb.info(base) }
    val dlcs = g.parts.filter { it.kind == "dlc" }
    val dlcOnDevice = dlcs.count { p -> p.files.all { Repo.has(it.path) } }
    val update = g.parts.filter { it.kind == "update" }.maxByOrNull { it.version }
    var showMissing by remember { mutableStateOf(false) }
    Section("Actualizaciones y DLC") {
        @Composable
        fun Line(ok: Boolean?, title: String, detail: String) = Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(when (ok) { true -> Icons.Rounded.CheckCircle; false -> Icons.Rounded.Warning; null -> Icons.Rounded.Info }, null,
                tint = when (ok) { true -> Palette.ok; false -> Palette.warn; null -> MaterialTheme.colorScheme.onSurfaceVariant })
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (detail.isNotBlank()) Muted(detail)
            }
        }
        val have = update?.version ?: 0L
        val updOnDevice = update?.files?.all { Repo.has(it.path) } == true
        when {
            info == null -> Line(null, "Actualización: " + (update?.let { SwitchDb.label(it.version) } ?: "ninguna"),
                if (SwitchDb.busy) "Buscando cuál es la última…" else "Sin internet para ver si hay una más nueva")
            info.latest == 0L -> Line(true, "No tiene actualizaciones", "")
            have >= info.latest -> Line(true, "Actualizado: ${SwitchDb.label(have)}",
                if (updOnDevice) "En el dispositivo" else "En el servidor (márcala abajo para descargarla)")
            else -> Line(false, "Falta la actualización ${SwitchDb.label(info.latest)}",
                if (update != null) "Tienes ${SwitchDb.label(have)}: busca la nueva y déjala en el servidor" else "No tienes ninguna actualización")
        }
        val total = info?.dlcs?.size ?: 0
        val owned = dlcs.map { it.titleId.uppercase() }.toSet()
        val missing = info?.dlcs.orEmpty().filter { it.uppercase() !in owned }
        val where = when {
            dlcs.isEmpty() -> ""
            dlcOnDevice == dlcs.size -> if (dlcs.size == 1) "En el dispositivo" else "Todos en el dispositivo"
            dlcOnDevice == 0 -> "En el servidor (márcalos abajo para descargarlos)"
            else -> "$dlcOnDevice en el dispositivo · ${dlcs.size - dlcOnDevice} en el servidor"
        }
        if (total > 0) Line(missing.isEmpty(), "DLC: tienes ${dlcs.size} de $total", where)
        // Juegos nuevos: titledb todavía no sabe cuántos DLC existen; se dice eso en vez de un ícono sin explicación.
        else if (dlcs.isNotEmpty()) Line(true, "DLC: tienes ${dlcs.size}", where +
            (if (info != null) " · Todavía no se sabe cuántos existen (juego muy nuevo)" else ""))
        else if (info != null) Line(true, "No tiene DLC", "")
        if (missing.isNotEmpty()) {
            SpacedRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showMissing = !showMissing }.padding(vertical = 4.dp)) {
                Text("Te faltan ${missing.size} DLC (no están ni en el servidor)", color = Palette.warn, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Icon(if (showMissing) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (showMissing) Muted(missing.joinToString("  ·  "))
        }
    }
}

/** Actualizaciones o DLC de este juego que están en la tablet pero no en el PC: copiarlos para que queden guardados. */
@Composable
private fun TabletExtras(g: Game) {
    val extras = Repo.tabletExtras[g.id].orEmpty()
    if (extras.isEmpty()) return
    val ctx = LocalContext.current
    val files = extras.flatMap { it.files }
    val key = "extras:${g.id}"
    val up = PcEdit.uploading[key]
    Section("Solo en el dispositivo") {
        extras.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(partNames[p.kind] + (if (p.version > 0) " · ${SwitchDb.label(p.version)}" else ""), fontWeight = FontWeight.SemiBold)
                    Muted(p.titleId)
                }
                Text(formatSize(p.size))
                Spacer(Modifier.width(12.dp))
                Pill("Solo en el dispositivo", Palette.warn)
            }
        }
        if (up != null) {
            LinearProgressIndicator(progress = { up.toFloat() / files.sumOf { it.size }.coerceAtLeast(1) }, Modifier.fillMaxWidth())
            Muted("Copiando al servidor: ${formatSize(up)} de ${formatSize(files.sumOf { it.size })}")
        } else if (Prefs.configured) Button(onClick = {
            appScope.launch {
                val msg = runCatching { PcEdit.uploadFiles(key, files) }.fold({ "Copiado al servidor" }, { "No se pudo copiar: ${it.message}" })
                Notice.info(msg)
                Repo.refresh()
            }
        }) { Text("Copiar al servidor · ${formatSize(files.sumOf { it.size })}") }
    }
}

/** Borrar, con la misma forma que Descargar (pero con borde rojo, para no confundirlos). */
@Composable
fun BigDeleteButton(modifier: Modifier = Modifier, autoFocus: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    val red = MaterialTheme.colorScheme.error
    // Con mando, si el juego ya está en el dispositivo el foco empieza aquí (lo que tiene sentido hacer).
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(autoFocus) { if (autoFocus && (Nav.keys || Nav.pad)) runCatching { focus.requestFocus() } }
    Row(
        modifier.height(64.dp).focusRequester(focus).focusHighlight(shape, grow = 1.02f).clip(shape).background(red.copy(alpha = 0.08f))
            .border(2.dp, red.copy(alpha = 0.6f), shape).pressable { onClick() }.padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Delete, null, tint = red, modifier = Modifier.size(26.dp))
        Text("Borrar…", color = red, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Borrar, cada cosa por separado (antes eran tres casillas juntas y no se entendía qué pasaba con qué):
 * 1) el juego del dispositivo (las partidas no se tocan), 2) sus partidas del dispositivo, y 3) aparte y en rojo, del
 * servidor. Cada tarjeta tiene su botón y lleva al paso "¿Seguro?" con solo esa acción.
 */
@Composable
private fun DeleteDialog(g: Game, w: Repo.Where, onClose: () -> Unit, onGone: () -> Unit) {
    val ctx = LocalContext.current
    val onDevice = w.presence != Presence.MISSING
    val onServer = w.presence != Presence.TABLET_ONLY && Prefs.configured
    var withSaves by remember { mutableStateOf<List<Pair<GameSaves.Link, Saves.Unit>>>(emptyList()) }
    LaunchedEffect(g.id) {
        withSaves = runCatching {
            GameSaves.links(ctx, g).mapNotNull { l -> Saves.currentUnits(ctx, l.emu).firstOrNull { it.key == l.key }?.let { l to it } }
        }.getOrDefault(emptyList())
    }
    // Qué se confirma: "dev", "saves" o "srv".
    var confirm by remember { mutableStateOf<String?>(null) }
    confirm?.let { what ->
        DeleteConfirm(g, w, what == "dev", what == "srv", what == "saves", onDevice, onServer, withSaves,
            onBack = { confirm = null }, onClose = onClose, onGone = onGone)
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Borrar ${g.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                @Composable
                fun Card(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, action: String,
                         danger: Boolean, enabled: Boolean, onClick: () -> Unit) {
                    val red = MaterialTheme.colorScheme.error
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (danger) red.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(icon, null, tint = if (danger) red else MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            Muted(detail)
                        }
                        if (enabled) OutlinedButton(onClick = { Sound.play(Sound.Fx.WARN); onClick() }, modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = red)) { Text(action) }
                    }
                }
                Card(Icons.Rounded.TabletAndroid, "Del dispositivo",
                    if (!onDevice) "No está en el dispositivo"
                    else "Libera ${formatSize(w.localSize)}. Tus partidas se quedan (se borran aparte, en Partidas)." +
                        if (onServer) " Lo vuelves a descargar cuando quieras." else "",
                    "Quitar", false, onDevice) { confirm = "dev" }
                if (Prefs.configured) {
                    Text("Servidor", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp))
                    Card(Icons.Rounded.Dns, "Del servidor",
                        if (!onServer) "No está en el servidor"
                        else "Se borra ${formatSize(g.size)} del PC, sin papelera." + if (!onDevice) " No te quedará en ningún lado." else "",
                        "Borrar del servidor", true, onServer) { confirm = "srv" }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
    )
}

/**
 * Segundo paso de Borrar: en palabras simples qué va a pasar con el juego y con las partidas. Si algo se pierde para
 * siempre (no queda ninguna copia, o no hay servidor para respaldar las partidas), lo dice en rojo y pide marcar que se
 * entiende.
 */
@Composable
private fun DeleteConfirm(
    g: Game, w: Repo.Where, dev: Boolean, srv: Boolean, saves: Boolean, onDevice: Boolean, onServer: Boolean,
    withSaves: List<Pair<GameSaves.Link, Saves.Unit>>, onBack: () -> Unit, onClose: () -> Unit, onGone: () -> Unit,
) {
    val ctx = LocalContext.current
    val gameLost = (dev || !onDevice) && (srv || !onServer) && (dev || srv)
    val savesLost = saves && !Prefs.configured
    var sure by remember { mutableStateOf(false) }
    val risky = gameLost || savesLost
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("¿Seguro?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @Composable
                fun Line(ok: Boolean, text: String) = Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null,
                        tint = if (ok) Palette.ok else MaterialTheme.colorScheme.error)
                    Text(text, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
                if (dev) Line(true, "Se borra del dispositivo (${formatSize(w.localSize)} libres)." +
                    if (onServer && !srv) " Sigue en el servidor: lo puedes volver a descargar cuando quieras." else "")
                if (srv) Line(!gameLost, "Se borra del servidor (${formatSize(g.size)}). No va a la papelera." +
                    if (onDevice && !dev) " Sigue en el dispositivo." else "")
                if (gameLost) Line(false, "No vas a tener este juego en ningún lado.")
                if (saves) {
                    if (Prefs.configured) Line(true, "Se borran sus partidas del dispositivo (" +
                        withSaves.joinToString(", ") { it.first.emu.name } + "). Antes se respaldan en el servidor: las recuperas " +
                        "en la ficha del juego → Partidas → Restaurar.")
                    else Line(false, "Se borran sus partidas y no hay servidor para respaldarlas: se pierden para siempre.")
                } else if (withSaves.isNotEmpty()) Line(true, "Tus partidas se quedan en el dispositivo.")
                if (risky) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { sure = !sure },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sure, { sure = it })
                    Text("Entiendo que no se puede deshacer", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(enabled = !risky || sure, onClick = {
                onClose()
                appScope.launch {
                    val msgs = ArrayList<String>()
                    if (saves) withSaves.forEach { (l, _) -> Saves.deleteUnit(ctx, l.emu, l.key)?.let { msgs += it } }
                    var failed: String? = null
                    var freed = 0L
                    if (dev && onDevice) freed += Repo.delete(g)
                    if (srv && onServer) runCatching { freed += Repo.deleteFromPc(g) }.onFailure { failed = it.message }
                    Sound.play(if (failed == null) Sound.Fx.DONE else Sound.Fx.WARN)
                    // Aviso arriba a la derecha con la carátula yéndose al basurero (antes era un Toast).
                    if (failed != null) Notice.show(g.id, g.title, "No se pudo borrar del servidor: $failed", Notice.Kind.ERROR)
                    else Notice.show(g.id, g.title, (if (srv) "Borrado del servidor" else "Quitado del dispositivo") +
                        " · ${formatSize(freed)} libres", Notice.Kind.DELETE)
                    if (gameLost) onGone()
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar") }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("Atrás") } },
    )
}

/** Editar el juego: nombre y las tres imágenes (carátula, fondo, logo), cada una con su vista previa. */
@Composable
private fun GameEditor(g: Game, onClose: () -> Unit) {
    var name by remember { mutableStateOf(Names.get(g.id) ?: g.title) }
    var pick by remember { mutableStateOf<String?>(null) }
    pick?.let { k -> ArtPicker(g, k) { pick = null }; return }
    fun save() {
        if (name.trim() != (Names.get(g.id) ?: g.title)) Repo.rename(g, if (name.isBlank()) "" else name)
        onClose()
        Nav.game = Repo.game(g.id) ?: g
    }
    ReviewSheet("Editar ${g.title}", "El nombre y las imágenes se guardan en este dispositivo; los archivos no cambian.",
        onClose = onClose,
        footer = {
            TextButton(onClick = { Art.forget(g.id); Art.startFetchAll(listOf(g), quiet = true); Notice.info("Buscando las imágenes de nuevo") }) {
                Text("Buscar todas de nuevo")
            }
            Spacer(Modifier.weight(1f))
            BigActionButton("Listo", Icons.Rounded.Check) { save() }
        },
    ) {
        // Vista previa: cómo se ve en la ficha (fondo, logo y carátula juntos).
        item {
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(Repo.system(g.system).color), Color(0xFF0D1220))))) {
                ArtImage(g, "hero", Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xAA000000), Color.Transparent))))
                Row(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cover(g, Modifier.width(110.dp), showTitle = false)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(260.dp, 70.dp)) { ArtImage(g, "logo", Modifier.fillMaxSize(), ContentScale.Fit, Alignment.BottomStart) }
                        Text(name.ifBlank { g.title }, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            SheetCard {
                Text("Nombre", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().gamepadText())
                SpacedRow {
                    Muted("Archivo: ${g.name}", Modifier.weight(1f))
                    TextButton(onClick = { name = g.title.takeIf { Names.get(g.id) == null } ?: Group.cleanTitle(g.name).first; Repo.rename(g, "") }) {
                        Text("Nombre original")
                    }
                }
            }
        }
        item {
            SheetCard {
                Text("Imágenes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(Triple("grid", "Carátula", 2f / 3f), Triple("icon", "Ícono", 1f), Triple("hero", "Fondo", 1.8f), Triple("logo", "Logo", 1.8f))
                        .forEach { (k, label, ratio) ->
                            Column(Modifier.weight(if (k == "grid" || k == "icon") 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val file = artFile(g, k)
                                Box(Modifier.fillMaxWidth().aspectRatio(ratio).focusHighlight(RoundedCornerShape(14.dp), grow = 1.03f)
                                    .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                    .pressable { Sound.play(Sound.Fx.OPEN); pick = k }, contentAlignment = Alignment.Center) {
                                    when {
                                        file != null -> ArtImage(g, k, Modifier.fillMaxSize(), if (k == "logo") ContentScale.Fit else ContentScale.Crop)
                                        Art.notFound(g.id, k) -> Muted("Sin imagen")
                                        else -> SkeletonRow(Modifier.fillMaxSize())
                                    }
                                }
                                SpacedRow {
                                    Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                }
                Muted("Toca una para elegir otra de SteamGridDB. También puedes subir las tuyas desde la Web.")
            }
        }
    }
}

/**
 * El botón de descargar de la ficha, como el de la eShop: grande, naranja con brillo, el tamaño a la vista, y con foco y
 * sonido para el control.
 */
@Composable
fun BigDownloadButton(label: String, size: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    val accent = Color(UiState.accent)
    val brush = androidx.compose.ui.graphics.SolidColor(accent)
    // Con control, al abrir la ficha el foco cae directo aquí (sin recorrer toda la pantalla).
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (Nav.keys || Nav.pad) { kotlinx.coroutines.delay(30); runCatching { focus.requestFocus() } } }
    Row(
        modifier.height(64.dp).focusRequester(focus).focusHighlight(shape, grow = 1.02f).clip(shape).background(brush)
            .border(2.dp, Color.White.copy(alpha = .35f), shape)
            .pressable { onClick() } // clickable ya recibe el foco y responde a A / Enter
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(30.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f))
        Box(Modifier.clip(shape).background(Color.White.copy(alpha = .25f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(size, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

/** Cancelar una descarga pide confirmación: se borra lo bajado y no se puede deshacer. */
@Composable
fun CancelDownloadDialog(d: Download, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("¿Cancelar la descarga?") },
        text = { Text("Se borra lo que ya se bajó de ${d.title} (${formatSize(d.done)}). Si solo quieres detenerla un rato, usa Pausar.") },
        confirmButton = {
            Button(onClick = { Sound.play(Sound.Fx.WARN); DownloadService.cancel(d); onClose() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Cancelar descarga") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Seguir descargando") } },
    )
}

/**
 * Borrar una sola cosa, directo al "¿Seguro?": "dev" quita el juego del dispositivo (botón grande), "srv" lo borra
 * del servidor (menú ⋮). Las partidas se borran aparte, en la sección Partidas de la ficha.
 */
@Composable
private fun DeleteNow(g: Game, w: Repo.Where, what: String, onClose: (gone: Boolean) -> Unit) {
    val onDevice = w.presence != Presence.MISSING
    val onServer = w.presence != Presence.TABLET_ONLY && Prefs.configured
    val ctx = LocalContext.current
    var withSaves by remember { mutableStateOf<List<Pair<GameSaves.Link, Saves.Unit>>>(emptyList()) }
    LaunchedEffect(g.id) {
        withSaves = runCatching {
            GameSaves.links(ctx, g).mapNotNull { l -> Saves.currentUnits(ctx, l.emu).firstOrNull { it.key == l.key }?.let { l to it } }
        }.getOrDefault(emptyList())
    }
    DeleteConfirm(g, w, dev = what == "dev", srv = what == "srv", saves = false, onDevice = onDevice, onServer = onServer,
        withSaves = withSaves, onBack = { onClose(false) }, onClose = { onClose(false) }, onGone = { onClose(true) })
}

/** Con mando, este botón toma el foco al aparecer (por ejemplo Pausar al empezar una descarga: si no, el foco se perdía). */
@Composable
fun Modifier.autoFocusPad(): Modifier {
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { if (Nav.keys || Nav.pad) { kotlinx.coroutines.delay(120); runCatching { fr.requestFocus() } } }
    return this.focusRequester(fr)
}
