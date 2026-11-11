package com.jarvis.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** Время последнего чанка с настоящим звуком: заглушённый системой микрофон отдаёт нули. */
    @Volatile var lastSoundAtMs = 0L
        private set
    @Volatile var lastChunkAtMs = 0L
        private set
    @Volatile var readErrors = 0
        private set
    /** Система сообщила, что заглушила запись этого приложения. */
    @Volatile var clientSilenced = false
        private set

    private var silenceCallback: AudioManager.AudioRecordingCallback? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var sourceHasEchoCancel = false

    /**
     * Пока говорит сам ассистент: источник с подавлением эха глушит динамик в 10–50 раз,
     * и слово активации слышно поверх речи. В тишине нужен обычный MIC: тот режим режет дальнюю речь.
     */
    @Volatile var echoCancel = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return

        openRecord(echoCancel)
        val now = SystemClock.elapsedRealtime()
        lastSoundAtMs = now
        lastChunkAtMs = now
        readErrors = 0

        job = scope.launch(Dispatchers.IO) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(CHUNK_SAMPLES)
            while (isActive) {
                if (echoCancel != sourceHasEchoCancel) {
                    closeRecord()
                    if (!isActive) break
                    runCatching { openRecord(echoCancel) }.onFailure {
                        // Микрофон занят или недоступен: пробуем снова чуть позже, не роняя процесс.
                        readErrors++
                        closeRecord()
                        delay(READ_ERROR_DELAY_MS)
                    }
                }
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read < 0) {
                    // Ошибка чтения (например, перезапуск audioserver): без паузы цикл крутится вхолостую.
                    readErrors++
                    delay(READ_ERROR_DELAY_MS)
                } else if (read > 0) {
                    val at = SystemClock.elapsedRealtime()
                    lastChunkAtMs = at
                    for (i in 0 until read) {
                        if (buf[i].toInt() != 0) {
                            lastSoundAtMs = at
                            break
                        }
                    }
                    val floatBuf = FloatArray(read) { buf[it] / 32768.0f }
                    _chunks.emit(AudioChunk(floatBuf))
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeRecord()
    }

    /** Открытие и закрытие микрофона идут из разных потоков (остановка службы и цикл чтения). */
    private val recordLock = Any()

    private fun openRecord(cancelEcho: Boolean) = synchronized(recordLock) {
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // MIC без жёсткого NS: VOICE_RECOGNITION часто режет дальнюю речь.
        // Wake и ASR получают один и тот же сырой сигнал.
        val source = if (cancelEcho) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val record = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize * 2)
        audioRecord = record
        sourceHasEchoCancel = cancelEcho
        if (cancelEcho) {
            echoCanceler = android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)
            echoCanceler?.enabled = true
        }
        registerSilenceCallback()
        record.startRecording()
    }

    /**
     * Закрывает микрофон и не бросает исключений. Раньше stop() у уже остановленной записи
     * и снятие незарегистрированного колбэка роняли приложение (Android 17, Infinix, Samsung).
     */
    private fun closeRecord() = synchronized(recordLock) {
        val record = audioRecord
        audioRecord = null
        val callback = silenceCallback
        silenceCallback = null
        clientSilenced = false
        if (record != null && callback != null) {
            runCatching { record.unregisterAudioRecordingCallback(callback) }
        }
        echoCanceler?.let { runCatching { it.release() } }
        echoCanceler = null
        if (record != null) {
            runCatching {
                if (record.state == AudioRecord.STATE_INITIALIZED &&
                    record.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    record.stop()
                }
            }
            runCatching { record.release() }
        }
    }

    private fun registerSilenceCallback() {
        val record = audioRecord ?: return
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                clientSilenced = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                    ?.isClientSilenced ?: false
            }
        }
        record.registerAudioRecordingCallback(context.mainExecutor, callback)
        silenceCallback = callback
    }

    companion object {
        private const val READ_ERROR_DELAY_MS = 200L
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = SAMPLE_RATE * 100 / 1000 // 100 ms
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
