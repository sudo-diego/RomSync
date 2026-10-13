package io.github.sudodiego.romsync

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
        item { Text("Ajustes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
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
    val scope = rememberCoroutineScope()
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
        message = true to "Listo: la tablet ya ve tu carpeta de ROMs"
        scope.launch { Repo.refresh() }
    }

    Section("PC") {
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
        Text("2. Buscar el PC", fontWeight = FontWeight.Bold)
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
            }) { Text("Buscar mi PC") }
            if (searching) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Muted("Buscando en tu WiFi…") }
            else if (host.isNotBlank()) Muted("PC elegido: $host")
        }
        found?.let { list ->
            if (list.isEmpty()) Text("No apareció ningún PC con SFTP. Revisa el paso 1 y que el PC esté en la misma WiFi.",
                color = MaterialTheme.colorScheme.error)
            list.forEach { f ->
                TextButton(onClick = { host = f.ip }) {
                    Text((if (f.ip == host) "✓ " else "") + f.ip + (if (f.windows) " · Windows" else " · " + f.banner.take(30)))
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
    Field("Carpeta de ROMs en el PC", roms, { roms = it }, Modifier.fillMaxWidth(), hint = "D:\\Emulación\\ROMs")
    Field("Carpeta de BIOS (opcional)", bios, { bios = it }, Modifier.fillMaxWidth(), hint = "vacío = «bios» dentro de ROMs")
    Field("Respaldos de partidas (opcional)", backups, { backups = it }, Modifier.fillMaxWidth(), hint = "vacío = «RomSync» junto a ROMs")
    TextButton(onClick = {
        Prefs.port = port.toIntOrNull() ?: 22; Prefs.pcRoms = roms; Prefs.pcBios = bios; Prefs.pcBackups = backups
    }) { Text("Guardar") }
    if (Prefs.fingerprint.isNotEmpty()) TextButton(onClick = { Prefs.fingerprint = "" }) { Text("Olvidar la identidad del PC") }
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
        title = { Text(dir?.let { PcFinder.windowsPath(it) } ?: "Discos del PC") },
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
    val scope = rememberCoroutineScope()
    Section("Tablet") {
        Field("Carpeta de ROMs", roms, { roms = it }, Modifier.fillMaxWidth())
        Field("Carpeta de BIOS", bios, { bios = it }, Modifier.fillMaxWidth())
        Field("Nombre de la tablet (carpeta de sus respaldos)", name, { name = it }, Modifier.fillMaxWidth())
        Column {
            Text("Dejar siempre libres: ${reserve.toInt()} GB")
            Slider(value = reserve, onValueChange = { reserve = it }, valueRange = 0f..30f, steps = 29,
                onValueChangeFinished = { Prefs.reserveGb = reserve.toInt() })
            Muted("Una descarga que deje menos que esto no empieza (los emuladores también necesitan espacio para cachés y partidas).")
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
        Muted("Gratis: en steamgriddb.com inicia sesión → Preferencias → API → copia la clave. Las imágenes quedan guardadas en la tablet.")
        Field("Clave de la API", key, { key = it }, Modifier.fillMaxWidth(), password = true)
        val scope = rememberCoroutineScope()
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
