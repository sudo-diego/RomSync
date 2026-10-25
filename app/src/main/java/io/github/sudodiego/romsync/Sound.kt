package io.github.sudodiego.romsync

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.util.Log

/**
 * Música de fondo y sonidos de navegación.
 * - Música: la canción que elija el usuario (Ajustes), en loop con MediaPlayer. Sin canción elegida, no hay música.
 * - Sonidos: SoundPool, tres juegos (clásico, suave, retro).
 * Respeta el foco de audio y se pausa en segundo plano. Un error de audio nunca cierra la app: se sigue sin sonido.
 */
object Sound {
    enum class Fx { MOVE, OPEN, BACK, DONE, WARN, TOGGLE, JINGLE, CUBE, RAIL }

    val packs = linkedMapOf("clasico" to "Clásico", "suave" to "Suave", "retro" to "Retro")

    private val packRes = mapOf(
        "clasico" to listOf(R.raw.sfx_clasico_mover, R.raw.sfx_clasico_abrir, R.raw.sfx_clasico_volver,
            R.raw.sfx_clasico_listo, R.raw.sfx_clasico_aviso, R.raw.sfx_clasico_interruptor, R.raw.sfx_clasico_jingle, R.raw.sfx_clasico_cubo, R.raw.sfx_clasico_riel),
        "suave" to listOf(R.raw.sfx_suave_mover, R.raw.sfx_suave_abrir, R.raw.sfx_suave_volver,
            R.raw.sfx_suave_listo, R.raw.sfx_suave_aviso, R.raw.sfx_suave_interruptor, R.raw.sfx_suave_jingle, R.raw.sfx_suave_cubo, R.raw.sfx_suave_riel),
        "retro" to listOf(R.raw.sfx_retro_mover, R.raw.sfx_retro_abrir, R.raw.sfx_retro_volver,
            R.raw.sfx_retro_listo, R.raw.sfx_retro_aviso, R.raw.sfx_retro_interruptor, R.raw.sfx_retro_jingle, R.raw.sfx_retro_cubo, R.raw.sfx_retro_riel),
    )

    private lateinit var app: Context
    private var pool: SoundPool? = null
    private val ids = HashMap<String, List<Int>>()
    private val loaded = HashSet<Int>()
    private var track: AudioTrack? = null
    private var player: MediaPlayer? = null
    private var loadedTrack = ""
    private var focus: AudioFocusRequest? = null
    private var audio: AudioManager? = null
    @Volatile private var foreground = false
    private var lastMove = 0L

    fun init(ctx: Context) {
        if (pool != null) return
        app = ctx.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        runCatching {
            val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs()).build()
            p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
            packRes.forEach { (pack, res) -> ids[pack] = res.map { p.load(app, it, 1) } }
            pool = p
        }.onFailure { Log.w("RomSync", "Sonidos: ${it.message}") }
        reloadMusic()
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun play(fx: Fx) {
        if (!Prefs.sfxOn) return
        if (fx == Fx.MOVE) { // los toques muy seguidos no se amontonan
            val now = System.currentTimeMillis()
            if (now - lastMove < 60) return
            lastMove = now
        }
        val p = pool ?: return
        val id = ids[Prefs.sfxPack]?.getOrNull(fx.ordinal) ?: return
        if (synchronized(loaded) { id !in loaded }) return
        val v = Prefs.sfxVolume / 100f
        runCatching { p.play(id, v, v, 1, 0, 1f) }
    }

    /** Para escuchar un juego de sonidos al elegirlo. */
    fun preview(pack: String) {
        val p = pool ?: return
        val id = ids[pack]?.getOrNull(Fx.DONE.ordinal) ?: return
        val v = Prefs.sfxVolume / 100f
        runCatching { p.play(id, v, v, 1, 0, 1f) }
    }

    fun onForeground(fg: Boolean) {
        foreground = fg
        if (fg) startMusic() else pauseMusic(abandon = true)
    }

    /** Se cambió algo de la música (encendida, volumen o pista). */
    fun musicSettingsChanged() {
        val want = wantedTrack()
        if (want != loadedTrack) { reloadMusic(); return }
        setVolume(Prefs.musicVolume / 100f)
        if (Prefs.musicOn && foreground) startMusic() else pauseMusic(abandon = true)
    }

    /** Solo hay música si el usuario eligió una canción. */
    private fun wantedTrack() = if (Prefs.customMusic.isNotBlank()) "propia:" + Prefs.customMusic else ""

    /** Prepara la pista elegida en otro hilo (decodificar no debe trabar la pantalla). */
    private fun reloadMusic() {
        val want = wantedTrack()
        if (want.isEmpty()) { synchronized(this) { releaseMusic() }; return }
        Thread {
            synchronized(this) {
                releaseMusic()
                runCatching {
                    player = MediaPlayer().apply {
                        setAudioAttributes(attrs())
                        setDataSource(app, Uri.parse(want.removePrefix("propia:")))
                        isLooping = true
                        prepare()
                    }
                    loadedTrack = want
                }.onFailure {
                    // La canción se borró o se movió: sin música (en Ajustes se ve que hay que elegirla de nuevo).
                    Log.w("RomSync", "Música: ${it.message}")
                    Prefs.customMusic = ""; Prefs.customMusicName = ""
                }
            }
            if (foreground) startMusic()
        }.start()
    }

    private fun releaseMusic() {
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null; player = null; loadedTrack = ""
    }

    private fun setVolume(v: Float) {
        track?.let { runCatching { it.setVolume(v) } }
        player?.let { runCatching { it.setVolume(v, v) } }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseMusic(abandon = change == AudioManager.AUDIOFOCUS_LOSS)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVolume(Prefs.musicVolume / 400f)
            AudioManager.AUDIOFOCUS_GAIN -> { setVolume(Prefs.musicVolume / 100f); if (foreground && Prefs.musicOn) resume() }
        }
    }

    private fun resume() {
        runCatching { track?.let { if (it.playState != AudioTrack.PLAYSTATE_PLAYING) it.play() } }
        runCatching { player?.let { if (!it.isPlaying) it.start() } }
    }

    @Synchronized
    private fun startMusic() {
        if (!Prefs.musicOn || !foreground) return
        if (track == null && player == null) return
        val am = audio ?: return
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs()).setOnAudioFocusChangeListener(focusListener).build().also { focus = it }
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        setVolume(Prefs.musicVolume / 100f)
        resume()
    }

    @Synchronized
    private fun pauseMusic(abandon: Boolean) {
        runCatching { track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause() } }
        runCatching { player?.let { if (it.isPlaying) it.pause() } }
        if (abandon) focus?.let { f -> audio?.let { runCatching { it.abandonAudioFocusRequest(f) } } }
    }
}
