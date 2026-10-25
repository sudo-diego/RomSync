# RomSync

App Android (Kotlin + Jetpack Compose) para manejar los ROMs, BIOS y partidas de una tablet o teléfono Android
contra el disco duro del PC, **sin instalar nada en el PC** (usa el servidor OpenSSH que trae Windows, como Nixel).

## Qué hace

- **Tienda** (estilo eShop): la biblioteca del PC y la de la tablet juntas, con carátulas de SteamGridDB.
  Cada juego dice cuánto ocupa y cuánto espacio queda; descargar pide confirmación de espacio, borrar pide
  confirmación y nunca toca las partidas.
- Entiende juegos de varios archivos: `.cue/.bin`, `.gdi`, `.m3u` con sus discos, carpetas de PS3/Wii U y los de
  **Switch con su actualización y DLC** (por TitleID; eliges qué partes bajar).
- **Descargas** en segundo plano, con pausa y reanudación si se corta.
- **Partidas**: respaldo en el PC de RetroArch, Dolphin, ARMSX2, Eden, Vita3K, WatermelonDS y Azahar (manual o
  automático cada N horas con WiFi), historial y restauración. Las de Android/data necesitan **Shizuku**.
  Ver [docs/INVESTIGACION.md](docs/INVESTIGACION.md).
- **Web**: la tablet sirve una web con QR y PIN. Desde el navegador del PC:
  plataformas y juegos (subir arrastrando, instalar desde el PC, borrar, carátulas), BIOS, partidas (bajar en zip,
  respaldar, restaurar) y herramientas (explorador de archivos, instalar APK).

## Preparar el PC (una vez)

1. Configuración → Sistema → Características opcionales → Agregar → **Servidor OpenSSH**.
2. PowerShell como administrador:
   ```powershell
   Start-Service sshd
   Set-Service -Name sshd -StartupType Automatic
   ```
3. En la app: Ajustes → PC: la IP del PC (`ipconfig`), tu usuario y contraseña de Windows y la carpeta de ROMs
   (por ejemplo `D:\Emulación\ROMs`, con una carpeta por plataforma como ES-DE: `psx`, `ps2`, `switch`…, y `bios`).

## Compilar

GitHub Actions compila la APK en cada push (pestaña Actions → artefacto `RomSync-apk`). En el PC, igual que TBF
Companion: Java 17 y `.\gradlew.bat assembleRelease`.

Pruebas (en la JVM): `.\gradlew.bat testReleaseUnitTest` (agrupación de juegos, tar, globs y SFTP contra un
servidor SSH de prueba).
