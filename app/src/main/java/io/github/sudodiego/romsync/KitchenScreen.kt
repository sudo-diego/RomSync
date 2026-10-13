package io.github.sudodiego.romsync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun KitchenScreen() {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column {
                Text("RomSync Web", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Muted("Administra la tablet desde el navegador del PC o del teléfono: juegos, BIOS, carátulas, partidas y archivos.")
            }
        }
        item {
            Section("") {
                if (!Kitchen.running) {
                    Muted("Enciéndela y abre la dirección en un equipo de la misma red WiFi. El PIN cambia cada vez.")
                    Button(onClick = { Kitchen.start(ctx) }) { Text("Encender la web") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
                            Qr(Kitchen.url, Modifier.size(200.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Muted("Abre en el navegador")
                            Text(Kitchen.url, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Muted("y escribe el PIN")
                            Text(Kitchen.pin, fontFamily = FontFamily.Monospace, fontSize = 44.sp, letterSpacing = 8.sp,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Button(onClick = { Kitchen.stop(ctx) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Apagar") }
                        }
                    }
                }
            }
        }
        item {
            Section("Qué se puede hacer") {
                Text("• Plataformas: ver los juegos de la tablet y del PC, subir ROMs arrastrándolos, borrar, cambiar carátulas.")
                Text("• Instalar desde el PC: la tablet lo descarga del disco por SFTP, como en la Tienda.")
                Text("• BIOS: ver las que faltan, copiarlas del PC o subirlas.")
                Text("• Partidas: bajar las de cada emulador en un zip, respaldarlas en el PC o restaurar subiendo un zip.")
                Text("• Herramientas: explorador de archivos de la tablet e instalar APK.")
            }
        }
    }
}

@Composable
fun Qr(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }.getOrNull() }
    if (matrix == null) return
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
