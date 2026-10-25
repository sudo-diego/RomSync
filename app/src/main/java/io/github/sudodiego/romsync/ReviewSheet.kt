package io.github.sudodiego.romsync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Hoja grande para revisar una lista antes de cambiar algo (ordenar, repetidos, copiar al servidor): encabezado con
 * qué es, el contenido en tarjetas y abajo, siempre a la vista, el resumen y el botón principal. Antes eran diálogos
 * chicos con una lista larga y apretada.
 */
@Composable
fun ReviewSheet(
    title: String, subtitle: String, onClose: () -> Unit,
    footer: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (subtitle.isNotBlank()) Muted(subtitle)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Cerrar") }
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) { footer() }
            }
        }
    }
}

/** Una tarjeta de la hoja (fondo de tarjeta, esquinas redondeadas). */
@Composable
fun SheetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/**
 * Encabezado de un grupo: ícono de color, nombre, cuántos y cuánto pesan, casilla para marcar el grupo entero y flecha
 * para plegarlo.
 */
@Composable
fun GroupHeader(icon: ImageVector, color: Color, title: String, detail: String, state: ToggleableState?,
                expanded: Boolean, onToggle: () -> Unit, onExpand: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onExpand() }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Muted(detail)
        }
        if (state != null) TriStateCheckbox(state, onClick = onToggle)
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
    }
}

/** Fila marcable dentro de una tarjeta. */
@Composable
fun CheckRow(checked: Boolean?, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (checked != null) Modifier.clickable { onToggle() } else Modifier)
        .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (checked != null) Checkbox(checked, { onToggle() }) else Box(Modifier.size(48.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

fun toggleState(ids: Collection<String>, selected: Set<String>): ToggleableState = when {
    ids.isNotEmpty() && ids.all { it in selected } -> ToggleableState.On
    ids.any { it in selected } -> ToggleableState.Indeterminate
    else -> ToggleableState.Off
}

