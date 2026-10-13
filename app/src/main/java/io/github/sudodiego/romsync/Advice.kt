package io.github.sudodiego.romsync

/** Consejos para elegir entre versiones repetidas de un juego (formato). */
object Advice {
    private val archive = setOf(".zip", ".7z", ".rar")
    private val zipNative = setOf("arcade", "mame", "fbneo", "neogeo", "cps1", "cps2", "cps3", "naomi", "atomiswave")

    /** Puntaje del formato (más alto = mejor) y por qué. */
    fun format(g: Game): Pair<Int, String> {
        val sys = g.system.lowercase()
        val e = if (g.kind == "folder") "carpeta" else Group.ext(g.primary)
        return when {
            sys == "wiiu" -> when (e) {
                ".wua" -> 3 to "WUA: un solo archivo con el juego, la actualización y los DLC; es el formato recomendado por Cemu"
                ".wud", ".wux" -> 2 to "imagen del disco"
                "carpeta" -> 1 to "carpeta suelta (miles de archivos, más lenta de copiar)"
                else -> 0 to "formato poco común"
            }
            sys == "n3ds" || sys == "3ds" -> when (e) {
                ".3ds", ".cci" -> 3 to "se abre directo en Azahar, sin instalar"
                ".cia" -> 2 to "hay que instalarlo en Azahar (ocupa doble mientras tanto)"
                in archive -> 0 to "comprimido: el emulador tiene que descomprimirlo"
                else -> 1 to "formato poco común"
            }
            sys == "gc" || sys == "wii" -> when (e) {
                ".rvz" -> 3 to "RVZ: comprimido sin perder nada, ocupa mucho menos"
                ".iso", ".gcm", ".wbfs" -> 2 to "imagen sin comprimir (ocupa más)"
                in archive -> 0 to "comprimido: Dolphin no lo abre directo"
                else -> 1 to "formato poco común"
            }
            g.kind == "multi" || g.kind == "discs" || e == ".chd" -> 3 to (if (e == ".chd") "CHD: comprimido sin perder nada" else "varios discos juntos")
            e in archive && sys !in zipNative -> 0 to "comprimido: muchos emuladores no lo abren directo"
            e == ".iso" || e == ".cue" || e == ".bin" -> 2 to "imagen sin comprimir (ocupa más)"
            else -> 2 to ""
        }
    }

    /** "Conviene quedarse con …" para un grupo de versiones; null si no hay una mejor clara. */
    fun pick(all: List<Game>): Pair<Game, String>? {
        val scored = all.map { it to format(it) }.sortedByDescending { it.second.first }
        val best = scored.first()
        if (scored.size < 2 || best.second.first == scored[1].second.first) return null
        return best.first to best.second.second
    }
}
