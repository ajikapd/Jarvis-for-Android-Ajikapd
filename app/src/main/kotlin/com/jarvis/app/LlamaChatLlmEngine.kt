package com.jarvis.app

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.jarvis.brain.ChatLlmEngine
import com.jarvis.brain.LlmDecisionKind
import com.jarvis.brain.LlmDecisionParser
import com.jarvis.brain.LlmStructuredDecision
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Локальный GGUF-чат (Qwen2.5 / Qwen3).
 *
 * Скорость: модель предзагружается в RAM, короткий ctx/prompt, мало токенов.
 * Качество: низкая temperature, жёсткий system, чистка мусора на выходе.
 * Профиль: FAST / BALANCED / QUALITY (см. [JarvisSettings.chatProfile]).
 */
class LlamaChatLlmEngine(
    private val modelFile: File,
    private val profileProvider: () -> String = { "BALANCED" },
    /** Если задан, каждый нативный вызов помечается: вылет внутри него отключит модель. */
    private val guardContext: android.content.Context? = null,
) : ChatLlmEngine, com.jarvis.brain.aiprovider.ModelNamed {

    private inline fun <T> native(crossinline block: () -> T): T =
        guardContext?.let { NativeGuard.run(it, NativeGuard.LLAMA) { block() } } ?: block()

    override val modelId: String get() = modelFile.name

    private val lock = ReentrantLock()
    @Volatile private var model: LlamaModel? = null
    @Volatile private var loadFailed = false
    @Volatile private var warmed = false
    @Volatile private var loadedProfile: String? = null

    val modelPath: String get() = modelFile.absolutePath

    private val threads: Int =
        min(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(2))

    private val nameLower: String = modelFile.name.lowercase()

    private val isFastTier: Boolean =
        nameLower.contains("0.6b") || nameLower.contains("0.5b") ||
            nameLower.contains("0_6b") || nameLower.contains("0_5b")

    private val isQwen3: Boolean = nameLower.contains("qwen3")

    data class ProfileParams(
        val contextSize: Int,
        val maxTokens: Int,
        val temperature: Float,
        val topP: Float,
        val topK: Int,
    )

    private fun params(): ProfileParams {
        val profile = profileProvider().uppercase()
        return when (profile) {
            "FAST" -> ProfileParams(
                contextSize = if (isFastTier) 160 else 192,
                maxTokens = if (isFastTier) 18 else 14,
                temperature = 0.10f,
                topP = 0.70f,
                topK = 15,
            )
            "QUALITY" -> ProfileParams(
                contextSize = if (isFastTier) 256 else 320,
                maxTokens = if (isFastTier) 36 else 32,
                temperature = 0.25f,
                topP = 0.85f,
                topK = 35,
            )
            else -> ProfileParams( // BALANCED — ~3.5–5 с, законченные фразы
                contextSize = if (isFastTier) 176 else 208,
                maxTokens = if (isFastTier) 22 else 18,
                temperature = 0.12f,
                topP = 0.72f,
                topK = 16,
            )
        }
    }

    override fun isReady(): Boolean = modelFile.isFile && !loadFailed

    override fun isLoaded(): Boolean = model != null && !loadFailed

    /** Только mmap/load в RAM — без generate (быстрый cold-start приложения). */
    fun preload() {
        if (!modelFile.isFile || loadFailed) return
        if (!lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS)) return
        try {
            ensureLoadedLocked(forceReloadIfProfileChanged = true)
            Log.i(TAG, "preloaded in RAM model=${modelFile.name} profile=${profileProvider()}")
        } finally {
            lock.unlock()
        }
    }

    override fun warm() {
        if (!modelFile.isFile || loadFailed) return
        lock.withLock {
            val m = ensureLoadedLocked(forceReloadIfProfileChanged = true) ?: return
            if (warmed) {
                // Короткий keep-hot ping, чтобы веса оставались в page-cache CPU.
                keepHotLocked(m)
                return
            }
            val t0 = SystemClock.elapsedRealtime()
            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
                native { runBlocking {
                    Llama.complete(
                        m,
                        prompt = if (isQwen3) "/no_think Привет кратко." else "Привет кратко.",
                        systemPrompt = "",
                        maxTokens = 4,
                    )
                } }
                Log.i(
                    TAG,
                    "warm-up ${SystemClock.elapsedRealtime() - t0}ms " +
                        "model=${modelFile.name} profile=${profileProvider()}",
                )
            }.onFailure { e -> Log.w(TAG, "warm-up failed: ${e.message}") }
            warmed = true
        }
    }

    private fun keepHotLocked(m: LlamaModel) {
        val last = lastKeepHotAt
        val now = SystemClock.elapsedRealtime()
        if (now - last < KEEP_HOT_MIN_MS) return
        lastKeepHotAt = now
        runCatching {
            native { runBlocking {
                Llama.complete(m, prompt = if (isQwen3) "/no_think ." else ".", systemPrompt = "ok", maxTokens = 1)
            } }
            Log.d(TAG, "keep-hot ping ok")
        }
    }

    @Volatile private var lastKeepHotAt = 0L

    override fun generate(
        userText: String,
        history: List<String>,
        memories: String,
        persona: String,
        assembled: com.jarvis.core.llm.AssembledLlmContext?,
    ): String? {
        if (!modelFile.isFile || loadFailed) return null
        val q = userText.trim().take(180)
        if (q.isBlank()) return null

        if (!lock.tryLock(15, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "generate skipped: busy")
            return null
        }
        val tAll = SystemClock.elapsedRealtime()
        try {
            val tLoad0 = SystemClock.elapsedRealtime()
            val m = ensureLoadedLocked(forceReloadIfProfileChanged = true) ?: return null
            val loadMs = SystemClock.elapsedRealtime() - tLoad0
            val p = params()
            val budget = assembled?.outputBudget ?: com.jarvis.core.llm.LlmContextBudget.FAST
            val system = assembled?.systemPrompt?.take(
                when (budget) {
                    com.jarvis.core.llm.LlmContextBudget.FAST -> 720
                    com.jarvis.core.llm.LlmContextBudget.BALANCED -> 1200
                    else -> 1800
                },
            ).orEmpty()
            val hist = assembled?.historyLines ?: history
            val mem = assembled?.legacyMemoriesSnippet?.ifBlank { memories } ?: memories
            val roleHint = compactRole(persona, mem, hist, q)
            val prompt = buildString {
                if (isQwen3) append("/no_think ")
                append(roleHint)
                append(q)
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            return try {
                val result = native { runBlocking {
                    Llama.complete(m, prompt = prompt, systemPrompt = system, maxTokens = p.maxTokens)
                } }
                val text = cleanup(result.text)?.let {
                    com.jarvis.core.llm.LlmOutputPolicy.apply(it, budget)
                }
                Log.i(
                    TAG,
                    "generate ok total=${SystemClock.elapsedRealtime() - tAll}ms " +
                        "loadOr0=${loadMs}ms model=${modelFile.name} profile=${profileProvider()} " +
                        "tok=${result.tokensGenerated} tps=${"%.1f".format(result.tokensPerSecond)} " +
                        "prefill=${result.promptEvalTimeMs}ms gen=${result.generateTimeMs}ms " +
                        "out='${text?.take(100)}'",
                )
                text
            } catch (t: Throwable) {
                Log.e(TAG, "generate failed: ${t.message}", t)
                null
            }
        } finally {
            lock.unlock()
        }
    }

    override fun decide(
        userText: String,
        history: List<String>,
        memories: String,
        persona: String,
    ): LlmStructuredDecision? {
        val text = generate(userText, history, memories, persona) ?: return null
        return LlmDecisionParser.parse("ANSWER: $text")
            ?: LlmStructuredDecision(LlmDecisionKind.ANSWER, text.take(360), raw = text)
    }

    fun unload() {
        lock.withLock {
            val m = model ?: return
            runCatching { Llama.releaseModel(m) }
            model = null
            warmed = false
            loadedProfile = null
        }
    }

    private fun ensureLoadedLocked(forceReloadIfProfileChanged: Boolean): LlamaModel? {
        val want = profileProvider().uppercase()
        if (model != null && forceReloadIfProfileChanged && loadedProfile != null && loadedProfile != want) {
            Log.i(TAG, "profile $loadedProfile → $want, reload sampler/ctx")
            runCatching { Llama.releaseModel(model!!) }
            model = null
            warmed = false
            loadedProfile = null
        }
        model?.let { return it }
        if (loadFailed || !modelFile.isFile) return null
        val p = params()
        val t0 = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "loading ${modelFile.name} (${modelFile.length() / 1_000_000}MB) " +
                "threads=$threads ctx=${p.contextSize} temp=${p.temperature} profile=$want…",
        )
        val loaded = try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
            native { runBlocking {
                Llama.loadModel(
                    modelPath = modelFile.absolutePath,
                    config = LlamaConfig(
                        contextSize = p.contextSize,
                        threads = threads,
                        gpuLayers = 0,
                        temperature = p.temperature,
                        topP = p.topP,
                        topK = p.topK,
                        seed = 42,
                    ),
                )
            } }
        } catch (t: Throwable) {
            loadFailed = true
            Log.e(TAG, "Chat LLM load failed: ${t.message}", t)
            return null
        }
        model = loaded
        loadedProfile = want
        Log.i(TAG, "Chat LLM loaded in ${SystemClock.elapsedRealtime() - t0}ms")
        return loaded
    }

    /** Короткий role-prefix. Контекст — только для явных follow-up. */
    private fun compactRole(
        persona: String,
        memories: String,
        history: List<String>,
        userText: String,
    ): String {
        val who = when (persona) {
            "FRIENDLY" ->
                "Дружелюбный JARVIS: тепло, на «ты», живые короткие фразы, лёгкий юмор, без канцелярита."
            "MINIMAL" ->
                "Минимальный JARVIS: максимум одно короткое предложение, только суть, без воды и эмодзи."
            "DEVELOPER" ->
                "Developer JARVIS: технично и точно, термины ок, статусы/ошибки если уместны, без сюсюканья."
            else ->
                "JARVIS Classic: спокойный вежливый дворецкий, чёткие ответы, сдержанный тон."
        }
        val mem = if (memories.isNotBlank()) " mem=${memories.take(20)};" else ""
        val follow = looksLikeFollowUp(userText)
        val ctx = if (follow) shortContext(history) else ""
        val ctxPart = if (ctx.isNotBlank()) " ctx=$ctx;" else ""
        return "[$who$mem$ctxPart RU] "
    }

    private fun looksLikeFollowUp(t: String): Boolean {
        val s = t.lowercase().trim()
        if (s.length > 48) return false
        return s.startsWith("а ") || s.startsWith("ну ") ||
            s in setOf("почему", "зачем", "подробнее", "дальше", "ещё", "еще") ||
            s.startsWith("а почему") || s.startsWith("а как") ||
            Regex("""\b(они|их|им|это|него|неё|нее)\b""").containsMatchIn(s)
    }

    private fun shortContext(history: List<String>): String {
        val turns = history.filter {
            it.startsWith("user:", ignoreCase = true) || it.startsWith("assistant:", ignoreCase = true)
        }
        val prior = if (turns.lastOrNull()?.startsWith("user:", ignoreCase = true) == true) {
            turns.dropLast(1)
        } else {
            turns
        }
        val u = prior.lastOrNull { it.startsWith("user:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.take(36).orEmpty()
        return u.takeIf { it.isNotBlank() }?.let { "«$it»" }.orEmpty()
    }

    private fun cleanup(raw: String): String? {
        var t = raw.trim()
        if (t.isBlank()) return null
        t = t.replace(Regex("""(?s)<think>.*?</think>"""), " ")
            .replace(Regex("""(?s)<think>.*"""), " ")
            .replace(Regex("""(?i)/no_think\s*"""), "")
            .trim()
        t = t.lineSequence()
            .map { it.trim() }
            .takeWhile { line ->
                val l = line.lowercase()
                l.isNotEmpty() &&
                    !l.startsWith("user") &&
                    !l.startsWith("human") &&
                    !l.startsWith("assistant") &&
                    !l.startsWith("контекст") &&
                    !l.startsWith("вопрос про") &&
                    !l.startsWith("<|") &&
                    !l.startsWith("system") &&
                    !l.startsWith("as an ai") &&
                    !l.startsWith("sure,") &&
                    !l.startsWith("of course")
            }
            .joinToString(" ")
            .trim()
        t = t.replace(Regex("""<\|im_end\|>.*""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<\|.*?\|>"""), "")
            .replace(Regex("""(?i)^(ANSWER|QUESTION|CLARIFICATION|REFUSAL|FOLLOW_UP)\s*[:|\-–]\s*"""), "")
            .replace(Regex("""(?i)^(sure[,!]?\s*|of course[,!]?\s*|okay[,!]?\s*|как ии[,\s]*)"""), "")
            .replace(Regex("""\*+"""), "")
            .replace(Regex("""#{1,6}\s*"""), "")
            .replace(Regex("""[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]+"""), " ")
            .replace(Regex("""[\p{So}\p{Cn}]+"""), " ") // эмодзи / символы
            .replace(Regex("""\s+"""), " ")
            .trim()
        t = t.replace(Regex("""^(Ответь|Ответ|Сейчас|Кратко)[:\s]+""", RegexOption.IGNORE_CASE), "")
        val parts = t.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return t.take(240).ifBlank { null }
        val complete = parts.filter { it.lastOrNull() in setOf('.', '!', '?', '…') }
        t = when {
            complete.isNotEmpty() -> complete.take(2).joinToString(" ")
            else -> {
                // Модель обрезана по maxTokens — режем по последней запятой/тире.
                var raw = parts.take(2).joinToString(" ").trimEnd(',', ';', ':', ' ')
                val cut = raw.lastIndexOfAny(charArrayOf(',', '—', '–', ';'))
                if (cut in 24 until raw.lastIndex) {
                    raw = raw.take(cut).trimEnd(',', ' ', '—', '–', ';')
                }
                if (raw.lastOrNull() !in setOf('.', '!', '?', '…')) raw = "$raw."
                raw
            }
        }
        return t.take(260).ifBlank { null }
    }

    companion object {
        private const val TAG = "Jarvis.ChatLlm"
        private const val KEEP_HOT_MIN_MS = 45_000L
    }
}
