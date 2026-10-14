package io.github.sudodiego.romsync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineLarge) }
        item { Column { LookAndSoundCard() } }
        item { PcCard() }
        item { TabletCard() }
        item { ArtCard() }
        item { ShizukuCard() }
        item { Muted("RomSync 0.2 · hecho para la Lenovo Legion Y700 Gen 3") }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
                  password: Boolean = false, number: Boolean = false, hint: String = "") {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        placeholder = { if (hint.isNotEmpty()) Text(hint) }, modifier = modifier,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            password -> KeyboardType.Password
            number -> KeyboardType.Number
            else -> KeyboardType.Text
        }))
}

@Composable
private fun PcCard() {
    val scope = appScope
    var host by remember { mutableStateOf(Prefs.host) }
    var user by remember { mutableStateOf(Prefs.user) }
    var pass by remember { mutableStateOf(Prefs.password) }
    var found by remember { mutableStateOf<List<PcFinder.Found>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var romsChoices by remember { mutableStateOf<List<PcFinder.RomsDir>?>(null) }
    var browse by remember { mutableStateOf(false) }
    var showSteps by remember { mutableStateOf(!Prefs.configured) }
    var manual by remember { mutableStateOf(false) }

    fun cfg() = SftpConfig(host.trim(), Prefs.port, user.trim(), pass, if (host.trim() == Prefs.host) Prefs.fingerprint else "")

    fun saveRoms(path: String) {
        Prefs.host = host; Prefs.user = user; Prefs.password = pass
        Prefs.pcRoms = PcFinder.windowsPath(path)
        romsChoices = null
        message = true to "Listo: el dispositivo ya ve tu carpeta de ROMs"
        scope.launch { Repo.refresh() }
    }

    Section("Servidor (tu PC)") {
        if (Prefs.configured) Text("Conectado a ${Prefs.host} · ${Prefs.pcRoms}", color = Palette.ok, fontWeight = FontWeight.SemiBold)

        // Paso 1: activar SFTP en Windows, solo con herramientas de Windows.
        SpacedRow {
            Text("1. Activar SFTP en Windows (una vez)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Ocultar" else "Ver cómo") }
        }
        if (showSteps) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("• Configuración → Sistema → Características opcionales → Ver características → busca «Servidor OpenSSH» → Agregar.")
            Text("• Abre la app «Servicios» (búscala en Inicio) → «OpenSSH SSH Server» → Tipo de inicio: Automático → Iniciar → Aceptar.")
            Muted("Todo con herramientas de Windows; no hay que bajar nada. El firewall se configura solo al agregarlo.")
        }

        // Paso 2: encontrar el PC en la red.
        Text("2. Buscar el servidor", fontWeight = FontWeight.Bold)
        SpacedRow {
            Button(enabled = !searching, onClick = {
                searching = true
                found = null
                scope.launch {
                    val me = Kitchen.lanAddress()
                    found = if (me == null) emptyList() else runCatching { PcFinder.scan(me) }.getOrDefault(emptyList())
                    // El equipo que abrió la web de la tablet suele ser el PC.
                    if (Kitchen.lastClientIp.isNotEmpty() && found!!.none { it.ip == Kitchen.lastClientIp }) {
                        withContext(Dispatchers.IO) { PcFinder.probe(Kitchen.lastClientIp) }?.let { found = listOf(it) + found!! }
                    }
                    if (found!!.size == 1) host = found!![0].ip
                    searching = false
                }
            }) { Text("Buscar mi servidor") }
            if (searching) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Muted("Buscando en tu WiFi…") }
            else if (host.isNotBlank()) Muted("Elegido: $host")
        }
        found?.let { list ->
            if (list.isEmpty()) Text("No apareció ningún equipo con SFTP. Revisa el paso 1 y que el servidor esté en la misma WiFi.",
                color = MaterialTheme.colorScheme.error)
            list.forEach { f ->
                TextButton(onClick = { host = f.ip }) {
                    Text((if (f.ip == host) "✓ " else "") + f.ip + (if (f.windows) " · Windows (tu servidor)" else " · otro aparato (router, NAS…): no es el servidor"))
                }
            }
        }

        // Paso 3: usuario y contraseña; después la carpeta de ROMs se busca sola.
        Text("3. Tu cuenta de Windows", fontWeight = FontWeight.Bold)
        SpacedRow {
            Field("Usuario", user, { user = it }, Modifier.weight(1f), hint = "el de C:\\Users\\…")
            Field("Contraseña", pass, { pass = it }, Modifier.weight(1f), password = true)
        }
        Muted("La contraseña de la cuenta (no el PIN). Con cuenta de Microsoft, es la de esa cuenta; el usuario es el nombre de tu carpeta en C:\\Users.")
        SpacedRow {
            Button(enabled = !connecting && host.isNotBlank() && user.isNotBlank(), onClick = {
                connecting = true
                message = null
                romsChoices = null
                scope.launch {
                    val r = runCatching {
                        withContext(Dispatchers.IO) {
                            SftpConn.connect(cfg()).use { c ->
                                Prefs.fingerprint = c.fingerprint
                                PcFinder.findRoms(c)
                            }
                        }
                    }
                    connecting = false
                    r.onSuccess { dirs ->
                        Prefs.host = host; Prefs.user = user; Prefs.password = pass
                        when {
                            dirs.size == 1 -> saveRoms(dirs[0].path)
                            dirs.isEmpty() -> { message = true to "Conectado. No encontré la carpeta de ROMs: elígela."; browse = true }
                            else -> { romsChoices = dirs; message = true to "Conectado. ¿Cuál es tu carpeta de ROMs?" }
                        }
                    }.onFailure { message = false to (it.message ?: "Error") }
                }
            }) { Text("Conectar") }
            if (connecting) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Muted("Conectando y buscando tu carpeta de ROMs…") }
        }
        message?.let { (good, text) -> Text(text, color = if (good) Palette.ok else MaterialTheme.colorScheme.error) }
        romsChoices?.let { list ->
            list.forEach { d ->
                TextButton(onClick = { saveRoms(d.path) }) { Text("${PcFinder.windowsPath(d.path)}  ·  ${d.platforms} plataformas") }
            }
            TextButton(onClick = { browse = true }) { Text("Otra carpeta…") }
        }
        if (Prefs.configured && romsChoices == null) TextButton(onClick = { browse = true }) { Text("Cambiar la carpeta de ROMs") }

        TextButton(onClick = { manual = !manual }) { Text(if (manual) "Ocultar opciones" else "Más opciones (puerto, BIOS, respaldos)") }
        if (manual) ManualPc()
    }
    if (browse) FolderPicker(cfg(), onPick = { browse = false; saveRoms(it) }, onClose = { browse = false })
}

/** Datos que casi nunca hay que tocar: puerto, carpeta de BIOS y de respaldos en el PC. */
@Composable
private fun ManualPc() {
    var port by remember { mutableStateOf(Prefs.port.toString()) }
    var roms by remember { mutableStateOf(Prefs.pcRoms) }
    var bios by remember { mutableStateOf(Prefs.pcBios) }
    var backups by remember { mutableStateOf(Prefs.pcBackups) }
    Field("Puerto", port, { port = it.filter(Char::isDigit) }, Modifier.width(140.dp), number = true)
    Field("Carpeta de ROMs en el servidor", roms, { roms = it }, Modifier.fillMaxWidth(), hint = "D:\\Emulación\\ROMs")
    Field("Carpeta de BIOS (opcional)", bios, { bios = it }, Modifier.fillMaxWidth(), hint = "vacío = «bios» dentro de ROMs")
    Field("Respaldos de partidas (opcional)", backups, { backups = it }, Modifier.fillMaxWidth(), hint = "vacío = «RomSync» junto a ROMs")
    TextButton(onClick = {
        Prefs.port = port.toIntOrNull() ?: 22; Prefs.pcRoms = roms; Prefs.pcBios = bios; Prefs.pcBackups = backups
    }) { Text("Guardar") }
    if (Prefs.fingerprint.isNotEmpty()) TextButton(onClick = { Prefs.fingerprint = "" }) { Text("Olvidar la identidad del servidor") }
}

/** Navegar las carpetas del PC por SFTP y elegir una (sin escribir rutas). */
@Composable
private fun FolderPicker(cfg: SftpConfig, onPick: (String) -> Unit, onClose: () -> Unit) {
    var dir by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<String>?>(null) }
    var platforms by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(dir) {
        entries = null
        val r = runCatching {
            withContext(Dispatchers.IO) {
                SftpConn.connect(cfg).use { c ->
                    val d = dir
                    if (d == null) PcFinder.drives(c) to 0
                    else c.list(d).filter { it.isDir && !it.name.startsWith(".") && !it.name.startsWith("$") }.map { SftpConn.join(d, it.name) }
                        .sortedBy { it.lowercase() } to PcFinder.platformCount(c, d)
                }
            }
        }
        r.onSuccess { entries = it.first; platforms = it.second }.onFailure { error = it.message }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(dir?.let { PcFinder.windowsPath(it) } ?: "Discos del servidor") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                if (dir != null) item {
                    TextButton(onClick = { dir = dir!!.substringBeforeLast('/').takeIf { it.length > 1 && it.count { c -> c == '/' } >= 1 && !Regex("^/?$").matches(it) } }) { Text("↑ Subir") }
                }
                if (entries == null && error == null) item { CircularProgressIndicator() }
                entries?.let { list ->
                    items(list.size) { i ->
                        TextButton(onClick = { dir = list[i] }, Modifier.fillMaxWidth()) {
                            Text("📁 " + list[i].substringAfterLast('/').ifEmpty { list[i] }, Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (dir != null) Button(onClick = { onPick(dir!!) }) {
                Text(if (platforms > 0) "Usar esta ($platforms plataformas)" else "Usar esta carpeta")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}

@Composable
private fun TabletCard() {
    var roms by remember { mutableStateOf(Prefs.romsDir) }
    var bios by remember { mutableStateOf(Prefs.biosDir) }
    var name by remember { mutableStateOf(Prefs.deviceName) }
    var reserve by remember { mutableStateOf(Prefs.reserveGb.toFloat()) }
    val scope = appScope
    Section("Este dispositivo") {
        Field("Carpeta de ROMs", roms, { roms = it }, Modifier.fillMaxWidth())
        Field("Carpeta de BIOS", bios, { bios = it }, Modifier.fillMaxWidth())
        Field("Nombre del dispositivo (carpeta de sus respaldos)", name, { name = it }, Modifier.fillMaxWidth())
        Column {
            Text("Dejar siempre libres: ${reserve.toInt()} GB")
            Slider(value = reserve, onValueChange = { reserve = it }, valueRange = 0f..30f, steps = 29,
                onValueChangeFinished = { Prefs.reserveGb = reserve.toInt() })
            Muted("Una descarga que deje menos libre que esto no empieza.")
        }
        Button(onClick = {
            Prefs.romsDir = roms; Prefs.biosDir = bios; Prefs.deviceName = name
            scope.launch { Repo.refresh(pc = false) }
        }) { Text("Guardar") }
    }
}

@Composable
private fun ArtCard() {
    var key by remember { mutableStateOf(Prefs.sgdbKey) }
    Section("Carátulas (SteamGridDB)") {
        Muted("Gratis en steamgriddb.com → Preferencias → API.")
        Field("Clave de la API", key, { key = it }, Modifier.fillMaxWidth(), password = true)
        val scope = appScope
        var msg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
        var testing by remember { mutableStateOf(false) }
        SpacedRow {
            Button(enabled = key.isNotBlank() && !testing, onClick = {
                testing = true
                scope.launch {
                    val e = Art.testKey(key.trim())
                    testing = false
                    if (e == null) {
                        Prefs.sgdbKey = key
                        msg = true to "Clave guardada y funcionando ✓"
                    } else msg = false to e
                }
            }) { Text("Probar y guardar") }
            if (testing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            if (Prefs.sgdbKey.isNotBlank()) TextButton(onClick = { Art.startFetchAll(Repo.allGames()); StoreState.coversHidden = false; Nav.tab = Tab.STORE }) {
                Text("Buscar todas las carátulas")
            }
        }
        msg?.let { (ok, text) -> Text(text, color = if (ok) Palette.ok else MaterialTheme.colorScheme.error) }
    }
}

/** Tema, color, fondo, música de fondo y sonidos de navegación. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun LookAndSoundCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var music by remember { mutableStateOf(Prefs.musicOn) }
    var sfx by remember { mutableStateOf(Prefs.sfxOn) }
    var musicVol by remember { mutableStateOf(Prefs.musicVolume.toFloat()) }
    var sfxVol by remember { mutableStateOf(Prefs.sfxVolume.toFloat()) }
    var trackSel by remember { mutableStateOf(Prefs.musicTrack) }
    var pack by remember { mutableStateOf(Prefs.sfxPack) }
    var customName by remember { mutableStateOf(Prefs.customMusicName) }
    // Elegir una canción propia (cualquier audio del dispositivo); se guarda el permiso para leerla siempre.
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: "Canción propia"
            Prefs.customMusic = uri.toString(); Prefs.customMusicName = name; customName = name
            Prefs.musicTrack = "propia"; trackSel = "propia"
            Sound.musicSettingsChanged()
        }
    }
    Section("Aspecto") { LookControls() }
    Spacer(Modifier.height(14.dp))
    Section("Sonido") {
        SpacedRow {
            Icon(Icons.Rounded.MusicNote, null)
            Text("Música", Modifier.width(110.dp), fontWeight = FontWeight.SemiBold)
            Slider(musicVol, { musicVol = it; Prefs.musicVolume = it.toInt(); Sound.musicSettingsChanged() }, Modifier.weight(1f),
                enabled = music, valueRange = 0f..100f)
            Switch(music, { music = it; Prefs.musicOn = it; Sound.musicSettingsChanged(); Sound.play(Sound.Fx.TOGGLE) })
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 34.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sound.tracks.forEach { (id, v) ->
                FilterChip(selected = trackSel == id, enabled = music, onClick = {
                    trackSel = id; Prefs.musicTrack = id; Sound.musicSettingsChanged()
                }, label = { Text(v.first) }, shape = RoundedCornerShape(99.dp))
            }
            FilterChip(selected = trackSel == "propia", enabled = music, onClick = {
                if (Prefs.customMusic.isBlank()) pick.launch(arrayOf("audio/*"))
                else { trackSel = "propia"; Prefs.musicTrack = "propia"; Sound.musicSettingsChanged() }
            }, label = { Text(if (customName.isBlank()) "Elegir canción propia…" else customName.substringBeforeLast('.').take(28)) },
                leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(99.dp))
            if (customName.isNotBlank()) TextButton(enabled = music, onClick = { pick.launch(arrayOf("audio/*")) }) { Text("Cambiar") }
        }
        SpacedRow {
            Icon(Icons.Rounded.TouchApp, null)
            Text("Sonidos", Modifier.width(110.dp), fontWeight = FontWeight.SemiBold)
            Slider(sfxVol, { sfxVol = it; Prefs.sfxVolume = it.toInt() }, Modifier.weight(1f), enabled = sfx, valueRange = 0f..100f,
                onValueChangeFinished = { Sound.play(Sound.Fx.MOVE) })
            Switch(sfx, { sfx = it; Prefs.sfxOn = it; Sound.play(Sound.Fx.TOGGLE) })
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 34.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sound.packs.forEach { (id, label) ->
                FilterChip(selected = pack == id, enabled = sfx, onClick = { pack = id; Prefs.sfxPack = id; Sound.preview(id) },
                    label = { Text(label) }, shape = RoundedCornerShape(99.dp))
            }
        }
    }
}

/** Modo claro/oscuro/sistema, color principal (presets o código) y fondo animado. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun LookControls() {
    var hex by remember { mutableStateOf("%06X".format(UiState.accent and 0xFFFFFF)) }
    fun setAccent(v: Long) {
        UiState.accent = v; Prefs.accent = v; hex = "%06X".format(v and 0xFFFFFF); Sound.play(Sound.Fx.TOGGLE)
    }
    SpacedRow {
        Text("Tema", Modifier.width(110.dp), fontWeight = FontWeight.SemiBold)
        listOf("claro" to "Claro", "oscuro" to "Oscuro", "sistema" to "Del sistema").forEach { (id, label) ->
            FilterChip(selected = UiState.theme == id, onClick = {
                Sound.play(Sound.Fx.TOGGLE); UiState.theme = id; Prefs.theme = id
            }, label = { Text(label) }, shape = RoundedCornerShape(99.dp))
        }
    }
    SpacedRow {
        Text("Color", Modifier.width(110.dp), fontWeight = FontWeight.SemiBold)
        androidx.compose.foundation.layout.FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            accentPresets.forEach { (name, v) ->
                val sel = UiState.accent == v
                androidx.compose.foundation.layout.Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(99.dp)).background(Color(v))
                        .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(99.dp)) else Modifier)
                        .clickable { setAccent(v) },
                    contentAlignment = Alignment.Center,
                ) { if (sel) Icon(Icons.Rounded.Check, name, tint = Color.White) }
            }
        }
        OutlinedTextField(hex, { v ->
            hex = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
            if (hex.length == 6) { UiState.accent = 0xFF000000L or hex.toLong(16); Prefs.accent = UiState.accent }
        }, Modifier.width(150.dp), singleLine = true, prefix = { Text("#") }, label = { Text("Código") },
            leadingIcon = { androidx.compose.foundation.layout.Box(Modifier.size(18.dp).clip(RoundedCornerShape(99.dp)).background(Color(UiState.accent))) })
    }
    SpacedRow {
        Text("Fondo", Modifier.width(110.dp), fontWeight = FontWeight.SemiBold)
        listOf("burbujas" to "Burbujas", "ondas" to "Ondas", "ninguno" to "Sin animación").forEach { (id, label) ->
            FilterChip(selected = UiState.background == id, onClick = {
                Sound.play(Sound.Fx.TOGGLE); UiState.background = id; Prefs.background = id
            }, label = { Text(label) }, shape = RoundedCornerShape(99.dp))
        }
    }
}
