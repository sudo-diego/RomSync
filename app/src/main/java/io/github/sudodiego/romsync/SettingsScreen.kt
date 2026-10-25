package io.github.sudodiego.romsync

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Link
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.LibraryMusic
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
    PadHints(listOf("A" to "Elegir", "◀▶" to "Cambiar", "B" to "Panel", "L R" to "Sección"))
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().screenFocus { listState.animateScrollBy(it) }, state = listState,
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineLarge) }
        item { Column { LookAndSoundCard() } }
        item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { PcCard() } }
        item { TabletCard() }
        item { ArtCard() }
        item { ShizukuCard() }
        item { Muted("RomSync 0.2") }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
                  password: Boolean = false, number: Boolean = false, hint: String = "") {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        placeholder = { if (hint.isNotEmpty()) Text(hint) }, modifier = modifier.gamepadText(),
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
    var port by remember { mutableStateOf(Prefs.port.toString()) }
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

    fun cfg() = SftpConfig(host.trim(), port.toIntOrNull() ?: 22, user.trim(), pass, if (host.trim() == Prefs.host) Prefs.fingerprint else "")

    fun saveRoms(path: String) {
        Prefs.host = host; Prefs.port = port.toIntOrNull() ?: 22; Prefs.user = user; Prefs.password = pass
        Prefs.pcRoms = PcFinder.windowsPath(path)
        romsChoices = null
        message = true to "Listo: el dispositivo ya ve tu carpeta de ROMs"
        scope.launch { Repo.refresh() }
    }

    var editing by remember { mutableStateOf(!Prefs.configured) }
    var advanced by remember { mutableStateOf(false) }

    // Arriba, siempre: el estado de la conexión en una tarjeta clara.
    Section("Servidor (tu PC)") {
        val conn = Repo.conn
        SpacedRow {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(99.dp)).background(when {
                !Prefs.configured -> Color(0xFF94A3B8)
                conn is Conn.Online -> Palette.ok
                conn is Conn.Offline -> Palette.danger
                else -> Palette.blue
            }))
            Column(Modifier.weight(1f)) {
                Text(when {
                    !Prefs.configured -> "Sin servidor todavía"
                    conn is Conn.Online -> "Conectado a tu PC"
                    conn is Conn.Offline -> "Sin conexión con tu PC"
                    else -> "Conectando…"
                }, style = MaterialTheme.typography.titleLarge)
                if (Prefs.configured) Muted("${Prefs.host}:${Prefs.port} · ROMs en ${Prefs.pcRoms}")
                else Muted("Conecta tu PC para ver y bajar tus juegos y respaldar tus partidas. Son 3 pasos.")
            }
            if (Prefs.configured) TextButton(onClick = { editing = !editing }) { Text(if (editing) "Listo" else "Cambiar de servidor") }
        }
        if (Prefs.configured && !editing) SpeedTestRow()
    }

    if (editing) {
        // Paso 1
        StepCard(1, "Activar SFTP en Windows", "Una sola vez, con herramientas de Windows. No hay que instalar nada.", done = Prefs.configured) {
            SpacedRow {
                Muted("¿Ya lo hiciste? Sigue al paso 2.", Modifier.weight(1f))
                TextButton(onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Ocultar" else "Ver cómo") }
            }
            if (showSteps) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("1. Configuración → Sistema → Características opcionales → Ver características → «Servidor OpenSSH» → Agregar.")
                Text("2. App «Servicios» → «OpenSSH SSH Server» → Tipo de inicio: Automático → Iniciar → Aceptar.")
                Muted("El firewall de Windows se configura solo al agregarlo.")
            }
        }
        // Paso 2
        StepCard(2, "Encontrar tu PC", "En casa se busca solo en tu WiFi. Fuera de casa, escribe su dirección.", done = host.isNotBlank()) {
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
                        if (found!!.count { it.windows } == 1) host = found!!.first { it.windows }.ip
                        searching = false
                    }
                }) { Text("Buscar en mi WiFi") }
                if (searching) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Muted("Buscando…") }
            }
            found?.let { list ->
                if (list.isEmpty()) Text("No apareció ningún PC con SFTP. Revisa el paso 1 y que esté en la misma WiFi.", color = MaterialTheme.colorScheme.error)
                list.forEach { f ->
                    val sel = f.ip == host
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { host = f.ip }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.RadioButton(sel, { host = f.ip })
                        Column(Modifier.weight(1f)) {
                            Text(f.ip, fontWeight = FontWeight.Bold)
                            Muted(if (f.windows) "Windows: tu PC" else "Otro aparato (router, NAS…): no es tu PC")
                        }
                    }
                }
            }
            Field("Dirección de tu PC", host, { host = it.trim() }, Modifier.fillMaxWidth(), hint = "192.168.1.20 · 100.x.x.x (Tailscale) · mi-pc.local")
            Muted("Con Tailscale usa la IP 100.x.x.x de tu PC, que aparece en la app de Tailscale. El puerto sigue siendo el 22, " +
                "el mismo de siempre: Tailscale solo cambia la dirección.")
        }
        // Paso 3
        StepCard(3, "Tu cuenta de Windows", "Para entrar a tus carpetas. La carpeta de ROMs, BIOS y respaldos se buscan solas.", done = Prefs.configured) {
            SpacedRow {
                Field("Usuario", user, { user = it }, Modifier.weight(1f), hint = "el de C:\\Users\\…")
                Field("Contraseña", pass, { pass = it }, Modifier.weight(1f), password = true)
            }
            Muted("La contraseña de la cuenta, no el PIN. Con cuenta de Microsoft es la de esa cuenta. El usuario es el nombre de tu carpeta en C:\\Users.")
            SpacedRow {
                BigActionButton(if (connecting) "Conectando…" else "Conectar", Icons.Rounded.Link,
                    enabled = !connecting && host.isNotBlank() && user.isNotBlank()) {
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
                            Prefs.host = host; Prefs.port = port.toIntOrNull() ?: 22; Prefs.user = user; Prefs.password = pass
                            when {
                                dirs.size == 1 -> { saveRoms(dirs[0].path); editing = false }
                                dirs.isEmpty() -> { message = true to "Conectado. No encontré la carpeta de ROMs: elígela."; browse = true }
                                else -> { romsChoices = dirs; message = true to "Conectado. ¿Cuál es tu carpeta de ROMs?" }
                            }
                        }.onFailure { message = false to (it.message ?: "Error") }
                    }
                }
                if (connecting) Muted("Conectando y buscando tu carpeta de ROMs…")
            }
            message?.let { (good, text) -> Text(text, color = if (good) Palette.ok else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
            romsChoices?.let { list ->
                list.forEach { d ->
                    TextButton(onClick = { saveRoms(d.path); editing = false }) { Text("${PcFinder.windowsPath(d.path)}  ·  ${d.platforms} plataformas") }
                }
                TextButton(onClick = { browse = true }) { Text("Otra carpeta…") }
            }
        }
    }

    // Para ajustar a mano (casi nunca hace falta).
    Section("") {
        SpacedRow(Modifier.fillMaxWidth().clickable { advanced = !advanced }) {
            Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text("Avanzado", fontWeight = FontWeight.Bold)
                Muted("Puerto, carpetas en el PC e identidad del servidor. La app los detecta sola. Cámbialos solo si sabes que hace falta.")
            }
            Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (advanced) {
            ManualPc()
            if (Prefs.configured) TextButton(onClick = { browse = true }) { Text("Elegir la carpeta de ROMs navegando…") }
        }
    }
    if (browse) FolderPicker(cfg(), onPick = { browse = false; saveRoms(it) }, onClose = { browse = false })
}

/** Un paso de la conexión: número (o ✓ si ya está), título, para qué sirve y su contenido. */
@Composable
private fun StepCard(n: Int, title: String, detail: String, done: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Section("") {
        SpacedRow {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(99.dp)).background(if (done) Palette.ok else MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) {
                if (done) Icon(Icons.Rounded.Check, null, tint = Color.White)
                else Text("$n", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Muted(detail)
            }
        }
        content()
    }
}

/** Datos que casi nunca hay que tocar: puerto, carpeta de BIOS y de respaldos en el PC. */
@Composable
private fun ManualPc() {
    var port by remember { mutableStateOf(Prefs.port.toString()) }
    var roms by remember { mutableStateOf(Prefs.pcRoms) }
    var bios by remember { mutableStateOf(Prefs.pcBios) }
    var backups by remember { mutableStateOf(Prefs.pcBackups) }
    Field("Puerto SSH", port, { port = it.filter(Char::isDigit) }, Modifier.width(160.dp), number = true)
    Muted("El 22 es el de siempre (también por Tailscale). Solo cámbialo si configuraste OpenSSH con otro.")
    Field("Carpeta de ROMs en el servidor", roms, { roms = it }, Modifier.fillMaxWidth(), hint = "D:\\Emulación\\ROMs")
    Field("Carpeta de BIOS (opcional)", bios, { bios = it }, Modifier.fillMaxWidth(), hint = "vacío = «bios» dentro de ROMs")
    Field("Respaldos de partidas (opcional)", backups, { backups = it }, Modifier.fillMaxWidth(), hint = "vacío = «RomSync» junto a ROMs")
    TextButton(onClick = {
        Prefs.port = port.toIntOrNull() ?: 22; Prefs.pcRoms = roms; Prefs.pcBios = bios; Prefs.pcBackups = backups
    }) { Text("Guardar") }
    if (Prefs.fingerprint.isNotEmpty()) {
        Muted("Identidad del servidor (para notar si alguien se hace pasar por tu PC): ${Prefs.fingerprint.take(23)}…")
        TextButton(onClick = { Prefs.fingerprint = "" }) { Text("Olvidar la identidad (si reinstalaste Windows u OpenSSH)") }
    }
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
                    items(list.size, key = { list[it] }) { i ->
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var roms by remember { mutableStateOf(Prefs.romsDir) }
    var bios by remember { mutableStateOf(Prefs.biosDir) }
    var name by remember { mutableStateOf(Prefs.deviceName) }
    var editName by remember { mutableStateOf(false) }
    var reserve by remember { mutableStateOf(Prefs.reserveGb.toFloat()) }
    val scope = appScope
    // Elegir carpeta con el selector de Android (sin escribir rutas).
    var picking by remember { mutableStateOf("") }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let { treeToPath(it) }
        if (uri != null && path == null) Notice.info("No se pudo usar esa carpeta. Elige una del almacenamiento del dispositivo")
        if (path != null) {
            if (picking == "roms") { roms = path; Prefs.romsDir = path } else { bios = path; Prefs.biosDir = path }
            Notice.info("Carpeta guardada: ${path.removePrefix(android.os.Environment.getExternalStorageDirectory().path)}")
            scope.launch { Repo.refresh(pc = false) }
        }
    }
    Section("Este dispositivo") {
        @Composable
        fun FolderRow(title: String, path: String, what: String) = SpacedRow {
            Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Muted(path.removePrefix(android.os.Environment.getExternalStorageDirectory().path).ifEmpty { "/" }.let { "Almacenamiento$it" })
            }
            FilledTonalButton(onClick = { Sound.play(Sound.Fx.MOVE); picking = what; picker.launch(null) }) { Text("Elegir…") }
        }
        FolderRow("Carpeta de ROMs", roms, "roms")
        FolderRow("Carpeta de BIOS", bios, "bios")
        SpacedRow {
            Icon(Icons.Rounded.TabletAndroid, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Nombre: $name", fontWeight = FontWeight.SemiBold)
                Muted("Lo pone la app sola. Separa los respaldos de este dispositivo de los de otros en el servidor.")
            }
            TextButton(onClick = { editName = !editName }) { Text(if (editName) "Listo" else "Cambiar") }
        }
        if (editName) {
            Field("Nombre del dispositivo", name, { name = it }, Modifier.fillMaxWidth())
            Muted("Si lo cambias, los respaldos anteriores quedan con el nombre viejo, pero se ven igual al restaurar.")
            TextButton(onClick = { Prefs.deviceName = name; name = Prefs.deviceName; editName = false }) { Text("Guardar nombre") }
        }
        Column {
            Text("Dejar siempre libres: ${reserve.toInt()} GB", fontWeight = FontWeight.SemiBold)
            Slider(value = reserve, onValueChange = { reserve = it }, valueRange = 0f..30f, steps = 29,
                onValueChangeFinished = { Prefs.reserveGb = reserve.toInt() },
                modifier = Modifier.padSlider(reserve, 0f..30f, 1f) { reserve = it; Prefs.reserveGb = it.toInt() })
            Muted("Una descarga que deje menos libre que esto no empieza.")
        }
    }
}

/** "content://…/tree/primary:ROMs" → "/storage/emulated/0/ROMs" (y las tarjetas SD a /storage/XXXX-XXXX/…). */
private fun treeToPath(uri: android.net.Uri): String? = runCatching {
    val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
    val vol = id.substringBefore(':')
    val rel = id.substringAfter(':', "")
    val base = if (vol.equals("primary", true)) android.os.Environment.getExternalStorageDirectory().path else "/storage/$vol"
    if (rel.isEmpty()) base else "$base/$rel"
}.getOrNull()?.takeIf { java.io.File(it).isDirectory }

@Composable
private fun ArtCard() {
    var key by remember { mutableStateOf(Prefs.sgdbKey) }
    Section("Carátulas (SteamGridDB)") {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (Prefs.sgdbKey.isBlank()) {
            Text("Sin clave los juegos se ven sin carátula, fondo ni logo.", fontWeight = FontWeight.SemiBold)
            Muted("Es gratis. Entra con tu cuenta de Steam en SteamGridDB, copia la clave y pégala aquí.")
            FilledTonalButton(onClick = {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.steamgriddb.com/profile/preferences/api"))) }
            }) { Text("Conseguir mi clave en SteamGridDB") }
        } else Text("Conectado a SteamGridDB ✓", color = Palette.ok, fontWeight = FontWeight.SemiBold)
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
            Sound.musicSettingsChanged()
        }
    }
    Section("Aspecto") { LookControls() }
    Spacer(Modifier.height(14.dp))
    Section("Sonido") {
        SpacedRow {
            Icon(Icons.Rounded.MusicNote, null)
            Text("Música", Modifier.width(110.dp), fontWeight = FontWeight.SemiBold)
            Slider(musicVol, { musicVol = it; Prefs.musicVolume = it.toInt(); Sound.musicSettingsChanged() },
                Modifier.weight(1f).padSlider(musicVol, 0f..100f, 5f) { musicVol = it; Prefs.musicVolume = it.toInt(); Sound.musicSettingsChanged() },
                enabled = music, valueRange = 0f..100f)
            Switch(music, { music = it; Prefs.musicOn = it; Sound.musicSettingsChanged(); Sound.play(Sound.Fx.TOGGLE) })
        }
        SpacedRow(Modifier.padding(start = 34.dp)) {
            FilterChip(selected = customName.isNotBlank(), enabled = music, onClick = { pick.launch(arrayOf("audio/*")) },
                label = { Text(if (customName.isBlank()) "Elegir canción…" else customName.substringBeforeLast('.').take(40)) },
                leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(99.dp))
            if (customName.isNotBlank()) TextButton(onClick = {
                Prefs.customMusic = ""; Prefs.customMusicName = ""; customName = ""; Sound.musicSettingsChanged()
            }) { Text("Quitar") }
        }
        SpacedRow {
            Icon(Icons.Rounded.TouchApp, null)
            Text("Sonidos", Modifier.width(110.dp), fontWeight = FontWeight.SemiBold)
            Slider(sfxVol, { sfxVol = it; Prefs.sfxVolume = it.toInt() }, Modifier.weight(1f).padSlider(sfxVol, 0f..100f, 5f) { sfxVol = it; Prefs.sfxVolume = it.toInt() },
                enabled = sfx, valueRange = 0f..100f,
                onValueChangeFinished = { Sound.play(Sound.Fx.MOVE) })
            Switch(sfx, { sfx = it; Prefs.sfxOn = it; Sound.play(Sound.Fx.TOGGLE) })
        }
        OptionRow("Juego de sonidos", Sound.packs.toList(), pack, enabled = sfx) { pack = it; Prefs.sfxPack = it; Sound.preview(it) }
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
    OptionRow("Tema", listOf("claro" to "Claro", "oscuro" to "Oscuro", "sistema" to "Del sistema"), UiState.theme,
        Modifier.focusWhen("entrada:SETTINGS")) { UiState.theme = it; Prefs.theme = it }
    // Color: muestras grandes; la elegida con un anillo y ✓; con mando se recorren con la cruceta.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Color destacado", fontWeight = FontWeight.SemiBold)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            accentPresets.forEach { (name, v) ->
                val sel = UiState.accent == v
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // El anillo va por fuera del color, separado.
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(99.dp)).clickable { setAccent(v) }
                            .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(99.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.layout.Box(Modifier.size(40.dp).clip(RoundedCornerShape(99.dp)).background(Color(v)),
                            contentAlignment = Alignment.Center) { if (sel) Icon(Icons.Rounded.Check, name, tint = Color.White) }
                    }
                    Text(name.substringBefore(' '), style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        SpacedRow {
            Muted("O escribe un código:")
            OutlinedTextField(hex, { v ->
                hex = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                if (hex.length == 6) { UiState.accent = 0xFF000000L or hex.toLong(16); Prefs.accent = UiState.accent }
            }, Modifier.width(150.dp).gamepadText(), singleLine = true, prefix = { Text("#") },
                leadingIcon = { androidx.compose.foundation.layout.Box(Modifier.size(18.dp).clip(RoundedCornerShape(99.dp)).background(Color(UiState.accent))) })
        }
    }
    OptionRow("Fondo", listOf("burbujas" to "Burbujas", "ondas" to "Ondas", "ninguno" to "Sin animación"), UiState.background) {
        UiState.background = it; Prefs.background = it
    }
    SpacedRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
        Sound.play(Sound.Fx.TOGGLE); UiState.fullscreen = !UiState.fullscreen; Prefs.fullscreen = UiState.fullscreen
    }.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Pantalla completa", fontWeight = FontWeight.SemiBold)
            Muted("Oculta la barra de arriba y los botones de Android. Aparecen al deslizar desde el borde.")
        }
        androidx.compose.material3.Switch(checked = UiState.fullscreen, onCheckedChange = {
            Sound.play(Sound.Fx.TOGGLE); UiState.fullscreen = it; Prefs.fullscreen = it
        })
    }
    SpacedRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
        Sound.play(Sound.Fx.TOGGLE); UiState.lightAnim = !UiState.lightAnim; Prefs.lightAnim = UiState.lightAnim
    }.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Optimizar las animaciones al descargar", fontWeight = FontWeight.SemiBold)
            Muted("El fondo se mueve más lento mientras se descarga y gasta menos batería")
        }
        androidx.compose.material3.Switch(checked = UiState.lightAnim, onCheckedChange = {
            Sound.play(Sound.Fx.TOGGLE); UiState.lightAnim = it; Prefs.lightAnim = it
        })
    }
    SpacedRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
        Sound.play(Sound.Fx.TOGGLE); UiState.showShortcuts = !UiState.showShortcuts; Prefs.showShortcuts = UiState.showShortcuts
    }.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Mostrar accesos directos", fontWeight = FontWeight.SemiBold)
            Muted("Juegos de Steam y apps que agregan otras apps, como DroiDeck. Se abren en esa app")
        }
        androidx.compose.material3.Switch(checked = UiState.showShortcuts, onCheckedChange = {
            Sound.play(Sound.Fx.TOGGLE); UiState.showShortcuts = it; Prefs.showShortcuts = it
        })
    }
    OptionRow("Al abrir una ficha", UiState.openAnims.toList(), UiState.openAnim) { UiState.openAnim = it; Prefs.openAnim = it }
    OptionRow("Animación de descarga", DownloadArt.styles.toList(), DownloadArt.style) { DownloadArt.style = it; Prefs.dlStyle = it }
    Muted(when (DownloadArt.style) {
        "3ds" -> "Bloques que llenan el cubo del ícono y, al terminar, un regalo que se abre al entrar al juego."
        "switch" -> "La carátula con una barra de avance abajo, como el menú de Switch."
        else -> "Un anillo con el porcentaje sobre la carátula."
    })
}

/**
 * Una opción como en Steam Big Picture: el nombre y el valor elegido con flechas a los lados. Con mando, izquierda/derecha
 * cambian el valor (con sonido) y A pasa al siguiente; con el dedo se tocan las flechas o el valor.
 */
@Composable
fun OptionRow(label: String, options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier,
              enabled: Boolean = true, onChange: (String) -> Unit) {
    val i = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    fun move(step: Int) {
        if (!enabled) return
        Sound.play(Sound.Fx.TOGGLE)
        onChange(options[(i + step + options.size) % options.size].first)
    }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .onPreviewKeyEvent { e ->
            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); true }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); true }
                else -> false
            }
        }
        .clickable(enabled = enabled) { move(1) }.padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { move(-1) }, enabled = enabled, modifier = Modifier.notForDpad()) {
            Icon(Icons.Rounded.ChevronLeft, "Anterior")
        }
        Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(options[i].second, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            // Puntos: cuántas opciones hay y cuál está elegida.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                options.indices.forEach { k ->
                    androidx.compose.foundation.layout.Box(Modifier.size(if (k == i) 14.dp else 6.dp, 6.dp).clip(RoundedCornerShape(99.dp))
                        .background(if (k == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                }
            }
        }
        IconButton(onClick = { move(1) }, enabled = enabled, modifier = Modifier.notForDpad()) {
            Icon(Icons.Rounded.ChevronRight, "Siguiente")
        }
    }
}

/** Medir la velocidad con el servidor (bajada y subida), sin tocar los juegos. */
@Composable
private fun SpeedTestRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var step by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    SpacedRow {
        FilledTonalButton(enabled = step == null, onClick = {
            step = "Empezando…"; result = null
            appScope.launch {
                result = runCatching { PcEdit.speedTest(ctx.cacheDir) { step = it } }.fold(
                    { (d, u) -> "Bajada ${formatSpeed(d)} · Subida ${formatSpeed(u)}" +
                        (if (PcEdit.lastRealUpload > 0) " (con un juego real: ${formatSpeed(PcEdit.lastRealUpload)})" else "") }, { "No se pudo medir: ${it.message}" })
                step = null
            }
        }) { Text("Probar velocidad") }
        if (step == null) result?.let { Text(it, fontWeight = FontWeight.SemiBold) }
    }
    // Mientras mide: el número grande que cambia en vivo y la barra de la fase (como Speedtest).
    if (step != null) {
        val shown by androidx.compose.animation.core.animateFloatAsState(PcEdit.liveSpeed / 1_000_000f,
            androidx.compose.animation.core.tween(350), label = "velocidad")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(step!!, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.0f".format(shown), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text(" MB/s", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            }
            androidx.compose.material3.LinearProgressIndicator(progress = { PcEdit.liveFrac }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)))
        }
    }
    Muted("Baja 512 MB de un juego del servidor y sube 256 MB de prueba y 256 MB de un juego tuyo. No se guarda nada.")
}

/** Un deslizador que se mueve con izquierda/derecha del mando (de a [step]), con el anillo de foco y sonido. */
@Composable
fun Modifier.padSlider(value: Float, range: ClosedFloatingPointRange<Float>, step: Float, set: (Float) -> Unit): Modifier =
    this.focusHighlight(RoundedCornerShape(99.dp), grow = 1f).onPreviewKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val d = when (e.nativeKeyEvent.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -step
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> step
            else -> return@onPreviewKeyEvent false
        }
        set((value + d).coerceIn(range.start, range.endInclusive)); Sound.play(Sound.Fx.MOVE); true
    }
