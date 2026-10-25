# Contexto de RomSync

App Android propia (Kotlin + Jetpack Compose, paquete `io.github.sudodiego.romsync`) para la **Lenovo Legion Y700
Gen 3** (256 GB, pantalla 2560×1600 de hasta 165 Hz): manejar ROMs, BIOS y partidas contra el disco del PC gamer
(Windows, `D:\ROMs`). La escribe Claude (Claude Code); el usuario la prueba en su tablet y decide el diseño. Español
neutro en la app y en las respuestas (sin voseo ni modismos).

- Emuladores: RetroArch, Dolphin, ARMSX2 (antes NetherSX2), Eden (Switch), Cemu, Azahar (3DS), Vita3K, WatermelonDS /
  melonDS, PPSSPP; además GameNative. Frontends: ES-DE / Cocoon (carpetas `ROMs/<plataforma>` y `ROMs/bios`).
- El historial detallado de cada ronda (qué se vio, qué se cambió y por qué) está en `docs/HISTORIAL.md`.
- Investigación de rutas de partidas de cada emulador: `docs/INVESTIGACION.md`.

## Decisiones (tomadas con el usuario; respetarlas)

- **Sin programas ni scripts en el PC.** La tablet entra al PC por **SFTP** con el OpenSSH que trae Windows. Se
  descartaron un servidor en Go y un .bat ("da miedo correr un .bat de una app vibe-codeada").
- **Partidas: viven en el dispositivo** y se respaldan en el PC (zip por emulador en
  `RomSync/respaldos/<dispositivo>/<emu>/`). Restaurar es juego por juego y antes se respalda lo actual. Borrar un juego
  nunca toca sus partidas: se borran aparte, en la sección Partidas de la ficha.
- **Nada se borra ni se cambia sin verlo y confirmarlo.** Antes de borrar una copia "repetida", se compara byte a byte.
- **Una tarjeta por juego** en la tienda (las versiones se eligen adentro). Switch se agrupa por TitleID del juego base.
- **Borrar del dispositivo** es el botón grande de la ficha; **borrar del servidor** solo desde el menú ⋮.
- Sin Toast de Android: todos los avisos son `Notice` (arriba a la derecha, con imagen y animación).
- Diseño: inspirado en las eShop de Wii U / 3DS / Switch y en su otra app (TBF Companion, repo G9Control). Color
  destacado elegible (todo lo principal lo usa, también el botón de descargar). Sonidos propios (sfx sintetizados) y
  música del usuario.
- Las APK de GitHub Actions se firman con `app/romsync-shared.keystore` (clave fija, no secreta) para instalar encima.
- Releases en GitHub: solo cuando el usuario lo pide en ese momento.

## Cómo funciona (por pantalla)

- **Mando** (inspirado en Steam Big Picture). Regla: lo mismo se hace igual en todas partes.
  - Anillo de foco y sonido en todo: botones de Material envueltos con el mismo nombre (`PadButtons.kt`), el resto con
    `focusHighlight` o la `Indication` de `Theme.kt`; cada movimiento de cruceta suena (`dispatchKeyEvent`).
  - Cruceta y stick izquierdo: Android los convierte en flechas (no hacerlo a mano: esas flechas no movían el foco);
    de una diagonal solo cuenta la primera dirección (`heldDpad` en `dispatchKeyEvent`). L3/R3 no hacen nada.
  - B va directo a "volver" (`onBackPressedDispatcher`; Compose lo usaba para salir de grupos de foco y se perdía):
    ficha → donde se entró; sección de la Tienda (plataforma, filtro, Ver todo) → inicio; pantalla principal → panel
    (se despliega); panel plegado → se despliega; panel desplegado → cierra la app. Derecha desde el panel vuelve a lo
    último elegido de la sección (`Nav.lastFocus`, lo anota `focusHighlight`); A en el panel entra a la sección y el foco
    va a su primer elemento (`Nav.enter` + `focusWhen("entrada:TAB")`; Tienda: carrusel; si no hay nada, queda en el panel).
  - Animación al abrir una ficha elegible en Ajustes (Acercar, Desde el lado, Desde abajo, Ninguna; `UiState.openAnim`),
    sin animar el tamaño (`using null`: si no, la ficha crecía desde arriba a la izquierda).
  - La ficha se abre ENCIMA de la sección, que sigue viva debajo (invisible y fuera del mando): al volver todo está igual.
    Mientras cambia de pantalla el panel no acepta el foco (`Nav.switching`), así no se "pasa" por él.
  - Cada pantalla es un grupo (`Modifier.screenFocus`): la cruceta no sale por arriba/abajo/derecha (nunca cae en el botón
    del panel, que es solo táctil) y, si no hay más elementos en esa dirección, la lista se desplaza hasta el final o el
    principio (se ve el encabezado y lo último, como Shizuku en Ajustes).
  - Cuadrícula de la Tienda: arriba/abajo van al de la misma columna (`gridNav`) y desplaza solo lo justo (saltar una fila de más
    redibujaba las carátulas); el título dice plataforma y/o filtro.
  - Carrusel: un solo elemento (izquierda/derecha cambian de página); A abre al SOLTAR (si no, el mismo A apretaba
    Descargar en la ficha). Al empezar una descarga el foco pasa a Pausar.
  - Guía de botones por pantalla (`PadHints`, una pila: la ficha encima de la sección), solo con mando (`Nav.pad`).
    L/R sección; ZL/ZR filtros de la Tienda; X buscar, Y vista, − actualizar, + orden.
  - Filas: lo enfocado centrado a lo ancho (`CenteredFocus`); hacia abajo solo se mueve si no se ve entero (`MarginFocus`).
    Campos de texto: el teclado sale con A. Opciones (`OptionRow`) y deslizadores (`padSlider`): izquierda/derecha cambian.
- Sombras: dibujadas como parte del contenido (`Modifier.softShadow`), no las del sistema: las del sistema no se
  desvanecen con las transiciones de pantalla y "saltaban". Las carátulas animan su aparición solo la primera vez.
- **Tienda** (`StoreScreen.kt`): carrusel de destacados (al azar por apertura; la cruceta cambia de página y el foco va
  con ella), plataformas (tarjeta con el ícono del emulador elegido para esa plataforma; se cambia manteniendo
  presionado o con el chip al filtrar), "En tu dispositivo", "Para descargar" en tiles anchos (fondo + logo) y un solo
  "Explorar" con pestañas de plataforma, cada uno en su panel con ícono y color (`StorePanel`); hasta 10 juegos por fila y
  la última tarjeta es "Ver todo"; "En tu dispositivo" y "Explorar" al azar por apertura. Los comprimidos no salen. Buscar, filtros, orden, vista de carátulas o de íconos (menú Wii U). Sin
  conexión se ve la última lista guardada con un aviso y "Reintentar". Los accesos directos del frontend (`.dpt`) de un
  juego que ya está en la biblioteca no salen como otra tarjeta.
- **Ficha** (`GameScreen.kt`): fondo con paralaje (se desvanece con máscara hacia el fondo de la app), carátula
  editable, estado, botón grande **Descargar** (color destacado, tamaño) y **Borrar** (solo si está en el dispositivo),
  versiones, Switch: "Actualizaciones y DLC" (actualización al día o cuál falta, DLC que tienes de los que existen,
  los que faltan, según titledb) y "Lo que tienes" (partes marcables), **Partidas** por emulador (en el dispositivo:
  "jugaste hace X"; respaldo al día / desactualizado / nunca; aviso si hace 2+ semanas que no se respalda; respaldar,
  restaurar, borrar del dispositivo), Vita3K, archivos. Copiar al servidor (juegos solo en el dispositivo) con
  velocidad, tiempo y Cancelar.
- **Descargas** (`DownloadsScreen.kt`): resumen (falta, velocidad, tiempo, espacio que queda, pausar/seguir todo), la
  activa en grande con su fondo y la animación elegida, cola, con problemas y recién terminadas (se van solas a los 6 s).
  Al terminar: jingle + aviso con la imagen del juego. Cancelar pide confirmación y borra lo bajado.
- **Animaciones de descarga** (`DownloadArt.kt`, Ajustes → Aspecto): Clásica (anillo), 3DS (cubo del ícono que se llena
  de bloques con cubitos colgando de una línea; al terminar queda envuelto en un regalo que se abre al entrar a la
  ficha) y Switch (barra abajo). Se ven en las carátulas de la Tienda, en Descargas y en "Sin copia".
- **Partidas** (`SavesScreen.kt`): resumen (al día / con cambios / sin respaldo) + "Respaldar todo"; tarjetas por
  emulador con ícono y estado; restaurar en una hoja grande: respaldos por día y, adentro, cada partida con su carátula
  y si es más nueva o más vieja que la del dispositivo (si es más vieja, pide confirmar que se pierde el progreso).
  Respaldo automático cada N h (WorkManager).
- **Organizar** (`OrganizeScreen.kt`, `Organize.kt`, `Tidy.kt`): resumen; "Juegos" (por ordenar, repetidos, a medias,
  sin copia en el servidor, sin imágenes) y "Sistema" (llaves de Switch, BIOS que faltan / fuera de su carpeta,
  archivos que sobran); a la derecha almacenamiento, "Lo que más ocupa" y "Apps y emuladores" (espacio por app con
  permiso Acceso a uso; Abrir / Gestionar). Cada revisión abre una hoja grande (`ReviewSheet.kt`).
  - **Ordenar juegos** (`Tidy.kt` + `Rar5.kt`): saca juegos de RAR5 guardados sin comprimir (copiando bytes, CRC) y de
    ZIP; Switch con nombre oficial (api.nlib.cc por TitleID; TitleID por nombre, ticket o cabecera NCA con
    `prod.keys`) → `switch/Nombre [TID].nsp`, updates/DLC en `switch/Actualizaciones y DLC/`; otras plataformas solo
    carpeta y marca de la página (no se renombran nombres viejos: partidas e imágenes dependen del nombre); borra
    copias exactas (byte a byte justo antes); renombra igual en el servidor; estima el tiempo y muestra el avance.
    Consejos de formato (RVZ, CHD, .3ds, WUA) con botón a la app que convierte (CHDroid por nombre o Google Play,
    Dolphin, Cemu).
  - **Repetidos**: mismo juego en varias versiones, o el mismo archivo en dos plataformas (Circus Charlie en `nes` y en
    `snesna`): recomienda cuál dejar; "Me quedo con todas" se recuerda.
  - **Sin copia**: cuadrícula de carátulas; copia con velocidad, tiempo, Cancelar (no deja `.part` en el PC).
  - **Archivos que sobran**: solo descargas a medias y copias viejas de partidas. **Los ocultos nunca** (son de otras
    apps: `ROMs/steam/.droiddeck-sync.json` de DroiDeck).
- **Web** (`Kitchen.kt` + `assets/web/`): la tablet sirve una web (NanoHTTPD, 8642) con QR y PIN.
- **Ajustes** (`SettingsScreen.kt`): Aspecto (tema, color, fondo animado, pantalla completa, animación de descarga),
  Sonido, **Servidor** (estado; si no está, 3 pasos numerados: activar SFTP, encontrar el PC en el WiFi o escribir la
  dirección —con Tailscale la IP 100.x.x.x, **mismo puerto 22**—, usuario y contraseña; "Probar velocidad" con número en
  vivo; Avanzado: puerto, carpetas en el PC, identidad del servidor), Este dispositivo (carpetas de ROMs y BIOS con el
  selector de Android, nombre automático, espacio a dejar libre), SteamGridDB (sin clave lleva a su página), Shizuku.

## Código (archivo → qué hace)

- `Group.kt`: agrupa archivos en juegos (discos, .m3u, carpetas, Switch por TitleID), basura, sobrantes, BIOS, títulos.
- `Repo.kt`: estado de la biblioteca y del dispositivo, `versionKey`, versiones, borrar, espacio, refrescar.
- `Sftp.kt` (JSch de mwiede): conexión (AES-GCM primero, `setBulkRequests(128)`), listar, leer con offset, subir
  (`uploadFrom` en tubería; `upload` con OutputStream solo para cosas chicas), renombrar, huella.
- `Pc.kt`: escanear el PC (PowerShell por SSH o SFTP), `PcEdit` (borrar, .m3u, copiar al servidor con velocidad y
  cancelar, `speedTest`). `PrefetchInputStream.kt`: lee el archivo por adelantado mientras se sube.
- `DownloadService.kt`: descargas en servicio (reanudables, buffer 4 MB, velocidad suavizada).
- `Art.kt`: SteamGridDB (carátula, fondo, logo, ícono; WebP al tamaño de pantalla; elige por nombre y por **años de la
  consola** cuando hay varios con el mismo nombre; Switch busca por nombre oficial; revisión única `recheckAll`).
- `SwitchId.kt` (TitleID), `SwitchDb.kt` (titledb: últimas versiones y DLC), `Tidy.kt`, `Rar5.kt`, `Organize.kt`.
- `Saves.kt`, `Backups.kt`, `BackupWorker.kt`, `GameSaves.kt`, `Emulators.kt`, `Storage.kt`, `Tar.kt`, `Shell.kt` (Shizuku).
- UI: `Theme.kt` (paleta, fondos animados), `Components.kt` (Cover, IconCover, ArtImage, focusHighlight, gamepadText),
  `ReviewSheet.kt`, `Notice.kt`, `DownloadArt.kt`, `EmuChoice.kt`, `AppsStorage.kt`, `ArtPicker.kt` (con esqueletos).
- `AppScope.kt`: lo que no se puede cortar va en `appScope`. `MainActivity.kt`: navegación, control (A/B, LB/RB,
  `Nav.keys`), frecuencia de pantalla, pantalla completa.

## Compilar y probar

- GitHub Actions compila y prueba en cada push (artefacto `RomSync-apk`). En el PC: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`,
  `./gradlew testReleaseUnitTest assembleRelease`; instalar con `adb install -r app/build/outputs/apk/release/app-release.apk`.
- Pruebas JVM en `app/src/test` (agrupación, BIOS, sobrantes, Switch, RAR5, tar, SFTP contra un servidor de prueba).
- adb inalámbrico: `adb mdns services` da `192.168.1.95:<puerto>` (cambia); `adb connect` a esa IP:puerto y usar
  `ANDROID_SERIAL=<ip:puerto>` (a veces aparece duplicado como `adb-…_adb-tls-connect`). Git Bash: `MSYS_NO_PATHCONV=1`.
- Registros: `adb logcat -s RomSync`. Frecuencia real: `dumpsys SurfaceFlinger | grep activeMode`.

## Mediciones

- Imágenes: 568 MB → 46 MB (WebP al tamaño de pantalla). APK ~4 MB.
- Pantalla: 120 Hz estables (el usuario la tiene fija en 120; pedir 165 la dejaba en 30).
- Red (WiFi 5 GHz, ~585 Mbps; PC por cable 1 Gbps), con "Probar velocidad": bajada de ~32 a **37-57 MB/s** según el
  WiFi del momento; subida de ~5 a **10-20 MB/s**; subida de un juego real igual que la de prueba gracias a la lectura
  anticipada (antes la mitad).

## Errores encontrados (y qué se hizo)

- "Archivos que sobran" marcaba ocultos de otras apps (DroiDeck) → los ocultos nunca son sobrantes.
- 30 Hz: pedir el modo de 165 Hz con el ajuste del sistema en 120 → se pide el más alto ≤ `peak_refresh_rate`.
- Ordenar dejaba nombres de Switch no oficiales (`[US][v0]`) → todos al formato oficial; el nombre de cada DLC se conserva.
- Repetidos: un RAR de DLC salía como versión → Switch por TitleID y los .rar/.7z no cuentan. Circus Charlie en dos
  carpetas no se veía → también el mismo archivo en dos plataformas, recomendando el de formato conocido.
- Carátulas: nombres disfrazados de páginas (TOTK = "Zel Air Mata Kerajaan") → nombre oficial por TitleID y reintento.
  Link's Awakening de Switch con las imágenes de Game Boy → elegir por años de la consola (sirve también para remakes
  como Resident Evil de GameCube).
- Estado del respaldo en la ficha decía "sin conexión": se consultaba en el hilo principal (`Pc.use`) → `Pc.io`.
- Línea blanca bajo el fondo de la ficha y sombra deformada al pasar el borde del scroll → máscara y sin estiramiento.
- Cancelar una subida dejaba `.part` en el PC → `upload`/`uploadFrom` lo borran si se corta.
- Foco con control: el buscador atrapaba el foco; dos puntos de foco en Descargar; el carrusel se llevaba el foco y,
  si el foco llegaba mientras cambiaba de página, peleaban (ahora se acomoda de una); lo enfocado se salía del borde.
- Animación de descarga atrasada (10 % con la barra en 50 %): una transición de 400 ms reiniciada ~50 veces por segundo
  → resorte.
- Sombras recortadas en rectángulo al pasar rápido y sombra entera bajo la portada transparente al abrir un juego: las
  sombras de Android no se desvanecen y el desvanecido usaba una capa aparte → sombra que crece con la animación y
  `CompositingStrategy.ModulateAlpha`.
- RAR de DLC que quedó en el servidor (Cadence of Hyrule) se veía como otro juego y Organizar no lo notaba → Tidy lee
  los encabezados de los RAR del servidor por SFTP y propone borrarlos si lo de adentro ya está suelto e idéntico.
- Mensajes sin sentido: "las partidas no se borran" al borrar una copia sin partidas; "antes:" en consejos que no
  cambian nada; DLC con ícono "i" cuando titledb aún no sabe cuántos existen.
- El botón del panel lateral cerraba la ficha en vez de abrir el panel (y dentro de la ficha el panel no se podía abrir).
- Guías de botones con nombres de Nintendo para un mando de PlayStation y un GameSir → según el mando.
- Con mando conectado no se podía tocar el buscador, y al escribir perdía el foco → solo fuera de la cruceta con mando,
  y activo mientras se usa.
- "Sin respaldo" como advertencia en juegos sin partidas → "Todavía no hay partidas que respaldar".
- Pantalla completa dejaba un hueco arriba (se reservaba el espacio de las barras ocultas).
- B no hacía nada o había que apretarlo dos veces: Compose usaba atrás para salir de grupos de foco → B va directo.
- Al entrar/salir de una ficha el foco pasaba unos segundos por el panel (y lo abría): la sección se desmontaba →
  la ficha va encima y el panel no acepta foco durante el cambio.
- Con el filtro Descargados, un juego borrado seguía apareciendo (la lista no se recalculaba con los archivos).
- 3DS: al llegar al 100 % la animación se cortaba y el regalo saltaba al final (se cambiaba de dibujo y cada uno tenía
  su reloj) → un solo dibujo y un reloj común (`DownloadArt.doneAt`, `FINISH`, `WRAP`): terminan de caer los
  cubitos, el ícono se llena y recién ahí se envuelve. Sonido de los cubitos medido en la grabación de la eShop
  (seg. 30,7–31,6: tics de ~40 ms que alternan ~1230/1660 Hz, uno cada ~0,13 s) y recreado en `tools/sintetizar.py`.
- Cuadrícula: al bajar a una fila fuera de la vista se saltaba con scrollToItem y las carátulas se descartaban un
  instante y volvían con fundido ("se redibuja todo") → desplazamiento suave de una fila y `animateItem` sin fundido.
  Regla: no saltar la lista para seguir el foco; listas siempre con `key`.
- Volver del inicio de una sección dejaba la Tienda arriba (Explorar no existía y el foco caía en el carrusel) → se
  guarda la posición al entrar (`homeScroll`) y se recupera al volver.
- Los 4 tics de los cubitos sonaban como 2 en la tablet → un solo sonido con la tanda entera a su ritmo (bajada y subida).
- Diagonal en la cruceta: al soltar la dirección que mandaba, la otra seguía apretada y no contaba → ahora pasa a contar.
- El foco "luchaba" (volvía solo a donde estaba al moverse rápido tras volver de una sección o una ficha): la app lo
  pedía dos veces → si hubo un movimiento de la cruceta mientras tanto (`Nav.moveStamp`), no insiste.
- Al cambiar de filtro con los gatillos el foco se perdía → va al carrusel o al primer juego.
- Al apretar Descargar o al terminar, el foco pasaba por el panel antes de Pausar/Borrar (el botón desaparecía) → el panel
  solo acepta el foco cuando se va a él (`Nav.sidebarWelcome`: izquierda, B o pedido de la app).
- 3DS: el aviso de descarga terminada (y su jingle) se adelantaba al regalo → espera a que terminen de caer los cubitos.
- Cierres al azar: `focusProperties { down = ... }` apuntaba a una tarjeta que podía no existir (fila desplazada) →
  se pide a mano y, si falla, sigue la búsqueda normal. Regla: nunca redirigir el foco a un FocusRequester que pueda no
  estar en pantalla. Además `dispatchKeyEvent` no deja que un pedido de foco fallido cierre la app.
- Carrusel: la página que entraba se veía cortada en rectángulo (la ventana tenía borde recto) → ventana redondeada.
- Con mando, desde una ficha o una sección de la Tienda la izquierda ya no va al panel: B vuelve al inicio y de ahí al
  panel (tocando, el panel funciona igual).
- Subida cortada dejaba `.part` en el PC (sin conexión no se podía borrar) → se anotan (`Prefs.pendingParts`) y se
  borran al volver a conectar (`SftpConn.cleanParts`).
- Abajo en el carrusel descargaba el juego: A abría la ficha al apretar y al soltar apretaba Descargar.

## Pendiente / ideas

- Convertir a CHD dentro de la app: CHDroid no publica su código (su repo solo tiene README), así que habría que
  compilar `chdman` (MAME, código abierto) para Android. Trabajo grande.
- Subida aún más rápida: varias conexiones por archivo (escribir por partes en el PC). Riesgoso; hoy ~10-20 MB/s.
- Ver en pantalla una descarga real con cada animación (3DS / Switch) y ajustar detalles.
- Vaciar las papeleras (`RomSync/papelera-*`) cuando el usuario confirme que todo anda bien.
