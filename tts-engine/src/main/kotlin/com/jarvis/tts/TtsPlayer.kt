package com.jarvis.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Вывод аудио. [play] — одна непрерывная реплика (MODE_STATIC).
 * [playContinuous] — несколько PCM-блоков в один MODE_STREAM без пауз между ними.
 */
class TtsPlayer {

    @Volatile
    private var playing: AudioTrack? = null

    @Volatile
    private var stopRequested = false

    val isPlaying: Boolean get() = playing != null

    fun play(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        stop()
        val track = createStaticTrack(samples, sampleRate) ?: return
        stopRequested = false
        playing = track
        track.play()
        waitUntilDone(track, samples.size)
        finishTrack(track)
    }

    /**
     * Пишет последовательность float-PCM в один STREAM-трек (16-bit).
     * Без stop/start между блоками — речь не «рубцуется».
     */
    fun playContinuous(
        sampleRate: Int,
        onFirstWrite: (() -> Unit)? = null,
        blocks: Sequence<FloatArray>,
    ) {
        stop()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).let { if (it > 0) it else sampleRate * 2 }

        // ~1.5–2 с буфера, чтобы успеть синтезировать следующее предложение
        val bufSize = (minBuf * 8).coerceAtLeast(sampleRate * 4)

        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        val track = AudioTrack(
            attr,
            format,
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            // Fallback: склеить всё и сыграть статикой
            val all = blocks.flatMap { it.asIterable() }.toList().toFloatArray()
            play(all, sampleRate)
            onFirstWrite?.invoke()
            return
        }

        stopRequested = false
        playing = track
        track.play()

        var first = true
        var frames = 0
        val shortBuf = ShortArray(4096)

        for (block in blocks) {
            if (stopRequested || playing !== track) break
            if (block.isEmpty()) continue
            if (first) {
                first = false
                onFirstWrite?.invoke()
            }
            var offset = 0
            while (offset < block.size && !stopRequested && playing === track) {
                val n = min(shortBuf.size, block.size - offset)
                for (i in 0 until n) {
                    val v = block[offset + i].coerceIn(-1f, 1f)
                    shortBuf[i] = (v * 32767f).toInt().toShort()
                }
                var written = 0
                while (written < n && !stopRequested && playing === track) {
                    val w = track.write(shortBuf, written, n - written)
                    if (w <= 0) break
                    written += w
                    frames += w
                }
                offset += n
            }
        }

        // Дождаться конца буфера
        val timeoutAt = System.currentTimeMillis() + 30_000
        while (!stopRequested &&
            playing === track &&
            track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            System.currentTimeMillis() < timeoutAt
        ) {
            if (frames > 0 && track.playbackHeadPosition >= frames - 1) break
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                break
            }
        }
        finishTrack(track)
    }

    fun playChime() {
        val sampleRate = 22050
        val nA = (sampleRate * 0.08).toInt()
        val nB = (sampleRate * 0.16).toInt()
        val amp = 0.5f
        val samples = FloatArray(nA + nB)
        var i = 0
        while (i < nA) {
            samples[i] = amp * sin(2.0 * PI * 880.0 * i / sampleRate).toFloat()
            i++
        }
        while (i < samples.size) {
            val j = i - nA
            samples[i] = amp * sin(2.0 * PI * 1174.0 * j / sampleRate).toFloat()
            i++
        }
        play(samples, sampleRate)
    }

    fun stop() {
        stopRequested = true
        val t = playing ?: return
        runCatching { t.pause(); t.flush(); t.stop() }
        t.release()
        playing = null
    }

    fun release() = stop()

    private fun createStaticTrack(samples: FloatArray, sampleRate: Int): AudioTrack? {
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(samples.size * 4)
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        return AudioTrack(attr, format, bufSize, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            .apply { write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) }
    }

    private fun waitUntilDone(track: AudioTrack, totalFrames: Int) {
        while (!stopRequested && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            if (track.playbackHeadPosition >= totalFrames) break
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                stop()
                return
            }
        }
    }

    private fun finishTrack(track: AudioTrack) {
        if (playing === track) {
            runCatching { track.pause(); track.flush() }
            track.release()
            playing = null
        }
    }
}
