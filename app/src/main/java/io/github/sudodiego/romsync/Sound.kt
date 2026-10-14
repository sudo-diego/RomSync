package io.github.sudodiego.romsync

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Música de fondo y sonidos de navegación (todo original: lo genera tools/sintetizar.py).
 * - Sonidos: SoundPool (salen al instante, varios a la vez).
 * - Música: AudioTrack estático con puntos de loop: se repite sin el microcorte que deja MediaPlayer al volver a empezar.
 * - Respeta el foco de audio (si otra app pone sonido, se calla) y se pausa cuando la app pasa a segundo plano.
 * Nada de esto puede cerrar la app: cualquier error de audio se registra y se sigue sin sonido.
 */
object Sound {
    enum class Fx(val res: Int) {
        MOVE(R.raw.sfx_mover), OPEN(R.raw.sfx_abrir), BACK(R.raw.sfx_volver),
        DONE(R.raw.sfx_listo), WARN(R.raw.sfx_aviso), TOGGLE(R.raw.sfx_interruptor),
    }

    private var pool: SoundPool? = null
    private val ids = HashMap<Fx, Int>()
    private val loaded = HashSet<Int>()
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var audio: AudioManager? = null
    private var foreground = false
    private var lastMove = 0L

    fun init(ctx: Context) {
        if (pool != null) return
        val app = ctx.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        runCatching {
            val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs()).build()
            p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
            Fx.entries.forEach { ids[it] = p.load(app, it.res, 1) }
            pool = p
        }.onFailure { Log.w("RomSync", "Sonidos: ${it.message}") }
        // La música se prepara aparte (decodificar 1,5 MB no debe trabar el arranque).
        Thread {
            runCatching { track = buildTrack(app) }.onFailure { Log.w("RomSync", "Música: ${it.message}") }
            if (foreground) startMusic()
        }.start()
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    /** Lee el WAV (16 bits mono) de res/raw y lo deja en un AudioTrack que se repite solo. */
    private fun buildTrack(ctx: Context): AudioTrack {
        val bytes = ctx.resources.openRawResource(R.raw.musica_tienda).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 22050
        var data = 0 to 0
        while (pos + 8 <= bytes.size) { // recorre los bloques del RIFF hasta "data"
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            if (id == "fmt ") rate = bb.getInt(pos + 12)
            if (id == "data") { data = pos + 8 to minOf(len, bytes.size - pos - 8); break }
            pos += 8 + len + (len and 1)
        }
        val (off, len) = data
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(len)
            .build()
        t.write(bytes, off, len)
        t.setLoopPoints(0, len / 2, -1)
        return t
    }

    fun play(fx: Fx) {
        if (!Prefs.sfxOn) return
        // Los toques muy seguidos (deslizar sobre la lista) no se amontonan.
        if (fx == Fx.MOVE) {
            val now = System.currentTimeMillis()
            if (now - lastMove < 60) return
            lastMove = now
        }
        val p = pool ?: return
        val id = ids[fx] ?: return
        if (synchronized(loaded) { id !in loaded }) return
        val v = Prefs.sfxVolume / 100f
        runCatching { p.play(id, v, v, 1, 0, 1f) }
    }

    /** La app volvió al frente / se fue al fondo (lo llama MainActivity en onStart y onStop). */
    fun onForeground(fg: Boolean) {
        foreground = fg
        if (fg) startMusic() else pauseMusic(abandon = true)
    }

    /** Se cambió la preferencia de música o su volumen. */
    fun musicSettingsChanged() {
        track?.let { runCatching { it.setVolume(Prefs.musicVolume / 100f) } }
        if (Prefs.musicOn && foreground) startMusic() else pauseMusic(abandon = true)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseMusic(abandon = change == AudioManager.AUDIOFOCUS_LOSS)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track?.let { runCatching { it.setVolume(Prefs.musicVolume / 400f) } }
            AudioManager.AUDIOFOCUS_GAIN -> { track?.let { runCatching { it.setVolume(Prefs.musicVolume / 100f) } }; if (foreground && Prefs.musicOn) track?.let { runCatching { it.play() } } }
        }
    }

    @Synchronized
    private fun startMusic() {
        if (!Prefs.musicOn || !foreground) return
        val t = track ?: return
        val am = audio ?: return
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs()).setOnAudioFocusChangeListener(focusListener).build().also { focus = it }
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        runCatching {
            t.setVolume(Prefs.musicVolume / 100f)
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        }.onFailure { Log.w("RomSync", "Música: ${it.message}") }
    }

    @Synchronized
    private fun pauseMusic(abandon: Boolean) {
        track?.let { t -> runCatching { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause() } }
        if (abandon) focus?.let { f -> audio?.let { runCatching { it.abandonAudioFocusRequest(f) } } }
    }
}
