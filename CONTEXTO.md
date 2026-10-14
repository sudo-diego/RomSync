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

- GitHub Actions compila y prueba en cada push (artefacto `RomSync-apk`). En el PC: Java 17, `.\gradlew.bat assembleRelease`
  (en el PC del usuario no hay JDK 17: sirve el JBR 21 de Android Studio, `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`;
  `local.properties` con `sdk.dir=C:/Users/diego/AppData/Local/Android/Sdk`, con barras normales).
- Pruebas JVM (`app/src/test`, `.\gradlew.bat testReleaseUnitTest`): agrupación, BIOS, sobrantes, Switch, tar, globs y
  SFTP + búsqueda de la carpeta de ROMs contra un servidor SSH de prueba (Apache MINA).
- Registros: `adb logcat -s RomSync` (Shizuku y errores). adb: `%LOCALAPPDATA%\Android\Sdk\platform-toolsdb.exe`; la
  tablet se conecta sola por depuración inalámbrica (mDNS), no por `192.168.1.95:5555`. Desde Git Bash usar
  `MSYS_NO_PATHCONV=1` (si no, `/sdcard/...` se convierte en una ruta de Windows). Para copiar de Android/data al PC:
  `cp -r` a `/sdcard/Download/...` y `adb pull` desde ahí (`adb pull` directo y `exec-out tar` fallaron).

## Lo que se vio en la tablet y el PC reales (sesión con adb, 13-10-2026)

- PC: `D:\ROMs` (~60 000 archivos), respaldos en `D:\RomSync\respaldos\TB321FU`. El shell de OpenSSH es cmd (sin
  `DefaultShell`): la lista con PowerShell funciona y la lectura del PC entera tarda < 30 s. Con 60 000 archivos el heap
  llega a ~160 MB: hubo un OutOfMemoryError con el límite de 256 MB → `largeHeap="true"`.
- Se cayó la tienda con "Key … was already used": un juego solo en la tablet con el mismo id que uno del PC (Switch:
  actualización en la tablet, base en el PC; los dos `tid:<TitleID>`). `Repo.compare` le da otro id.
- `ps2/BIOS/` (en los dos lados) trae subcarpetas de un zip de Mac: `.DIFF` de 4 MB, `.INF` y `__MACOSX/._*.BIN` de
  187 B. Ya no salen como juegos (`isBiosFile`: todo lo que está en una carpeta "bios", y .diff/.inf con "scph"). ARMSX2
  sí muestra los `._SCPH….BIN` como juegos: conviene borrar `ps2/BIOS` (lo útil ya está en `ROMs/bios`).
- Versiones: la carpeta de Wii U "THE LEGEND OF ZELDA The Wind Waker HD [Game] [0005…]" y "The Wind Waker HD (US).wua"
  no se juntaban → `Group.versionTitle` quita "The" y el prefijo "Legend of Zelda"/"Zelda" (si queda subtítulo).
- Carátulas: el contador llega a 0 (las no encontradas no cuentan). SteamGridDB da a veces otro juego primero
  ("Aladdin" → "Aladdin - Hidden Objects Game"): ponerla a mano funciona. Las miniaturas tardan; ahora tienen fondo.
- Sobrantes: borrar funciona en los dos lados (probado con archivos de prueba); las copias .SAV(n) salen sin marcar.
- .m3u de Final Fantasy VII: creado en el PC, un solo juego, al bajar "lo que falta" baja solo el .m3u (111 B).
- Dolphin: respaldar y restaurar UN juego funciona (solo cambia ese .gci). Lo restaurado queda con dueño `shell`
  (tar de Shizuku) y Dolphin lo sigue escribiendo bien (Android/data se controla por carpeta y grupo ext_data_rw).
  Al restaurar, la fecha del archivo pasa a ser la de la restauración.
- Vita3K del usuario guarda todo en `/sdcard/Vita` (no en Android/data, que quedó vacía): los respaldos salían vacíos.
  `Emulators.vitaRoot` elige la carpeta con datos. Gravity Rush está instalado en `/sdcard/Vita/ux0/app/PCSA00011`
  (1,4 GB); `ROMs/psvita/GravityRush.dpt` solo dice `[vita_game_id] PCSA00011`.
- NetherSX2 → ARMSX2 (hecho y verificado): NetherSX2 tiene `Mcd001.ps2` y `Mcd002.ps2` (vacías, mismo hash) y
  `hola123.ps2`, una tarjeta de CARPETA con las partidas (God of War, Persona 3 FES). ARMSX2 2.8.3 recién instalado
  no tenía carpeta de datos hasta pasar su asistente (se eligió Internal = Android/data/com.armsx2/files, BIOS en
  `ROMs/bios`, juegos en `ROMs/ps2`). El traspaso copió las 20 piezas idénticas (md5), pero ARMSX2 deja Mcd001 en el
  slot 1: hubo que poner hola123.ps2 en el slot 1 (Memory Cards). Persona 3 FES en ARMSX2 carga la partida
  (Diego, PLV 33, 35 h 25 m). Ahora RomSync lo avisa al terminar. Antes: NetherSX2 buscaba también en /sdcard/ARMSX2
  (habría copiado de ARMSX2 a ARMSX2); ahora solo ARMSX2 lo hace. Respaldo manual en
  `D:/RomSync/respaldos/adb-manual-2026-10-13/nethersx2` (salvo `gamesettings/94A82AAA.ini`, que ni el shell puede leer).

## Limpieza de la biblioteca (14-10-2026, con permiso del usuario)

Todo lo quitado está en `D:\RomSync\papelera-2026-10-13` (26 GB) y `/sdcard/RomSync/papelera-2026-10-13` (92 MB):
nada se borró de verdad. Las partidas de DS reemplazadas también quedaron en `D:/RomSync/respaldos/limpieza-2026-10-13`.
- Wii U: 7 carpetas (base/update/DLC, ~60 000 archivos, 23 GB) que estaban completas dentro de los .wua (se comparó
  el TitleID y la versión de cada carpeta con los que trae cada .wua). La lectura del PC pasa de 60 000 a ~460 archivos.
- 3DS: `Minerva_Myrient/` (zips cortados de Pokémon Moon y X; los .3ds buenos ya estaban).
- GameCube: Paper Mario TTYD .iso (está el .rvz) y Twilight Princess Europa (se queda la USA, que está en la tablet).
- DS: Pokémon Negra "[b]" (dump malo) y partidas viejas numeradas ("4527 - …", "5597 - …", un .sav vacío). Se subieron al
  PC Pokémon Negra, Oro HeartGold y Perla que solo estaban en la tablet; se renombraron 3 partidas numeradas para que
  coincidan con su ROM ("0022 - Super Mario 64 DS (Europe)….sav" → "Super Mario 64 DS.sav").
- BIOS: `ps2/BIOS` entera, PS2 que nadie usa (ARMSX2 usa la SCPH-39001, NetherSX2 tiene las suyas en su carpeta):
  quedan SCPH-39001 (USA) y SCPH-70004 (Europa) con .MEC/.NVM; `ps-30a.zip` (igual al .bin, mismo CRC).
  `bios/Mupen64plus/mupen64plus.ini` NO es BIOS pero es la configuración del núcleo de N64: se queda. Tampoco son BIOS
  pero se quedan: `bios/dc/` (memoria y VMU de Flycast = partidas de Dreamcast) y `bios/swanstation/` (caché).
- "Falta 1 BIOS en la tablet" era falso: la tablet ignoraba `mupen64plus.ini` (.ini) y el PC no. Ahora en la carpeta
  bios solo se ignora basura del sistema (`Group.isSystemJunk`), y Descargas dice cuáles faltan.

## Cambios de la app en esa sesión

- Ficha del juego: sección Partidas (respaldar/restaurar SOLO ese juego, por emulador; `GameSaves.kt` sabe la clave de
  cada juego: código del disco GC/Wii leído de la cabecera —RVZ lo copia en 0x58—, TitleID de Switch/3DS, ID de Vita,
  o el nombre del archivo); sección Vita3K ("Instalado en Vita3K · 1,4 GB" y "Quitar de Vita3K", solo `ux0/app/<ID>`);
  "Copiar al PC" para juegos solo en la tablet (`PcEdit.uploadFromTablet`). Las partidas de PS2 son tarjetas compartidas.
- .m3u: se crea en el PC y también en la tablet si los discos están ahí.
- Imágenes: al leer la biblioteca se bajan solas carátula, fondo y logo de todo (antes el fondo y el logo solo al abrir
  la ficha). Nombres oficiales de SteamGridDB si coinciden con el archivo (o si se elige a mano); las coincidencias
  viejas se revisan con `/games/id` y las que eran otro juego se vuelven a buscar.
- Nombres: `Group.prettyTitle` ("Legend of Zelda, The - The Wind Waker" → "The Legend of Zelda: The Wind Waker";
  quita "0022 - "). Wind Waker HD sale "The Legend of Zelda: The Wind Waker HD" (nombre oficial).
- Tienda: filtros Todos / En la tablet / Para descargar / Sin copia en el PC. "Repetidos" y las tarjetas de sobrantes y
  BIOS se juntaron en "Ordenar tu biblioteca" (`Organize.kt`: 4 pasos con recomendación de qué versión conservar).
- Respaldos: cada uno guarda qué es (completo, automático, un juego, antes de restaurar, traspaso) y qué juegos trae;
  los viejos se leen una vez y se anotan en su .json. La lista muestra "Solo Mario Party 7 · ayer, 22:48". Las partidas
  de un juego se agrupan por clave (la tarjeta .gci y los estados .s00 de Dolphin juntos). Los códigos de disco del PC
  se leen al escanear (para nombrar partidas aunque el juego no esté en la tablet; 3 letras = juego, la 4.ª región).

## Más cambios (14-10-2026)

- Respaldos en el PC, por emulador: últimos `Prefs.keepBackups` completos/automáticos (10, elegible 5/10/20 en
  Partidas), 5 de cada juego y 5 de seguridad; "conservar" nunca se borra (`Backups.toPrune`).
- Tienda: selección múltiple (mantener presionada una tarjeta) para borrar de la tablet o del PC de una vez.
- Ficha: "Versión: Europe · RVZ · 830 MB ▾" abre la lista de versiones con dónde está cada una y la recomendada.
- Ordenar → repetidos: "Me quedo con todas" (`Prefs.keepAllVersions`): al usuario le gusta coleccionar versiones.
- Steam: los accesos del PC (`D:/ROMs/steam`) se movieron a la papelera (pedido del usuario: no sirven en el PC).
- Gravity Rush: `D:/ROMs/psvita/Gravity Rush (USA) [PCSA00011].vpk` armado desde `/sdcard/Vita/ux0/app/PCSA00011` + la
  licencia (`ux0/license/PCSA00011/*.rif`) como `sce_sys/package/work.bin`. No se probó reinstalarlo.
- Switch: el PC solo tiene juegos base (v0). Las actualizaciones y DLC están INSTALADAS en la NAND de Eden (6,3 GB, no
  son archivos de ROMs): Smash (actualización 3,7 GB + ~85 DLC), Breath of the Wild (actualización 950 MB + 2 DLC
  1,3 GB) y Celeste (actualización 444 MB). Se identificó descifrando la cabecera de cada NCA con `header_key` de
  `bios/prod.keys` (AES-XTS, número de sector big-endian; TitleID en 0x210, tipo en 0x205).
- Wind Waker GameCube "USA" (278 MB) tiene nombre propio "The Legend of Zelda: The Wind Waker Mod": es la versión con
  mods, no un repetido.

- Actualización o DLC de Switch que está solo en la tablet y cuyo juego está en el PC: sale DENTRO de la ficha de ese
  juego ("Solo en la tablet" + "Copiar al PC"; `Repo.tabletExtras`) y en Ordenar → sin copia en el PC. Probado con la
  actualización de Bayonetta 2 (que el usuario había puesto en la tablet). Contenido de Switch: "Marcar todo /
  Desmarcar todo" y el nombre de cada DLC sacado del archivo. El usuario confirma que Cocoon ignora la carpeta con
  `noload.txt`.

## Limpieza de la tablet (14-10-2026, madrugada)

- Banjo-Kazooie: `Download/…z64` era el mismo juego que `ROMs/n64/….n64` (mismo contenido al normalizar el orden de
  bytes). Queda el .z64 (formato estándar) en `ROMs/n64` de los dos lados; el .n64 a la papelera.
- **Partidas de Wii**: el Dolphin actual (reinstalado el 28-09) tenía `Wii/title` vacío; las únicas copias estaban en
  `Download/dolphin-emu` (una exportación del 05-08: Mario Galaxy 1 y 2, Brawl, Wii Sports/Resort, Mario Party 8,
  NSMB Wii, Wii Party…). Se copiaron a Dolphin (284 archivos, verificados) y ya salen en los respaldos de Dolphin.
  La exportación entera quedó en `D:/RomSync/respaldos/TB321FU/dolphin-exportacion-2026-08-05` y se borró de la tablet.
- Borrado (confirmado por el usuario o copia exacta): Wind Waker .rvz a medias, IPL.bin ×2 (= las de Dolphin, que van
  en sus respaldos), exportaciones viejas de partidas de Eden (las partidas están en los respaldos de Eden), Celeste en
  Download, y copias "(1)" de apk/zip/torrent.
- Cocoon usa hoy `/sdcard/Cucun` (sus capturas son copias de Pictures/Screenshots para su galería: no tocar).
  `/sdcard/Cocoon Shell` (1,3 GB) es la carpeta vieja, sin cambios desde agosto: no se borró (preguntar).
- `2S2H/MM.z64` = Majora's Mask de `ROMs/n64`: lo necesita el port 2Ship2Harkinian, se queda.

## Rediseño estilo eShop (14-10-2026)

- Look: eShop de Switch en estructura (panel izquierdo que se colapsa a íconos, `Sidebar` en MainActivity; se colapsa
  solo al abrir un juego) con la vibra de Wii U / 3DS: naranja 3DS (`Palette.orange`) + azul Wii U, fondo claro con
  burbujas que flotan (`BubblesBackground`), letra Nunito (res/font, OFL; licencia en tools/Nunito-OFL.txt).
  Tema claro por defecto y oscuro en Ajustes (`UiState.theme`, `Prefs.theme`; los íconos de la barra de estado se
  ajustan solos).
- Tienda: inicio con carrusel de destacados (juegos con hero; pasa cada 6 s, paralaje y escala) y filas por tema
  ("En tu tablet", "Para descargar" y por plataforma, con "Ver todo"); al buscar o filtrar aparece la grilla con
  `animateItem`. Tarjetas con sombra, rebote al tocar, aparición suave; el estado es un ícono (✓ verde, ámbar a medias,
  % descargando) en vez de texto. Estado del PC en una píldora con punto de color.
- Ficha: hero más alto con paralaje al hacer scroll, portada que entra con rebote, píldoras (plataforma, tamaño,
  formato) en vez de una línea de texto, archivos plegados. Transiciones entre pantallas con `AnimatedContent`.
- Sonido (`Sound.kt`): música de fondo y 6 sonidos, todos ORIGINALES generados por `tools/sintetizar.py` (bossa en Fa,
  34 s, loop sin corte con AudioTrack estático + setLoopPoints; sonidos por SoundPool). Foco de audio (se calla si otra
  app suena), pausa al salir de la app (onStop). Ajustes → Sonido y aspecto: interruptores y volumen de música y
  sonidos. Los botones de Material 3 usan su propia onda (no `LocalIndication`), así que el sonido está puesto a mano en
  las acciones principales; `SoundIndication` cubre los `clickable` comunes.
- Menos texto en todas las pantallas (Partidas, Ajustes, Web, Descargas, ficha).
- Probado en la tablet: navegación por todas las pestañas, búsqueda, selección múltiple, panel, tema oscuro/claro,
  música que se pausa al ir al inicio y vuelve; monkey de 400 toques sin cierres (y sin cambios en archivos).

## Segunda vuelta de diseño (14-10-2026, pedido del usuario)

- Aspecto: tema claro/oscuro/del sistema (`isDark()`), color principal elegible (8 colores o código hex; el esquema se
  arma desde ese color en `schemeFor`), fondo: burbujas, ondas estilo XMB o sin animación (`AppBackground`). El fondo
  usa tiempo continuo (`withFrameNanos`), no un ciclo: ya no "salta" al repetirse.
- Música: 3 pistas originales de ~1-2 min con secciones (bossa, lo-fi, 8 bits; `tools/sintetizar.py` → .ogg con
  ffmpeg, 1,3 MB en total) decodificadas a PCM con MediaCodec y en loop sin corte; o una canción propia del usuario
  (MediaPlayer en loop; permiso persistente al URI). 3 juegos de sonidos (clásico, suave, retro).
- Tienda: destacados = juegos que NO están en el dispositivo (cambian cada día); filtros en una línea + "Plataforma ▾".
  Al abrir un juego, fundido con leve zoom (el deslizamiento dejaba ver una franja mientras el panel se cerraba).
- Lenguaje genérico: "dispositivo" y "servidor" en vez de tablet/PC en toda la app y la web.
- Ficha: tocar la portada abre el editor (nombre, carátula, fondo, logo; `ArtPicker(kind)`); menú ⋮ (editar, crear
  .m3u, borrar); un solo "Borrar…" con casillas: del dispositivo, del servidor y sus partidas (se respaldan antes;
  `Saves.deleteUnit`, `Storage.delete`). Juegos de varios discos muestran "Contenido" (Disco 1, 2… y el .m3u).
- Partidas: nombres de juego en vez de códigos (3DS por cabecera NCSD, también leída del servidor; extdata con el
  código del medio; estados de Azahar con su juego; Dolphin IPL/SRAM = "Archivos del sistema"; Saturn backup.bin =
  "Memoria interna de Saturn"; códigos bajos de 3DS = "Datos del sistema de 3DS"). "Respaldar un juego" salió de
  Partidas (está en la ficha).
- Pruebas: monkey de 400 toques sin cierres; ojo, el monkey puede iniciar descargas (se canceló una de Smash).

## Pendiente

- Switch: mostrar en la ficha lo instalado en Eden y, con titledb (blawar/titledb: versions.json, cnmts.json), la
  última versión y los DLC que existen. Propuesto al usuario.
- Vaciar las papeleras cuando el usuario confirme que todo anda bien.
