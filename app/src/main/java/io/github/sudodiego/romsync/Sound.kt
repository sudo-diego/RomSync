package io.github.sudodiego.romsync

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Música de fondo y sonidos de navegación (las pistas y sonidos incluidos los genera tools/sintetizar.py: originales).
 * - Pistas incluidas: se decodifican (.ogg) una vez a PCM y suenan en un AudioTrack estático con puntos de loop, así
 *   se repiten sin el microcorte de MediaPlayer.
 * - Canción propia (elegida en Ajustes): MediaPlayer en loop (puede ser larga; no se carga entera en memoria).
 * - Sonidos: SoundPool, tres juegos (clásico, suave, retro).
 * Respeta el foco de audio y se pausa en segundo plano. Un error de audio nunca cierra la app: se sigue sin sonido.
 */
object Sound {
    enum class Fx { MOVE, OPEN, BACK, DONE, WARN, TOGGLE }

    /** Pistas incluidas: id → (nombre, recurso). */
    val tracks = linkedMapOf(
        "bossa" to ("Bossa de la tienda" to R.raw.musica_bossa),
        "lofi" to ("Tarde tranquila" to R.raw.musica_lofi),
        "retro" to ("8 bits" to R.raw.musica_retro),
    )
    val packs = linkedMapOf("clasico" to "Clásico", "suave" to "Suave", "retro" to "Retro")

    private val packRes = mapOf(
        "clasico" to listOf(R.raw.sfx_clasico_mover, R.raw.sfx_clasico_abrir, R.raw.sfx_clasico_volver,
            R.raw.sfx_clasico_listo, R.raw.sfx_clasico_aviso, R.raw.sfx_clasico_interruptor),
        "suave" to listOf(R.raw.sfx_suave_mover, R.raw.sfx_suave_abrir, R.raw.sfx_suave_volver,
            R.raw.sfx_suave_listo, R.raw.sfx_suave_aviso, R.raw.sfx_suave_interruptor),
        "retro" to listOf(R.raw.sfx_retro_mover, R.raw.sfx_retro_abrir, R.raw.sfx_retro_volver,
            R.raw.sfx_retro_listo, R.raw.sfx_retro_aviso, R.raw.sfx_retro_interruptor),
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

    private fun wantedTrack() = if (Prefs.musicTrack == "propia" && Prefs.customMusic.isNotBlank()) "propia:" + Prefs.customMusic
        else Prefs.musicTrack.takeIf { it in tracks } ?: "bossa"

    /** Prepara la pista elegida en otro hilo (decodificar no debe trabar la pantalla). */
    private fun reloadMusic() {
        val want = wantedTrack()
        Thread {
            synchronized(this) {
                releaseMusic()
                runCatching {
                    if (want.startsWith("propia:")) {
                        player = MediaPlayer().apply {
                            setAudioAttributes(attrs())
                            setDataSource(app, Uri.parse(want.removePrefix("propia:")))
                            isLooping = true
                            prepare()
                        }
                    } else track = decodeTrack(tracks.getValue(want).second)
                    loadedTrack = want
                }.onFailure {
                    Log.w("RomSync", "Música ($want): ${it.message}")
                    // La canción propia ya no se puede abrir (se borró o se movió): volver a la incluida.
                    if (want.startsWith("propia:")) { Prefs.musicTrack = "bossa"; runCatching { track = decodeTrack(R.raw.musica_bossa); loadedTrack = "bossa" } }
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

    /** .ogg de res/raw → PCM 16 bits → AudioTrack estático que se repite solo. */
    private fun decodeTrack(res: Int): AudioTrack {
        val ex = MediaExtractor()
        app.resources.openRawResourceFd(res).use { fd -> ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        val fmt = ex.getTrackFormat(0)
        ex.selectTrack(0)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outFmt = fmt
        while (true) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFmt = codec.outputFormat
            if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!
                val chunk = ByteArray(info.size); b.position(info.offset); b.get(chunk); pcm.write(chunk)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); ex.release()
        val rate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val bytes = pcm.toByteArray()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes.size)
            .build()
        t.write(bytes, 0, bytes.size)
        t.setLoopPoints(0, bytes.size / (2 * ch), -1)
        return t
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
