package com.myra.assistant.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mic: 16 kHz mono PCM16 (VOICE_COMMUNICATION + AEC/NS/AGC) -> onAudioChunk
 * Speaker: 24 kHz mono PCM16 queue -> AudioTrack
 */
class AudioEngine(private val context: Context) {

    companion object {
        const val MIC_RATE = 16000
        const val OUT_RATE = 24000
        const val CHUNK_BYTES = 1280 // ~40 ms @16 kHz PCM16 (smaller = MYRA hears you sooner)
    }

    var onAudioChunk: ((ByteArray) -> Unit)? = null           // IO thread
    var onAmplitudeChanged: ((Float) -> Unit)? = null         // main thread
    var onSpeakingStarted: (() -> Unit)? = null               // main thread
    var onSpeakingStopped: (() -> Unit)? = null               // main thread

    @Volatile var isMuted = false
    @Volatile var muteWhileSpeaking = false
    @Volatile private var speaking = false

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = LinkedBlockingQueue<ByteArray>()

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var recordJob: Job? = null
    private var playJob: Job? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var focusRequest: AudioFocusRequest? = null

    // ------------------------------------------------------------------ mic

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recordJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val min = AudioRecord.getMinBufferSize(
            MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(min, CHUNK_BYTES * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        attachEffects(rec.audioSessionId)
        record = rec
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release(); record = null; return
        }

        recordJob = scope.launch {
            val buf = ByteArray(CHUNK_BYTES)
            try {
                while (isActive) {
                    val n = rec.read(buf, 0, CHUNK_BYTES)
                    if (n <= 0) { delay(10); continue }
                    val chunk = buf.copyOf(n)
                    if (!speaking) emitAmp(if (isMuted) 0f else rms(chunk, n))
                    val suppress = isMuted || (muteWhileSpeaking && speaking)
                    if (!suppress) onAudioChunk?.invoke(chunk)
                }
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }
    }

    private fun attachEffects(session: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(session)?.also { it.setEnabled(true) }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(session)?.also { it.setEnabled(true) }
            if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(session)?.also { it.setEnabled(true) }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ speaker

    fun startPlayback() {
        if (playJob?.isActive == true) return
        val minBuf = AudioTrack.getMinBufferSize(
            OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, OUT_RATE)) // ~0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        requestFocus(attrs)

        playJob = scope.launch {
            var idle = 0
            while (isActive) {
                val c = queue.poll(40, TimeUnit.MILLISECONDS)
                if (c == null) {
                    if (speaking && ++idle >= 12) {
                        speaking = false
                        main.post { onSpeakingStopped?.invoke() }
                    }
                    continue
                }
                idle = 0
                if (!speaking) {
                    speaking = true
                    main.post { onSpeakingStarted?.invoke() }
                }
                emitAmp(rms(c, c.size))
                try { t.write(c, 0, c.size) } catch (_: Exception) {}
            }
        }
    }

    fun queueAudio(pcm24k: ByteArray) {
        queue.offer(pcm24k)
    }

    /** Stop MYRA talking immediately (barge-in / long press). */
    fun clearPlayback() {
        queue.clear()
        try {
            track?.apply { pause(); flush(); play() }
        } catch (_: Exception) {
        }
        if (speaking) {
            speaking = false
            main.post { onSpeakingStopped?.invoke() }
        }
    }

    fun release() {
        recordJob?.cancel()
        playJob?.cancel()
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { aec?.release(); ns?.release(); agc?.release() } catch (_: Exception) {}
        abandonFocus()
        scope.cancel()
    }

    // ------------------------------------------------------------------ helpers

    private fun emitAmp(v: Float) {
        main.post { onAmplitudeChanged?.invoke(v) }
    }

    private fun requestFocus(attrs: AudioAttributes) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(req)
            focusRequest = req
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
    }

    private fun rms(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s.toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0f
        val r = sqrt(sum / count).toFloat() / 32768f
        return min(1f, r * 4f)
    }
}
