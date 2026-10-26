package com.jarvis.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AudioChunk(val samples: FloatArray)

class AudioCapture(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private val _chunks = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
    val chunks: SharedFlow<AudioChunk> = _chunks

    val isRecording: Boolean get() = job?.isActive == true

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        // MIC без жёсткого NS: VOICE_RECOGNITION часто режет дальнюю речь.
        // AGC для wake — в OwwWakeSpotter; ASR получает тот же сырой сигнал.
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufSize * 2
        )
        audioRecord?.startRecording()

        job = scope.launch(Dispatchers.IO) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(CHUNK_SAMPLES)
            while (isActive) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read > 0) {
                    val floatBuf = FloatArray(read) { buf[it] / 32768.0f }
                    _chunks.emit(AudioChunk(floatBuf))
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = SAMPLE_RATE * 100 / 1000 // 100 ms
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
