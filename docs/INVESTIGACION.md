# Investigación: dónde guarda cada emulador (Android, octubre 2026)

Revisado en el código fuente de cada emulador (sus repositorios en GitHub) y en guías de la comunidad. Las rutas
con `Android/data/<paquete>` son privadas desde Android 11: una app normal no las puede leer, por eso RomSync usa
**Shizuku** (el shell de Android sí tiene acceso). Las del almacenamiento compartido se leen directo con
"Acceso a todos los archivos".

| Emulador | Paquete | Partidas | ¿Shizuku? | Exportar sin Shizuku |
|---|---|---|---|---|
| RetroArch | `com.retroarch.aarch64`, `com.retroarch`, `com.retroarch.ra32` | Lo dice `retroarch.cfg` (`savefile_directory`, `savestate_directory`; `:` = carpeta de la app). Por defecto `RetroArch/saves` y `RetroArch/states` o `Android/data/<paquete>/files/{saves,states}`. Con `savefiles_in_content_dir` quedan junto al ROM. | Solo si están en Android/data | Ajustes → Directorio |
| Dolphin | `org.dolphinemu.dolphinemu` | `Android/data/…/files`: `GC/` (tarjetas de memoria .raw o carpetas GCI), `Wii/title/*/*/data` (partidas de Wii), `StateSaves/`. Antes de Android 11 era `/sdcard/dolphin-emu`. | Sí | Ajustes → Configuración → Datos de usuario → Exportar. Dolphin también aparece como "proveedor de documentos" en el explorador de Android. |
| ARMSX2 | `com.armsx2` (GitHub), `come.nanodata.armsx2` (Play) | La "DataRoot": `getExternalFilesDir()` (`Android/data/…/files`) o la carpeta que elijas en el asistente (preferencia `systemDir`). Dentro: `memcards/` y `sstates/` (como PCSX2). | Sí, salvo carpeta propia | Desde 2.6.5.3: herramienta de respaldo/restauración (zip con tarjetas, estados y ajustes). |
| Eden (Switch) | `dev.eden.eden_emulator` | `Android/data/…/files/nand/user/save/0000000000000000/<usuario>/<TitleID>`. Los perfiles en `nand/system/save/8000000000000010` (se respaldan juntos para que el usuario coincida al restaurar). La NAND se puede mover (Configuración → Sistema → Sistema de archivos). | Sí, salvo NAND movida | Ajustes → Administrar datos → Partidas guardadas → Exportar. |
| Vita3K | `org.vita3k.emulator` | `Android/data/…/files/vita/ux0/user/00/savedata/<TITLEID>` (y trofeos en `ux0/user`). Los juegos se **instalan** en `ux0/app/<TITLEID>`: el .vpk/.pkg se puede borrar después. | Sí | Aparece como "Vita3K" en el explorador de archivos de Android. |
| WatermelonDS | `me.magnum.melondualds` (+ `.nightly`) | Junto al ROM (`.sav` o `.srm`, elegible). Si un frontend abre un ROM que no escaneó, en `Android/data/…/files/saves`. | Solo para esa carpeta | Están junto a los ROMs. |
| Azahar (3DS) | `org.azahar_emu.azahar` (Play: `io.github.lime3ds.android`) | La carpeta de usuario que eliges al configurarlo (normalmente `Azahar/`). Partidas en `sdmc/Nintendo 3DS/<id0>/<id1>/title/00040000/<id>/data`, datos extra en `extdata`, estados en `states/`. En `title/0004000e` y `0004008c` están las actualizaciones y DLC instalados (no se respaldan: ocupan mucho). | No | Están en tu almacenamiento. |
| GameNative | `app.gamenative` | Steam Cloud | — | Se sincroniza solo. |

## Actualizaciones y DLC de Switch

Desde Eden 0.2.0 las actualizaciones, DLC y mods se pueden usar **sin instalarlos en la NAND**: Ajustes →
Administrar carpetas de juegos → + → Carpeta de contenido externo. Así quedan como archivos normales y RomSync los
trata como parte del juego (agrupa por TitleID: base `…000`, actualización `…800`, DLC `base + 0x1000 + n`) y los
puede bajar o borrar junto con él.

Ojo: hay un reporte (issue de Eden 0.2.0-rc1) de que al abrir el juego desde un frontend (Cocoon, Beacon) no carga
el contenido externo; desde Eden sí. Si te pasa, instala la actualización en la NAND o usa un archivo ya mezclado.

## Cómo copia RomSync lo que está en Android/data

Con Shizuku corre el `tar` de Android (toybox) como el shell, sin archivos intermedios:

- Respaldar: `cd <carpeta> && tar -cf - -T -` (la lista de archivos entra por stdin; el tar sale por stdout y la
  app lo pasa a un zip).
- Restaurar: `mkdir -p <carpeta> && cd <carpeta> && tar -xof -` (`-o`: sin cambiar dueño).

Confirmado en el código de toybox: soporta `-T` (lista desde un archivo o `-`), `-o` y nombres largos de GNU.

## Fuentes

- Código: [Dolphin](https://github.com/dolphin-emu/dolphin) (`DirectoryInitialization.kt`, `DocumentProvider.kt`),
  [ARMSX2](https://github.com/ARMSX2/ARMSX2) (`MainActivityRuntime.kt`), [Vita3K-Android](https://github.com/Vita3K/Vita3K-Android)
  (`VitaDocumentsProvider.java`), [Azahar](https://github.com/azahar-emu/azahar) (`build.gradle.kts`, `DirectoryInitialization.kt`),
  [WatermelonDS](https://github.com/SapphireRhodonite/WatermelonDS) (README), [toybox](https://github.com/landley/toybox) (`tar.c`).
- [Joey's Retro Handhelds: dónde están las partidas de cada emulador](https://www.joeysretrohandhelds.com/resources/where-to-find-save-files-for-every-emulator-and-custom-firmware/)
- [Android Authority: Eden, actualizaciones y DLC sin NAND](https://www.androidauthority.com/eden-switch-dlc-updates-storage-3641207)
- [Eden: issue del contenido externo con frontends](https://github.com/eden-emulator/Issue-Reports/issues/367)
- [Releases de ARMSX2](https://github.com/ARMSX2/ARMSX2/releases)
- [melonDS Android: partidas](https://github.com/rafaelvcaetano/melonDS-android/wiki/Save-Files)
- [Cannoli: Nonna's Kitchen](https://cannoli.dev/documentation/nonnas-kitchen/) (la idea de la Cocina)
