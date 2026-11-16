package com.jarvis.app

import android.content.Context
import android.os.SystemClock
import com.jarvis.asr.SpeakerEmbedder
import com.jarvis.wakeword.VoiceMemory
import com.jarvis.wakeword.VoicePrint
import com.jarvis.wakeword.VoiceTrim
import java.io.File

/**
 * «Только мой голос»: третий фильтр после двух детекторов слова.
 * Образец хранится только в личной папке приложения. Если сравнить нельзя, слово проходит:
 * лишнее срабатывание лучше, чем Джарвис, не отвечающий владельцу.
 */
object VoiceIdentity {
    const val MODEL_ID = "speaker_campp"
    private const val PRINT_FILE = "voice_print.txt"
    private const val PHRASE_FILE = "voice_phrases.txt"
    /** Короткое «да» или «стоп» тоже надо сверить. */
    private const val MIN_COMMAND_SAMPLES = 2400
    /** Сняты по журналу: ваши короткие команды от 0,36, речь из видео до 0,35. */
    private const val COMMAND_AFTER_WAKE = 0.38f
    /** Без слова активации нет подтверждения, что говорит владелец: порог строже. */
    private const val COMMAND_AWAITED = 0.45f
    /** Голос смешан со звуком видео, оценка падает до 0,15–0,27; команду потом сверим чисто. */
    private const val WAKE_OVER_MEDIA = 0.14f
    private const val LEARN_CAPACITY = 30
    private const val LEARN_WORD_MARGIN = 0.12f
    private const val LEARN_COMMAND_SCORE = 0.45f
    private const val STRONG_WAKE_MS = 15_000L
    private const val WORD_MEMORY_FILE = "voice_learned_words.txt"
    private const val PHRASE_MEMORY_FILE = "voice_learned_phrases.txt"

    const val PHRASE_MS = 6000

    /** Вслух, обычным голосом: разные звуки и слова, которые реально звучат в командах. */
    val PHRASES = listOf(
        "Открой тик ток, сделай громче и поставь таймер на десять минут.",
        "Какая завтра погода, и нужно ли брать с собой зонт?",
        "Напомни через час позвонить маме и купить продукты домой.",
        "Включи музыку, выключи фонарик и скажи, сколько сейчас времени.",
        "Да, всё верно. Нет, отмена. Стоп, хватит. Продолжай, спасибо большое.",
    )

    data class Check(val accepted: Boolean, val score: Float, val threshold: Float, val seconds: Float = 0f)

    private var embedder: SpeakerEmbedder? = null
    private var print: VoicePrint? = null
    private var printLoaded = false
    private var phrases: VoicePrint? = null
    private var phrasesLoaded = false
    private var wordMemory: VoiceMemory? = null
    private var phraseMemory: VoiceMemory? = null
    private var strongWakeAt = 0L

    private fun modelFile(context: Context): File? {
        val entry = ModelCatalog.byId(MODEL_ID) ?: return null
        return File(ModelManager.get(context).modelsRoot(), entry.relativePath).takeIf { it.isFile }
    }

    private fun printFile(context: Context) = File(context.filesDir, PRINT_FILE)
    private fun phraseFile(context: Context) = File(context.filesDir, PHRASE_FILE)

    fun modelReady(context: Context): Boolean = modelFile(context) != null

    @Synchronized
    fun enrolled(context: Context): Boolean = currentPrint(context) != null

    @Synchronized
    fun phrasesEnrolled(context: Context): Boolean = currentPhrases(context) != null

    @Synchronized
    private fun currentPhrases(context: Context): VoicePrint? {
        if (!phrasesLoaded) {
            phrases = runCatching { VoicePrint.decode(phraseFile(context).readText()) }.getOrNull()
            phrasesLoaded = true
        }
        return phrases
    }

    @Synchronized
    private fun currentPrint(context: Context): VoicePrint? {
        if (!printLoaded) {
            print = runCatching { VoicePrint.decode(printFile(context).readText()) }.getOrNull()
            printLoaded = true
        }
        return print
    }

    /** Отпечаток записи для образца или null, если речи мало. */
    @Synchronized
    fun embedSample(context: Context, pcm: FloatArray): FloatArray? {
        VoiceClips.save(context, "enroll_word", pcm)
        return embed(context, pcm)?.also { VoiceSecondCheck.keepEnrollment(context, "word", pcm) }
    }

    /** Отпечатки кусков записанной фразы. */
    @Synchronized
    fun embedPhrase(context: Context, pcm: FloatArray): List<FloatArray> {
        VoiceClips.save(context, "enroll_phrase", pcm)
        val engine = loadEmbedder(context) ?: return emptyList()
        return VoiceTrim.windows(VoiceTrim.speech(pcm)).mapNotNull { piece ->
            runCatching { engine.embed(piece) }.getOrNull()
        }.also { if (it.isNotEmpty()) VoiceSecondCheck.keepEnrollment(context, "phrase", pcm) }
    }

    @Synchronized
    fun savePhrases(context: Context, recordings: List<List<FloatArray>>) {
        val next = VoicePrint.enrollGroups(recordings)
        phraseFile(context).writeText(next.encode())
        phrases = next
        phrasesLoaded = true
    }

    @Synchronized
    fun save(context: Context, samples: List<FloatArray>) {
        val next = VoicePrint.enroll(samples)
        printFile(context).writeText(next.encode())
        print = next
        printLoaded = true
    }

    @Synchronized
    fun forget(context: Context) {
        VoiceSecondCheck.forget(context)
        printFile(context).delete()
        phraseFile(context).delete()
        File(context.filesDir, WORD_MEMORY_FILE).delete()
        File(context.filesDir, PHRASE_MEMORY_FILE).delete()
        print = null
        phrases = null
        printLoaded = true
        phrasesLoaded = true
        wordMemory = VoiceMemory(LEARN_CAPACITY)
        phraseMemory = VoiceMemory(LEARN_CAPACITY)
        strongWakeAt = 0L
    }

    /** Загрузка заранее: первое слово не должно ждать модель. */
    @Synchronized
    fun warm(context: Context) {
        if (JarvisSettings.voiceLock(context) && currentPrint(context) != null) loadEmbedder(context)
        VoiceSecondCheck.warm(context)
    }

    /** Сколько образцов выучено на ваших командах. */
    @Synchronized
    fun learnedCount(context: Context): Int = words(context).size + commands(context).size

    private fun words(context: Context): VoiceMemory =
        wordMemory ?: loadMemory(context, WORD_MEMORY_FILE).also { wordMemory = it }

    private fun commands(context: Context): VoiceMemory =
        phraseMemory ?: loadMemory(context, PHRASE_MEMORY_FILE).also { phraseMemory = it }

    private fun loadMemory(context: Context, name: String): VoiceMemory {
        val text = runCatching { File(context.filesDir, name).readText() }.getOrDefault("")
        return VoiceMemory.decode(text, LEARN_CAPACITY)
    }

    private fun remember(context: Context, memory: VoiceMemory, name: String, embedding: FloatArray) {
        memory.add(embedding)
        runCatching { File(context.filesDir, name).writeText(memory.encode()) }
    }

    /**
     * null, когда защита выключена или сравнить нечего. Учится только на чистом звуке
     * и только на том, что похоже на записанный образец, а не на уже выученное.
     */
    @Synchronized
    fun check(context: Context, pcm: FloatArray, clean: Boolean = false, media: Boolean = false): Check? {
        if (!JarvisSettings.voiceLock(context)) return null
        val owner = currentPrint(context) ?: return null
        val embedding = embed(context, pcm) ?: return null
        val score = owner.with(words(context).samples).score(embedding)
        val strong = owner.score(embedding) >= owner.threshold + LEARN_WORD_MARGIN
        if (strong) strongWakeAt = SystemClock.elapsedRealtime()
        // Одной похожести на коротком слове недостаточно для обучения: чужой «Джарвис»
        // иногда проходит этот порог. Эталон слова нельзя пополнять до второй проверки.
        val threshold = if (media) minOf(WAKE_OVER_MEDIA, owner.threshold) else owner.threshold
        return Check(score >= threshold, score, threshold)
    }

    /** Команды проверяются, только когда записаны фразы: по одному слову сравнивать ненадёжно. */
    @Synchronized
    fun commandLocked(context: Context): Boolean =
        JarvisSettings.voiceLock(context) && currentPhrases(context) != null && loadEmbedder(context) != null

    /** null, если речи слишком мало или защита выключена. Берётся лучший кусок команды. */
    @Synchronized
    fun checkCommand(context: Context, pcm: FloatArray, awaited: Boolean, clean: Boolean = false): Check? {
        if (!commandLocked(context)) return null
        val owner = currentPhrases(context) ?: return null
        val engine = loadEmbedder(context) ?: return null
        val speech = VoiceTrim.speech(pcm, MIN_COMMAND_SAMPLES)
        val pieces = VoiceTrim.windows(speech).mapNotNull { piece ->
            runCatching { engine.embed(piece) }.getOrNull()
        }
        val known = owner.with(commands(context).samples)
        val top = pieces.maxByOrNull(known::score) ?: return null
        val best = known.score(top)
        val threshold = minOf(owner.threshold, if (awaited) COMMAND_AWAITED else COMMAND_AFTER_WAKE)
        // Эта оценка ещё не прошла независимую ERes2NetV2-проверку команды.
        // Не пополняем эталон на потенциально чужой речи.
        return Check(best >= threshold, best, threshold, speech.size / 16_000f)
    }

    private fun embed(context: Context, pcm: FloatArray): FloatArray? {
        val speech = VoiceTrim.speech(pcm)
        if (speech.isEmpty()) return null
        val engine = loadEmbedder(context) ?: return null
        return runCatching { engine.embed(speech) }.getOrNull()
    }

    private fun loadEmbedder(context: Context): SpeakerEmbedder? {
        embedder?.let { return it }
        val model = modelFile(context) ?: return null
        return runCatching { SpeakerEmbedder(model.absolutePath) }.getOrNull()?.also { embedder = it }
    }
}
