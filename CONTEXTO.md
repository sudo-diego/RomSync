# Contexto de RomSync

App Android propia (Kotlin + Jetpack Compose, paquete `io.github.sudodiego.romsync`) para la **Lenovo Legion Y700
Gen 3** (256 GB): manejar ROMs, BIOS y partidas contra el disco duro del PC gamer. La escribió Claude (Claude Code); el
usuario la prueba en su tablet. Habla español neutro (sin voseo ni modismos). Emuladores: RetroArch, Dolphin,
**NetherSX2** (quiere pasarse a ARMSX2 sin perder partidas), ARMSX2, Eden, Vita3K, WatermelonDS, Azahar y GameNative.
Frontends: ES-DE / Cocoon (carpetas `ROMs/<plataforma>` y `bios`).

## Decisiones (tomadas con el usuario; respetarlas)

- **Sin programas ni scripts en el PC.** Se probaron y descartaron: un servidor en Go (.exe) y un "conector" .bat
  ("da miedo correr un .bat de una app vibe-codeada"). La tablet entra al PC por **SFTP** con el OpenSSH que trae
  Windows (como su app anterior, Nixel). Ajustes → PC guía en 3 pasos: activar «Servidor OpenSSH» con herramientas de
  Windows (Características opcionales + app Servicios), «Buscar mi PC» (prueba el puerto 22 en la red y lee el saludo
  SSH; Windows primero; el router aparece como "SSH-2.0-dropbear") y usuario + contraseña. La carpeta de ROMs se busca
  sola (`PcFinder.findRoms`) o se navega; nunca escribir rutas.
- **RomSync Web** (antes "Cocina"; el usuario pidió quitar ese nombre): la web la sirve la tablet (NanoHTTPD, puerto
  8642) al estilo de **Nonna's Kitchen** de Cannoli: QR, dirección y PIN de 6 caracteres que cambia cada vez.
- **Partidas: viven en la tablet** y se respaldan en el PC (zip por emulador en `RomSync/respaldos/<tablet>/<emu>/`,
  últimos 30, "conservar"). **Restaurar es juego por juego** (se ve qué trae un respaldo antes de tocar nada; antes de
  restaurar se respalda lo actual). Borrar un juego nunca toca partidas.
- **Una tarjeta por juego en la tienda:** las versiones (región, formato, con mods) se agrupan por el título del
  NOMBRE DEL ARCHIVO (`Repo.versionKey`), no por el nombre propio; adentro se elige la versión. `Advice` recomienda
  formato (WUA > carpeta en Wii U, .3ds > .cia > .zip, RVZ > ISO, CHD).
- Shizuku solo para Android/data (Dolphin, Eden, ARMSX2, NetherSX2, Vita3K): `tar -cf - -T -` / `tar -xof -` de toybox.
- Estilo de TBF Companion (su otra app, repo G9Control): paleta de los fondos de la Y700, confirmar lo que borra.
- Las APK de GitHub Actions se firman con `app/romsync-shared.keystore` (clave fija, no secreta) para instalar encima.

## Estructura

- `Group.kt`: agrupa archivos en juegos (.cue/.bin, .gdi, .m3u, discos sueltos "(Disc N)", carpetas PS3/Wii U, Switch
  por TitleID con base/update/DLC), ignora basura (`isIgnored`), clasifica sobrantes (`junkKind`), detecta BIOS
  (`isBiosFile`: nombre, compañeros .MEC/.NVM/.ROM1, tamaño 4 MB en ps2 / 512 KB en psx, .bin chicos en ps2).
- `SwitchId.kt`: TitleID de un .nsp/.nsz sin código en el nombre (lista PFS0 → nombre del .tik).
- `Sftp.kt` (JSch de mwiede): conexión, listar, reanudar, subir a .part, huella del PC, `exec` (comandos por SSH).
- `Pc.kt`: escanear la biblioteca (en Windows, la lista entera con PowerShell por SSH en una sola respuesta; si falla,
  carpeta por carpeta por SFTP), con avance en `Repo.scanStatus`. `PcEdit`: borrar del PC, crear .m3u.
- `PcFinder.kt`: buscar el PC en la red, discos y carpeta de ROMs. `Repo.kt`: estado, comparación, versiones, limpieza.
- `DownloadService.kt`, `Emulators.kt`, `Storage.kt`, `Tar.kt`, `Saves.kt` (respaldo/restauración por juego,
  `transferToArmsx2`), `Backups.kt`, `BackupWorker.kt`, `Art.kt` (SteamGridDB), `Names.kt` (nombres propios).
- `Kitchen.kt` + `assets/web/`: RomSync Web. Pantallas: Store, Game, Downloads, Saves, Kitchen (Web), Settings,
  `CleanupDialog.kt` (sobrantes y BIOS fuera de su carpeta), `ArtPicker.kt`.
- `AppScope.kt`: operaciones que no se pueden cortar (borrar, mover, respaldar) van en `appScope`, no en el scope de
  una ventana (daba "The coroutine scope left the composition" al mover BIOS).
- Investigación de rutas de partidas: `docs/INVESTIGACION.md`.

## Compilar y probar

- GitHub Actions compila y prueba en cada push (artefacto `RomSync-apk`). En el PC: Java 17, `.\gradlew.bat assembleRelease`.
- Pruebas JVM (`app/src/test`, `.\gradlew.bat testReleaseUnitTest`): agrupación, BIOS, sobrantes, Switch, tar, globs y
  SFTP + búsqueda de la carpeta de ROMs contra un servidor SSH de prueba (Apache MINA).
- Registros: `adb logcat -s RomSync` (Shizuku y errores).

## Pendiente (ver docs/PROMPT-SESION-ADB.md)

Lo que solo se puede revisar con la tablet y el PC reales.
