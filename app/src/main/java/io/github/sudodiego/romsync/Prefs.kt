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

    var autoBackup: Boolean
        get() = sp.getBoolean("auto_backup", true)
        set(v) = sp.edit().putBoolean("auto_backup", v).apply()

    /** Cada cuántas horas intenta el respaldo automático (solo con WiFi y el PC encendido). */
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
