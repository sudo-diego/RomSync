package io.github.sudodiego.romsync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import java.io.File

/** Ajustes de la app. Todo en SharedPreferences: son pocos y se leen desde la UI, el servicio y el respaldo. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) sp = context.applicationContext.getSharedPreferences("romsync", Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun put(key: String, value: String) = sp.edit().putString(key, value).apply()

    val storageRoot: String get() = Environment.getExternalStorageDirectory().absolutePath

    // ---- PC (SFTP con el OpenSSH de Windows) ----
    var host: String
        get() = str("host", "")
        set(v) = put("host", v.trim())
    var port: Int
        get() = sp.getInt("port", 22)
        set(v) = sp.edit().putInt("port", v).apply()
    var user: String
        get() = str("user", "")
        set(v) = put("user", v.trim())
    var password: String
        get() = str("password", "")
        set(v) = put("password", v)
    var fingerprint: String
        get() = str("fingerprint", "")
        set(v) = put("fingerprint", v)

    /** Carpeta de ROMs en el PC, como la escribe Windows ("D:\Emulación\ROMs"). */
    var pcRoms: String
        get() = str("pc_roms", "")
        set(v) = put("pc_roms", v.trim())
    /** BIOS en el PC; vacía = "bios" dentro de la carpeta de ROMs. */
    var pcBios: String
        get() = str("pc_bios", "")
        set(v) = put("pc_bios", v.trim())
    /** Dónde guardar los respaldos de partidas en el PC; vacía = "RomSync" junto a la carpeta de ROMs. */
    var pcBackups: String
        get() = str("pc_backups", "")
        set(v) = put("pc_backups", v.trim())

    val configured: Boolean get() = host.isNotBlank() && user.isNotBlank() && pcRoms.isNotBlank()

    fun sftp() = SftpConfig(host, port, user, password, fingerprint)

    val remoteRoms: String get() = SftpConn.remotePath(pcRoms)
    val remoteBios: String get() = if (pcBios.isBlank()) "$remoteRoms/bios" else SftpConn.remotePath(pcBios)
    val remoteBackups: String get() = if (pcBackups.isNotBlank()) SftpConn.remotePath(pcBackups)
        else remoteRoms.substringBeforeLast('/').ifEmpty { remoteRoms } + "/RomSync/respaldos"

    var keepBackups: Int
        get() = sp.getInt("keep_backups", 10)
        set(v) = sp.edit().putInt("keep_backups", v).apply()

    /** Clave de la API de SteamGridDB (las carátulas las baja la tablet). */
    var sgdbKey: String
        get() = str("sgdb_key", "")
        set(v) = put("sgdb_key", v.trim())

    /** Nombre con el que la tablet aparece en el PC (y la carpeta de sus respaldos). */
    var deviceName: String
        get() = str("device", Build.MODEL.ifBlank { "tablet" })
        set(v) = put("device", v.trim().ifBlank { Build.MODEL })

    /** Carpeta de ROMs de la tablet (estructura de ES-DE: una subcarpeta por plataforma). */
    var romsDir: String
        get() = str("roms_dir", defaultRomsDir())
        set(v) = put("roms_dir", v.trim().trimEnd('/'))

    var biosDir: String
        get() = str("bios_dir", "").ifBlank { defaultBiosDir() }
        set(v) = put("bios_dir", v.trim().trimEnd('/'))

    /** Espacio que siempre queda libre (GB): una descarga que lo invada no empieza. */
    var reserveGb: Int
        get() = sp.getInt("reserve_gb", 3)
        set(v) = sp.edit().putInt("reserve_gb", v).apply()

    // Sonido (música de fondo y sonidos de navegación).
    var musicOn: Boolean
        get() = sp.getBoolean("music_on", true)
        set(v) = sp.edit().putBoolean("music_on", v).apply()
    var sfxOn: Boolean
        get() = sp.getBoolean("sfx_on", true)
        set(v) = sp.edit().putBoolean("sfx_on", v).apply()
    /** "bossa", "lofi", "retro" o "propia". */
    var musicTrack: String
        get() = sp.getString("music_track", "bossa") ?: "bossa"
        set(v) = sp.edit().putString("music_track", v).apply()
    var customMusic: String
        get() = sp.getString("custom_music", "") ?: ""
        set(v) = sp.edit().putString("custom_music", v).apply()
    var customMusicName: String
        get() = sp.getString("custom_music_name", "") ?: ""
        set(v) = sp.edit().putString("custom_music_name", v).apply()
    /** Juego de sonidos: "clasico", "suave" o "retro". */
    var sfxPack: String
        get() = sp.getString("sfx_pack", "clasico") ?: "clasico"
        set(v) = sp.edit().putString("sfx_pack", v).apply()
    var musicVolume: Int
        get() = sp.getInt("music_volume", 45)
        set(v) = sp.edit().putInt("music_volume", v).apply()
    var sfxVolume: Int
        get() = sp.getInt("sfx_volume", 70)
        set(v) = sp.edit().putInt("sfx_volume", v).apply()
    var accent: Long
        get() = sp.getLong("accent", 0xFFFF7A1AL)
        set(v) = sp.edit().putLong("accent", v).apply()
    /** Apps cuyo espacio se muestra en Organizar (null = aún no elegidas: se usan las conocidas que estén instaladas). */
    var watchedApps: Set<String>?
        get() = sp.getStringSet("watchedApps", null)
        set(v) = sp.edit().putStringSet("watchedApps", v).apply()
    fun emuFor(system: String): String? = sp.getString("emu:$system", null)
    fun setEmuFor(system: String, pkg: String) = sp.edit().putString("emu:$system", pkg).apply()
    var icons: Boolean
        get() = sp.getBoolean("icons", false)
        set(v) = sp.edit().putBoolean("icons", v).apply()
    var background: String
        get() = sp.getString("background", "burbujas") ?: "burbujas"
        set(v) = sp.edit().putString("background", v).apply()
    var sidebarOpen: Boolean
        get() = sp.getBoolean("sidebar_open", true)
        set(v) = sp.edit().putBoolean("sidebar_open", v).apply()
    /** "claro" (estilo eShop de Wii U / 3DS) u "oscuro". */
    var theme: String
        get() = sp.getString("theme", "claro") ?: "claro"
        set(v) = sp.edit().putString("theme", v).apply()

    var autoBackup: Boolean
        get() = sp.getBoolean("auto_backup", true)
        set(v) = sp.edit().putBoolean("auto_backup", v).apply()

    /** Cada cuántas horas intenta el respaldo automático (solo con WiFi y el PC encendido). */
    /** Cuándo salió bien el último respaldo automático (si falla, se reintenta cada hora hasta que salga). */
    var lastAutoBackupOk: Long
        get() = sp.getLong("last_auto_ok", 0)
        set(v) = sp.edit().putLong("last_auto_ok", v).apply()

    /** Qué pasó en el último intento (para mostrarlo en Partidas). */
    var lastAutoBackupNote: String
        get() = sp.getString("last_auto_note", "") ?: ""
        set(v) = sp.edit().putString("last_auto_note", v).apply()

    var autoBackupHours: Int
        get() = sp.getInt("auto_backup_hours", 12)
        set(v) = sp.edit().putInt("auto_backup_hours", v).apply()

    /** Ruta elegida a mano para los datos de un emulador (vacía = la detectada). */
    fun emuPath(id: String): String = str("emu_path_$id", "")
    fun setEmuPath(id: String, path: String) = put("emu_path_$id", path.trim().trimEnd('/'))

    fun lastBackupHash(emu: String): String = str("last_hash_$emu", "")
    fun lastBackupAt(emu: String): Long = sp.getLong("last_at_$emu", 0)
    fun setLastBackup(emu: String, hash: String, at: Long) =
        sp.edit().putString("last_hash_$emu", hash).putLong("last_at_$emu", at).apply()

    // TitleID leído de cada .nsp sin código en el nombre ("ruta|tamaño" → id; "" = no se pudo).
    fun flag(key: String) = sp.getBoolean("flag:$key", false)
    fun setFlag(key: String, v: Boolean) = sp.edit().putBoolean("flag:$key", v).apply()
    fun switchId(key: String): String? = sp.getString("tid:$key", null)
    fun setSwitchId(key: String, id: String) = sp.edit().putString("tid:$key", id).apply()

    /** Juegos con varias versiones que el usuario quiere tener todas (claves de Repo.versionKey). */
    var keepAllVersions: Set<String>
        get() = sp.getStringSet("keep_versions", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("keep_versions", v).apply()

    var welcomeDone: Boolean
        get() = sp.getBoolean("welcome_done", false)
        set(v) = sp.edit().putBoolean("welcome_done", v).apply()

    private fun defaultRomsDir(): String {
        for (name in listOf("ROMs", "Roms", "roms")) {
            val f = File(storageRoot, name)
            if (f.isDirectory) return f.absolutePath
        }
        return File(storageRoot, "ROMs").absolutePath
    }

    private fun defaultBiosDir(): String {
        val roms = File(romsDir)
        for (name in listOf("bios", "BIOS", "Bios")) {
            val inRoms = File(roms, name)
            if (inRoms.isDirectory) return inRoms.absolutePath
        }
        for (name in listOf("BIOS", "bios")) {
            val top = File(storageRoot, name)
            if (top.isDirectory) return top.absolutePath
        }
        return File(roms, "bios").absolutePath
    }
}
