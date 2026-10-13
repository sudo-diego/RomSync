# Prompt para la sesión con ADB (Claude Code en el PC)

Copia esto en Claude Code abierto en la carpeta del repositorio (rama `claude/roms-tablet-pc-manager-26krnr`), con la
tablet conectada por adb (`adb connect 192.168.1.95:5555`).

---

Lee CONTEXTO.md y docs/INVESTIGACION.md. Es mi app RomSync; la tablet es la Lenovo Legion Y700 Gen 3 y está conectada
por adb. El PC es este mismo (Windows, con el Servidor OpenSSH activado). Compila con `.\gradlew.bat assembleRelease`
(Java 17) e instala con `adb install -r`. Mira los errores con `adb logcat -s RomSync AndroidRuntime`. Antes de
instalar, revisa que no esté jugando (`adb shell dumpsys activity activities | findstr topResumedActivity`).

Revisa y arregla esto, probando cada cosa en la tablet:

1. **Leer el disco del PC tarda mucho** ("Leyendo el disco del PC…" sin avanzar). La última versión pide la lista
   entera con PowerShell por SSH (`Pc.windowsList`) y muestra el avance (`Repo.scanStatus`). Verifica que el comando
   funcione con mi cuenta (puede fallar si el shell por defecto de OpenSSH no es cmd, por la codificación UTF-8 de los
   nombres con tildes, o por el tiempo límite) y cuánto tarda ahora. Si cae al modo SFTP, mide por qué.
2. **BIOS de PS2 que siguen saliendo como juegos en la plataforma PlayStation 2.** Lista los nombres y tamaños reales
   de `ROMs/ps2` en la tablet y en el PC, y ajusta `Group.isBiosFile` para que ninguna salga como juego (sin atrapar
   juegos de verdad). Después prueba "BIOS fuera de su carpeta → Ordenar" (antes fallaba con "The coroutine scope left
   the composition"; ya se movió a `appScope`). Ojo: si NetherSX2/ARMSX2 buscan la BIOS en la carpeta ps2, al
   moverla hay que cambiar esa carpeta en el emulador; avísame antes de moverlas en la tablet.
3. **"Buscar carátulas (4)" que nunca baja.** Ya no deberían contar las que SteamGridDB no tiene (`Art.notFound`).
   Verifica que el número llegue a 0 y que esas 4 se puedan poner a mano (ficha → Cambiar carátula).
4. **Archivos que sobran → Borrar.** Verifica que borre de verdad en la tablet y en el PC (descargas a medias `.parts`,
   copias `.SAV(1)(0)`). Los `.opts` ya no deberían aparecer (son opciones del emulador, se quedan).
5. **Crear .m3u** (ficha de un juego de varios discos, como Final Fantasy VII en psx): confirma que el archivo se crea
   en el PC, que después de actualizar la biblioteca sale un solo juego y que al descargarlo baja también el .m3u.
6. **Repetidos en una sola tarjeta:** confirma que Wind Waker (RVZ USA, Europa y la con mods) sale una vez con
   "N versiones", que adentro se elige la versión, y que ponerle nombre propio a una (ej. "Wind Waker HD Plus") no la
   separa del grupo. Revisa el consejo de formato (WUA vs carpeta en Wii U, .3ds vs .zip).
7. **Vita3K y Gravity Rush (.dpt):** el archivo de `ROMs/psvita` es un acceso directo; el juego está instalado dentro
   de Vita3K (`Android/data/org.vita3k.emulator/files/vita/ux0/app/<TITLEID>`). Mira con adb/Shizuku qué hay en
   `ux0/app` y propón cómo mostrar en la ficha "Instalado en Vita3K (X GB)" y, si quiero, borrarlo de ahí (sin tocar
   `ux0/user`, que son las partidas).
8. **Partidas:** prueba "Respaldar un juego…" y "Restaurar…" (eligiendo un solo juego) con Dolphin, con un juego que
   no me importe: juega, guarda, respalda, vuelve a guardar, restaura solo ese juego y verifica en Dolphin. Revisa que
   los archivos restaurados en Android/data queden con el dueño correcto (que Dolphin los pueda abrir).
9. **NetherSX2 → ARMSX2 (lo más importante):** no quiero perder mis partidas de PS2. Revisa con adb dónde están
   realmente mis tarjetas de NetherSX2 (`Android/data/xyz.aethersx2.android/files/memcards`, ¿o eligió otra carpeta?)
   y dónde las busca ARMSX2 (su carpeta de datos puede ser `Android/data/com.armsx2/files` o la que elegí en su
   asistente; preferencia `systemDir`). Antes de nada, haz un respaldo de las dos carpetas en el PC. Luego prueba
   "Pasar tarjetas a ARMSX2" (Partidas → NetherSX2) y verifica en ARMSX2 que aparecen las partidas. Si las tarjetas
   son de carpeta (no archivo .ps2) o tienen otro nombre, adapta `Saves.transferToArmsx2`. Alternativa oficial:
   NetherSX2 → Ajustes → Transfer Data → Export, e Import en ARMSX2.

Cuando termines, actualiza CONTEXTO.md con lo aprendido y haz commit (sin push hasta que te lo pida).
