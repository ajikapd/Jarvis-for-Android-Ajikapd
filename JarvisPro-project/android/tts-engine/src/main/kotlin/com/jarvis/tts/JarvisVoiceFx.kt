package com.jarvis.tts

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Обработка голоса Джарвиса: порт утверждённой на слух цепочки ffmpeg («вариант 4»).
 * Тон не меняется. Работает на чистом FloatArray, чтобы проверяться на JVM без Android.
 */
object JarvisVoiceFx {
    private const val TAIL_MS = 90
    /** Обработка усаживает сигнал: возвращаем к −2 dBFS, но не раздуваем почти тишину. */
    private const val TARGET_PEAK = 0.79f
    private const val MAX_GAIN = 8f

    fun process(input: FloatArray, sampleRate: Int): FloatArray {
        if (input.isEmpty()) return input
        val tail = sampleRate * TAIL_MS / 1000
        val x = input.copyOf(input.size + tail)

        highPass(sampleRate, 80.0).run(x)
        peaking(sampleRate, 2600.0, 1.0, 1.5).run(x)
        compress(x, sampleRate)
        highPass(sampleRate, 170.0).run(x)
        lowPass(sampleRate, 7200.0).run(x)
        chorus(x, sampleRate)
        flanger(x, sampleRate)
        echo(x, sampleRate)
        return normalized(x)
    }

    /** Биквад RBJ: как highpass/lowpass/equalizer в ffmpeg. */
    private class Biquad(
        val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double,
    ) {
        fun run(x: FloatArray) {
            var z1 = 0.0
            var z2 = 0.0
            for (i in x.indices) {
                val inV = x[i].toDouble()
                val out = b0 * inV + z1
                z1 = b1 * inV - a1 * out + z2
                z2 = b2 * inV - a2 * out
                x[i] = out.toFloat()
            }
        }
    }

    private fun highPass(sr: Int, f: Double): Biquad {
        val w = 2 * PI * f / sr
        val alpha = sin(w) / (2 * SQRT1_2_Q)
        val a0 = 1 + alpha
        val c = cos(w)
        return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    private fun lowPass(sr: Int, f: Double): Biquad {
        val w = 2 * PI * f / sr
        val alpha = sin(w) / (2 * SQRT1_2_Q)
        val a0 = 1 + alpha
        val c = cos(w)
        return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    private fun peaking(sr: Int, f: Double, q: Double, gainDb: Double): Biquad {
        val w = 2 * PI * f / sr
        val a = 10.0.pow(gainDb / 40)
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        val a0 = 1 + alpha / a
        return Biquad(
            (1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0,
            -2 * c / a0, (1 - alpha / a) / a0,
        )
    }

    /** acompressor: порог −22 дБ, 2:1, атака 12 мс, спад 160 мс. */
    private fun compress(x: FloatArray, sr: Int) {
        val threshold = 10.0.pow(-22.0 / 20)
        val ratio = 2.0
        val attack = 1 - exp(-1.0 / (0.012 * sr))
        val release = 1 - exp(-1.0 / (0.160 * sr))
        var env = 0.0
        for (i in x.indices) {
            val power = (x[i] * x[i]).toDouble()
            env += (power - env) * (if (power > env) attack else release)
            val level = sqrt(env)
            if (level > threshold) {
                x[i] = (x[i] * (threshold / level).pow(1 - 1 / ratio)).toFloat()
            }
        }
    }

    /** chorus=0.8:0.9:10:0.12:0.25:1.2 */
    private fun chorus(x: FloatArray, sr: Int) {
        val base = 0.010 * sr
        val depth = 0.0012 * sr
        val line = DelayLine((base + depth).toInt() + 4)
        for (i in x.indices) {
            val lfo = sin(2 * PI * 0.25 * i / sr)
            val wet = line.read(base + depth * lfo)
            line.write(x[i])
            x[i] = ((x[i] * 0.8 + wet * 0.12) * 0.9).toFloat()
        }
    }

    /** flanger=delay=0.5:depth=0.6:regen=-10:width=25:speed=0.2 */
    private fun flanger(x: FloatArray, sr: Int) {
        val base = 0.0005 * sr
        val depth = 0.0006 * sr
        val feedback = -0.10
        val wetGain = 0.25 / 1.25
        val dryGain = 1 / 1.25 * sqrt(1 - feedback * feedback)
        val line = DelayLine((base + depth).toInt() + 4)
        for (i in x.indices) {
            val lfo = (sin(2 * PI * 0.2 * i / sr) + 1) / 2
            val wet = line.read(base + depth * lfo)
            line.write((x[i] + wet * feedback).toFloat())
            x[i] = (x[i] * dryGain + wet * wetGain).toFloat()
        }
    }

    /** aecho=0.9:0.6:18|34:0.11|0.06 */
    private fun echo(x: FloatArray, sr: Int) {
        val d1 = (0.018 * sr).toInt()
        val d2 = (0.034 * sr).toInt()
        val dry = x.copyOf()
        for (i in x.indices) {
            val e1 = if (i >= d1) dry[i - d1] else 0f
            val e2 = if (i >= d2) dry[i - d2] else 0f
            x[i] = ((dry[i] * 0.9 + e1 * 0.11 + e2 * 0.06) * 0.6).toFloat()
        }
    }

    private fun normalized(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        if (peak < 1e-5f) return x
        val gain = min(TARGET_PEAK / peak, MAX_GAIN)
        for (i in x.indices) x[i] *= gain
        return x
    }

    private class DelayLine(size: Int) {
        private val buf = FloatArray(size)
        private var pos = 0

        fun write(v: Float) {
            buf[pos] = v
            pos = (pos + 1) % buf.size
        }

        /** Линейная интерполяция между отсчётами: задержка плавает вместе с LFO. */
        fun read(delay: Double): Double {
            val back = delay + 1
            val whole = back.toInt()
            val frac = back - whole
            val a = buf[(pos - whole + 2 * buf.size) % buf.size]
            val b = buf[(pos - whole - 1 + 2 * buf.size) % buf.size]
            return a * (1 - frac) + b * frac
        }
    }

    private const val SQRT1_2_Q = 0.7071067811865476

    /** Уровень в дБ для диагностики и тестов. */
    internal fun rmsDb(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var sum = 0.0
        for (i in from until to) sum += (x[i] * x[i]).toDouble()
        return 10 * log10(sum / max(1, to - from) + 1e-12)
    }
}
