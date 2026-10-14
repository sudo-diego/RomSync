package io.github.sudodiego.romsync

import android.content.Context
import android.content.SharedPreferences

/**
 * Nombres propios para juegos ("Wind Waker HD Plus" en vez de "Legend of Zelda, The - The Wind Waker"). Se usan
 * en la tienda, en la web y para buscar la carátula en SteamGridDB. No cambian el archivo.
 */
object Names {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences("names", Context.MODE_PRIVATE)
    }

    fun get(id: String): String? = sp?.getString(id, null)?.takeIf { it.isNotBlank() }

    fun set(id: String, name: String) {
        sp?.edit()?.apply { if (name.isBlank()) remove(id) else putString(id, name.trim()) }?.apply()
    }

    /** El nombre que se muestra: el propio; si no, el oficial de SteamGridDB (si coincide con el archivo); si no, el del archivo. */
    fun apply(games: List<Game>): List<Game> = games.map { g ->
        g.copy(title = get(g.id) ?: Art.officialName(g.id) ?: Group.cleanTitle(g.name).first)
    }
}
