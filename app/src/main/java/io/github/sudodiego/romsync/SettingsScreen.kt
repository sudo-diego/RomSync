package io.github.sudodiego.romsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { PcCard() }
        item { HowToCard() }
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
    var port by remember { mutableStateOf(Prefs.port.toString()) }
    var user by remember { mutableStateOf(Prefs.user) }
    var pass by remember { mutableStateOf(Prefs.password) }
    var roms by remember { mutableStateOf(Prefs.pcRoms) }
    var bios by remember { mutableStateOf(Prefs.pcBios) }
    var backups by remember { mutableStateOf(Prefs.pcBackups) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    Section("PC (SFTP)") {
        Muted("La tablet entra al disco del PC con el servidor OpenSSH de Windows, con tu usuario y contraseña de Windows. No hay que instalar nada más en el PC.")
        SpacedRow {
            Field("IP del PC", host, { host = it }, Modifier.weight(1f), hint = "192.168.1.10")
            Field("Puerto", port, { port = it.filter(Char::isDigit) }, Modifier.width(110.dp), number = true)
        }
        SpacedRow {
            Field("Usuario de Windows", user, { user = it }, Modifier.weight(1f))
            Field("Contraseña", pass, { pass = it }, Modifier.weight(1f), password = true)
        }
        Field("Carpeta de ROMs en el PC", roms, { roms = it }, Modifier.fillMaxWidth(), hint = "D:\\Emulación\\ROMs")
        Field("Carpeta de BIOS (opcional)", bios, { bios = it }, Modifier.fillMaxWidth(), hint = "vacío = «bios» dentro de ROMs")
        Field("Respaldos de partidas (opcional)", backups, { backups = it }, Modifier.fillMaxWidth(), hint = "vacío = «RomSync» junto a ROMs")
        SpacedRow {
            Button(enabled = !testing && host.isNotBlank() && user.isNotBlank() && roms.isNotBlank(), onClick = {
                testing = true
                result = null
                scope.launch {
                    val changedHost = host.trim() != Prefs.host
                    Prefs.host = host; Prefs.port = port.toIntOrNull() ?: 22; Prefs.user = user; Prefs.password = pass
                    Prefs.pcRoms = roms; Prefs.pcBios = bios; Prefs.pcBackups = backups
                    if (changedHost) Prefs.fingerprint = ""
                    val r = runCatching {
                        Pc.io { c ->
                            val st = c.stat(Prefs.remoteRoms)
                            if (st?.isDir != true) error("Conectado, pero no existe ${Prefs.remoteRoms}")
                            c.list(Prefs.remoteRoms).count { it.isDir }
                        }
                    }
                    testing = false
                    ok = r.isSuccess
                    result = r.fold({ "Conectado · $it carpetas de plataformas" }, { it.message ?: "Error" })
                    if (r.isSuccess) Repo.refresh()
                }
            }) { Text("Probar y guardar") }
            if (testing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            if (Prefs.fingerprint.isNotEmpty()) TextButton(onClick = { Prefs.fingerprint = ""; result = "Se aceptará la identidad del PC la próxima vez" }) {
                Text("Olvidar identidad del PC")
            }
        }
        result?.let { Text(it, color = if (ok) Palette.ok else MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun HowToCard() {
    var open by remember { mutableStateOf(!Prefs.configured) }
    Section("Cómo activar SFTP en Windows (una vez)") {
        if (!open) {
            TextButton(onClick = { open = true }) { Text("Ver los pasos") }
            return@Section
        }
        Text("1. Configuración → Sistema → Características opcionales → Agregar → «Servidor OpenSSH».")
        Text("2. En PowerShell como administrador:")
        Text("Start-Service sshd\nSet-Service -Name sshd -StartupType Automatic", fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
        Text("3. La IP del PC sale con «ipconfig» (Dirección IPv4). Conviene fijarla en el router.")
        Text("4. Usuario: el de Windows (si usas cuenta de Microsoft, el correo o el nombre de la carpeta en C:\\Users). Contraseña: la de la cuenta (no el PIN).")
        Muted("Es lo mismo que usaba Nixel. Todo queda dentro de tu red.")
    }
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
        Button(onClick = { Prefs.sgdbKey = key }) { Text("Guardar") }
    }
}
