package io.github.sudodiego.romsync

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Descargas, como la cola de la eShop: arriba el resumen (cuánto falta, velocidad, tiempo, espacio que queda) con
 * pausar/seguir todo; la que se está bajando en grande, con el fondo del juego; después la cola, las que fallaron y
 * las terminadas (con "Abrir").
 */
@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val all = DownloadService.items.toList()
    val running = all.filter { it.status == DlStatus.RUNNING }
    val queued = all.filter { it.status == DlStatus.QUEUED || it.status == DlStatus.PAUSED }
    val failed = all.filter { it.status == DlStatus.FAILED }
    val done = all.filter { it.status == DlStatus.DONE }.reversed()
    val pending = running + queued
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Descargas", style = MaterialTheme.typography.headlineLarge) }
        if (all.isEmpty()) item {
            Section("") {
                SpacedRow {
                    Icon(Icons.Rounded.CloudDone, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                    Column {
                        Text("Nada descargando", style = MaterialTheme.typography.titleLarge)
                        Muted("Lo que descargues desde la Tienda aparece aquí.")
                    }
                }
            }
        }
        if (pending.isNotEmpty()) item {
            val left = pending.sumOf { it.total - it.done }
            val speed = running.sumOf { it.speed }
            Section("") {
                SpacedRow {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Faltan ${formatSize(left)}" + (if (speed > 0) " · ${formatEta(left, speed)}" else ""),
                            style = MaterialTheme.typography.titleLarge)
                        Muted(listOfNotNull(
                            "${pending.size} en la cola",
                            formatSpeed(speed).ifBlank { null },
                            "quedarán ${formatSize((Repo.space.free - left).coerceAtLeast(0))} libres",
                        ).joinToString(" · "))
                    }
                    val anyRunning = pending.any { it.status != DlStatus.PAUSED }
                    FilledTonalButton(onClick = {
                        Sound.play(Sound.Fx.MOVE)
                        if (anyRunning) pending.forEach { DownloadService.pause(it) } else pending.forEach { DownloadService.resume(ctx, it) }
                    }, modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp))) {
                        Icon(if (anyRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp))
                        Text(if (anyRunning) "Pausar todo" else "Seguir todo")
                    }
                }
            }
        }
        items(running, key = { "r-" + it.id }) { d -> NowDownloading(d) }
        if (queued.isNotEmpty()) item { Text("En la cola", style = MaterialTheme.typography.titleLarge) }
        items(queued, key = { "q-" + it.id }) { d -> DownloadRow(d) }
        if (failed.isNotEmpty()) item { Text("Con problemas", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error) }
        items(failed, key = { "f-" + it.id }) { d -> DownloadRow(d) }
        if (done.isNotEmpty()) item {
            SpacedRow(Modifier.fillMaxWidth()) {
                Text("Terminadas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { DownloadService.clearDone() }) { Text("Quitar terminadas") }
            }
        }
        items(done, key = { "d-" + it.id }) { d -> DownloadRow(d) }
    }
}

/** La que se está bajando: grande, con el fondo del juego, porcentaje, velocidad, tiempo y archivo actual. */
@Composable
private fun NowDownloading(d: Download) {
    val g = Repo.game(d.id)
    var ask by remember { mutableStateOf(false) }
    if (ask) CancelDownloadDialog(d) { ask = false }
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(26.dp))
        .background(Brush.linearGradient(listOf(Color(Repo.system(d.system).color), Color(0xFF0D1220))))) {
        if (g != null) ArtImage(g, "hero", Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE6000000), Color(0x66000000)))))
        Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (g != null) Cover(g, Modifier.width(120.dp), showTitle = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Descargando", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                Text(d.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                val frac = d.done.toFloat() / d.total.coerceAtLeast(1)
                SpacedRow {
                    LinearProgressIndicator(progress = { frac }, Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(99.dp)),
                        color = Color(UiState.accent), trackColor = Color.White.copy(alpha = 0.25f))
                    Text("${(frac * 100).toInt()} %", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text("${formatSize(d.done)} de ${formatSize(d.total)}" +
                    listOf(formatSpeed(d.speed), formatEta(d.total - d.done, d.speed)).filter { it.isNotBlank() }.joinToString(" · ", prefix = " · "),
                    color = Color.White.copy(alpha = 0.9f))
                if (d.current.isNotEmpty()) Text(d.current.substringAfterLast('/'), color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { Sound.play(Sound.Fx.MOVE); DownloadService.pause(d) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp))) {
                    Icon(Icons.Rounded.Pause, null); Spacer(Modifier.width(6.dp)); Text("Pausar")
                }
                TextButton(onClick = { Sound.play(Sound.Fx.WARN); ask = true }, modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp)),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Cancelar") }
            }
        }
    }
}

/** Una fila de la cola, de las que fallaron o de las terminadas: miniatura, estado y sus botones. */
@Composable
private fun DownloadRow(d: Download) {
    val ctx = LocalContext.current
    val g = Repo.game(d.id)
    var ask by remember { mutableStateOf(false) }
    if (ask) CancelDownloadDialog(d) { ask = false }
    Section("") {
        SpacedRow {
            if (g != null) Cover(g, Modifier.width(56.dp), showTitle = false)
            Column(Modifier.weight(1f)) {
                Text(d.title, fontWeight = FontWeight.Bold)
                Muted(when (d.status) {
                    DlStatus.QUEUED -> "En espera · ${formatSize(d.total)}"
                    DlStatus.PAUSED -> "En pausa · ${formatSize(d.done)} de ${formatSize(d.total)}"
                    DlStatus.FAILED -> "Falló: ${d.error}"
                    DlStatus.DONE -> "Listo · ${formatSize(d.total)}"
                    DlStatus.RUNNING -> "${formatSize(d.done)} de ${formatSize(d.total)}"
                })
                if (d.status == DlStatus.PAUSED) LinearProgressIndicator(progress = { d.done.toFloat() / d.total.coerceAtLeast(1) },
                    Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            when (d.status) {
                DlStatus.QUEUED -> FilledTonalButton(onClick = { DownloadService.pause(d) }) { Text("Pausar") }
                DlStatus.PAUSED, DlStatus.FAILED -> FilledTonalButton(onClick = { Sound.play(Sound.Fx.MOVE); DownloadService.resume(ctx, d) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp))) { Text(if (d.status == DlStatus.FAILED) "Reintentar" else "Seguir") }
                DlStatus.DONE -> if (g != null) FilledTonalButton(onClick = { Sound.play(Sound.Fx.OPEN); Nav.game = g },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp))) { Text("Abrir") }
                DlStatus.RUNNING -> {}
            }
            if (d.status != DlStatus.DONE) TextButton(onClick = { Sound.play(Sound.Fx.WARN); ask = true },
                modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp))) { Text("Cancelar") }
        }
    }
}
