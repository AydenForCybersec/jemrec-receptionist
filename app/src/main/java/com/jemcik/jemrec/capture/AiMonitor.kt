package com.jemcik.jemrec.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.recorder.DaemonClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The app's side of the AI call: the recorder's `m` command, the ordinary
 * handshake, and nothing the app can say in return. The caller's PCM and the
 * assistant's control lines arrive on one loopback socket; the lines feed
 * [AiLive], and the PCM is played only while Listen is on.
 *
 * The connection is the call's: it reconnects while the service holds it, and
 * the recorder replays the call's STATE and TRANSCRIPT lines to a monitor that
 * came late. Audio is never on the call's path - the daemon captures and the
 * Termux service speaks; this is a listener on the outside of both.
 */
internal class AiMonitor(
    context: Context,
    private val onText: (String) -> Unit,
) {

    private val app = context.applicationContext
    private val audio = AiAudio()
    @Volatile private var stopped = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        AiAudio.load(app)
        thread = Thread(this::loop, "jemrec-ai-monitor").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
        audio.close()
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (!stopped) {
            try {
                val s = runBlocking { DaemonClient.monitor(app) }
                if (stopped) {
                    runCatching { s.close() }
                    return
                }
                s.soTimeout = 0
                socket = s
                Log.i(TAG, "monitor: connected to the recorder's AI link")
                read(s)
            } catch (t: Throwable) {
                if (!stopped) Log.i(TAG, "monitor: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
            if (!stopped) {
                try {
                    Thread.sleep(RECONNECT_MS)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun read(s: Socket) {
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        while (!stopped) {
            val type = input.read()
            if (type < 0) return
            val length = input.readInt()
            if (length < 0 || length > MAX_PAYLOAD) {
                Log.w(TAG, "monitor: implausible frame of $length bytes")
                return
            }
            val payload = ByteArray(length)
            input.readFully(payload)
            when (type) {
                1 -> if (AiAudio.enabled.value) audio.write(payload)
                2 -> {
                    val line = String(payload, StandardCharsets.UTF_8).trim()
                    if (line == "HELLO 1" || line.startsWith("HELLO ")) AiLive.replay()
                    onText(line)
                }
                else -> Log.w(TAG, "monitor: unknown frame type $type")
            }
        }
    }

    private companion object {
        const val TAG = "JemRec"
        const val MAX_PAYLOAD = 1 shl 20
        const val RECONNECT_MS = 1_000L
    }
}

/**
 * The caller's voice through the phone's speaker or earpiece, for the live
 * monitor. Off until the user turns Listen on; the PCM is dropped when it is
 * off, never queued for later. The writer thread owns the track, as the
 * assistant's track does on the recorder side.
 */
internal class AiAudio {

    private val queue = ArrayBlockingQueue<ByteArray>(120)
    @Volatile private var running = true
    @Volatile private var track: AudioTrack? = null
    private val thread = Thread(this::run, "jemrec-ai-listen").apply {
        isDaemon = true
        start()
    }

    fun write(bytes: ByteArray) {
        if (!enabled.value || bytes.isEmpty()) return
        if (!queue.offer(bytes)) {
            queue.poll()
            queue.offer(bytes)
        }
    }

    private fun run() {
        while (running) {
            val block = try {
                queue.poll(50, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            }
            if (!enabled.value) {
                queue.clear()
                release()
                continue
            }
            if (block == null) continue
            try {
                val tr = track ?: make().also {
                    track = it
                    it.play()
                }
                tr.write(block, 0, block.size)
            } catch (t: Throwable) {
                Log.w(TAG, "monitor: the caller's audio stopped: ${t.javaClass.simpleName}")
                release()
            }
        }
        release()
    }

    private fun make(): AudioTrack {
        val rate = 48_000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min * 2, 19_200))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun release() {
        val tr = track
        track = null
        if (tr != null) {
            runCatching { tr.pause() }
            runCatching { tr.flush() }
            runCatching { tr.release() }
        }
    }

    fun close() {
        running = false
        thread.interrupt()
        queue.clear()
        release()
    }

    companion object {
        private const val TAG = "JemRec"
        private const val KEY = "ai_listen"

        private val _enabled = MutableStateFlow(false)
        val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

        /** Read the saved choice once, for a service that started first. */
        fun load(context: Context) {
            _enabled.value = Prefs.of(context).getBoolean(KEY, false)
        }

        fun set(context: Context, on: Boolean) {
            _enabled.value = on
            Prefs.of(context).edit { putBoolean(KEY, on) }
        }
    }
}
