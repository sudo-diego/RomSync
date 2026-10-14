package io.github.sudodiego.romsync

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

@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val items = DownloadService.items
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SpacedRow(Modifier.fillMaxWidth()) {
                Text("Descargas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (items.any { it.status == DlStatus.DONE }) TextButton(onClick = { DownloadService.clearDone() }) { Text("Quitar terminadas") }
            }
        }
        if (items.isEmpty()) item {
            Section("") { Muted("Nada descargando.") }
        }
        items(items.toList().reversed(), key = { it.id + it.status }) { d ->
            Section("") {
                SpacedRow {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, fontWeight = FontWeight.Bold)
                        Muted(when (d.status) {
                            DlStatus.QUEUED -> "En cola · ${formatSize(d.total)}"
                            DlStatus.RUNNING -> "${formatSize(d.done)} de ${formatSize(d.total)} · ${formatSpeed(d.speed)} · ${formatEta(d.total - d.done, d.speed)}"
                            DlStatus.PAUSED -> "En pausa · ${formatSize(d.done)} de ${formatSize(d.total)}"
                            DlStatus.FAILED -> "Falló: ${d.error}"
                            DlStatus.DONE -> "Listo · ${formatSize(d.total)}"
                        })
                    }
                    when (d.status) {
                        DlStatus.RUNNING, DlStatus.QUEUED -> FilledTonalButton(onClick = { DownloadService.pause(d) }) { Text("Pausar") }
                        DlStatus.PAUSED, DlStatus.FAILED -> FilledTonalButton(onClick = { DownloadService.resume(ctx, d) }) {
                            Text(if (d.status == DlStatus.FAILED) "Reintentar" else "Seguir")
                        }
                        DlStatus.DONE -> {}
                    }
                    if (d.status != DlStatus.DONE) TextButton(onClick = { DownloadService.cancel(d) }) { Text("Cancelar") }
                }
                if (d.status != DlStatus.DONE) LinearProgressIndicator(
                    progress = { d.done.toFloat() / d.total.coerceAtLeast(1) }, Modifier.fillMaxWidth())
                if (d.status == DlStatus.RUNNING && d.current.isNotEmpty()) Muted(d.current, Modifier.width(600.dp))
            }
        }
    }
}
