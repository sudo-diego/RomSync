package io.github.sudodiego.romsync

import java.util.Locale

/** "4,2 GB", "850 MB": con coma, como en la web. */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    val text = if (i >= 2) String.format(Locale("es"), if (v >= 100) "%.0f" else "%.1f", v) else String.format(Locale("es"), "%.0f", v)
    return "$text ${units[i]}"
}

fun formatSpeed(bps: Long) = if (bps <= 0) "" else formatSize(bps) + "/s"

fun formatEta(remaining: Long, bps: Long): String {
    if (bps <= 0) return ""
    val s = remaining / bps
    return when {
        s < 60 -> "menos de 1 min"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

fun formatAgo(ms: Long): String {
    if (ms <= 0) return "nunca"
    val m = (System.currentTimeMillis() - ms) / 60000
    return when {
        m < 1 -> "recién"
        m < 60 -> "hace $m min"
        m < 48 * 60 -> "hace ${m / 60} h"
        else -> "hace ${m / 1440} días"
    }
}
