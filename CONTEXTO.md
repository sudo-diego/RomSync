# Contexto de RomSync

App Android propia (Kotlin + Jetpack Compose, paquete `io.github.sudodiego.romsync`) para la **Lenovo Legion Y700
Gen 3** (256 GB): manejar ROMs, BIOS y partidas contra el disco duro del PC gamer. La escribió Claude (Claude Code);
el usuario la prueba en su tablet. Emuladores: RetroArch, Dolphin, ARMSX2, Eden, Vita3K, WatermelonDS, Azahar y
GameNative. Frontends: ES-DE / Cocoon (carpetas `ROMs/<plataforma>` y `bios`).

## Decisiones (tomadas con el usuario)

- **Sin programa en el PC.** Se probó primero un servidor en Go (.exe con web); el usuario prefirió lo de su app
  anterior, **Nixel** (Flutter, github.com/sudo-diego/nixel): la tablet entra al PC por **SFTP** con el OpenSSH que trae
  Windows. Se quitó el servidor Go (está en el historial de git, commit 6998507).
- **La web es la "Cocina"**, como **Nonna's Kitchen** de Cannoli: la sirve la tablet (NanoHTTPD, puerto 8642), con QR,
  dirección y PIN de 6 caracteres que cambia cada vez. Desde el navegador: Plataformas (juegos de la tablet y del PC,
  subir arrastrando, instalar desde el PC, borrar, carátulas), BIOS, Partidas y Herramientas (archivos, APK).
- **Partidas: viven en la tablet** y se respaldan en el PC (zip por emulador, últimos 30, "conservar"). Borrar un
  juego nunca toca sus partidas (las extensiones de partidas se ignoran en todo: `Group.isIgnored`).
- **Shizuku solo para Android/data** (Dolphin, Eden, ARMSX2, Vita3K): `tar -cf - -T -` / `tar -xof -` de toybox.
- Estilo de TBF Companion (G9Control): paleta de los fondos de la Y700, textos en español neutro, confirmar lo que borra.

## Estructura

- `Group.kt`: agrupa archivos en juegos (.cue/.bin, .gdi, .m3u, carpetas de PS3/Wii U, Switch por TitleID). Puro JVM.
- `Sftp.kt` (JSch de mwiede): conexión, listar, leer desde un offset (reanudar), subir a .part y renombrar, huella del PC.
- `Pc.kt`: escanear la biblioteca del PC. `Repo.kt`: estado (biblioteca en caché, archivos de la tablet, comparación).
- `DownloadService.kt`: descargas en primer plano, una a la vez, a .part con reanudación.
- `Emulators.kt` (rutas de cada emulador), `Storage.kt` (directo o Shizuku), `Tar.kt`, `Saves.kt` (respaldo/restauración),
  `Backups.kt` (zips en el PC), `BackupWorker.kt` (automático con WiFi).
- `Art.kt`: SteamGridDB desde la tablet (clave en Ajustes), caché en `files/art`, imágenes subidas desde la web.
- `Kitchen.kt` + `assets/web/` (index.html, app.css, app.js): la Cocina.
- Pantallas: `StoreScreen`, `GameScreen`, `DownloadsScreen`, `SavesScreen`, `KitchenScreen`, `SettingsScreen`.
- Investigación de rutas: `docs/INVESTIGACION.md`.

## Compilar y probar

- GitHub Actions compila y prueba en cada push (artefacto `RomSync-apk`). En el PC: Java 17, `.\gradlew.bat assembleRelease`.
- Pruebas JVM (`app/src/test`): agrupación, globs, tar y SFTP contra Apache MINA SSHD.

## Pendiente (no probado en la tablet todavía)

- Todo lo que toca hardware: SFTP contra el OpenSSH real de Windows (rutas `/D:/…`), velocidad de descarga por WiFi,
  Shizuku + tar en Android/data (dueño de los archivos restaurados), PackageInstaller desde la Cocina.
- Vita3K: mostrar y borrar juegos instalados en `ux0/app` (ocupan espacio aparte del ROM).
- Navegación con el control (G9) en la app: hoy es la de Compose por defecto.
- Ideas: que TBF Companion avise a RomSync al cerrar un emulador para respaldar en ese momento.
