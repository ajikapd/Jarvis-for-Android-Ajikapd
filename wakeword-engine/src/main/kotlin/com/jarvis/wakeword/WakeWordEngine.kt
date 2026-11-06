package com.jarvis.wakeword

import com.jarvis.asr.AsrSession
import com.jarvis.asr.CommandRefinePasses
import com.jarvis.core.common.model.RequestSource
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.nlp.AsrPhraseNormalizer
import com.jarvis.core.nlp.CommandTextSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

enum class WakeWordState { IDLE, LISTENING, AWAITING_COMMAND }

class WakeWordEngine(
    private val session: AsrSession,
    private val scope: CoroutineScope,
    private val maxCommandDurationMs: Long = DEFAULT_MAX_COMMAND_DURATION_MS,
    private val awaitCommandTimeoutMs: Long = DEFAULT_AWAIT_COMMAND_TIMEOUT_MS,
    private val onPartial: (String) -> Unit = {},
    sensitivity: Int = DEFAULT_SENSITIVITY,
    private val refineWithPcm: ((FloatArray, String) -> CommandRefinePasses?)? = null,
    /** Сказал ли это владелец; второй аргумент: команду ждали без слова активации. */
    private val voiceAllowed: (FloatArray, Boolean) -> Boolean = { _, _ -> true },
) {
    private val maxFuzzyDist = fuzzyDistFor(sensitivity)
    private val _userRequests = MutableSharedFlow<UserRequest>(extraBufferCapacity = 8)
    val userRequests: SharedFlow<UserRequest> = _userRequests

    private val _wakeDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val wakeDetected: SharedFlow<Unit> = _wakeDetected

    var state = WakeWordState.IDLE
        private set

    private var lastPartial = ""
    private var commandTimeoutJob: Job? = null
    private var awaitTimeoutJob: Job? = null
    private var lastWakeAtMs = 0L
    private var lastEmitAtMs = 0L
    private var lastEmitText: String? = null
    private var lastWeakMatchAtMs = 0L

    private var hangoverText = ""
    private var hangoverUntilMs = 0L

    private var pendingCommandPrefix = ""

    private val commandPcm = ArrayList<Float>(SAMPLE_RATE * 6)

    /** Прервать ожидание команды извне: кнопка отмены или вход в Live. */
    fun cancelListening() {
        if (state == WakeWordState.IDLE) return
        clearHangover()
        clearPcm()
        pendingCommandPrefix = ""
        lastPartial = ""
        session.reset()
        state = WakeWordState.IDLE
    }

    fun notifyExternalWake() {
        if (state != WakeWordState.IDLE) return
        val now = System.currentTimeMillis()
        clearHangover()
        clearPcm()
        pendingCommandPrefix = ""
        lastPartial = ""
        lastWeakMatchAtMs = 0L
        session.reset()
        state = WakeWordState.LISTENING
        if (now - lastWakeAtMs >= WAKE_DEDUP_MS) {
            lastWakeAtMs = now
            if (!_wakeDetected.tryEmit(Unit)) {
                scope.launch { _wakeDetected.emit(Unit) }
            }
        }
        commandTimeoutJob?.cancel()
        commandTimeoutJob = scope.launch {
            delay(maxCommandDurationMs)
            finishCommand(lastPartial)
        }
    }

    fun onAudioChunk(samples: FloatArray) {
        if (state == WakeWordState.LISTENING || state == WakeWordState.AWAITING_COMMAND) {
            appendPcm(samples)
        }
        val result = session.processChunk(samples)
        val rawText = result.text.trim()
        val text = mergeWithHangover(rawText)
        if (text.isNotBlank() && text != lastPartial) {
            onPartial(text)
        }

        when (state) {
            WakeWordState.IDLE -> {
                val now = System.currentTimeMillis()
                val hit = findWakeHit(text)
                val strength = hit?.strength ?: WakeStrength.NONE
                val weakConfirmed = strength == WakeStrength.WEAK &&
                    lastWeakMatchAtMs > 0L &&
                    now - lastWeakMatchAtMs < WEAK_CONFIRM_WINDOW_MS
                val shouldWake = when (strength) {
                    WakeStrength.STRONG -> true
                    WakeStrength.WEAK -> result.isEndpoint || weakConfirmed
                    WakeStrength.NONE -> false
                }
                if (shouldWake && hit != null) {
                    val seeded = hit.commandText
                    clearHangover()
                    clearPcm()
                    appendPcm(samples)
                    session.reset()
                    pendingCommandPrefix = ""
                    state = WakeWordState.LISTENING
                    lastWeakMatchAtMs = 0L
                    lastPartial = seeded
                    if (now - lastWakeAtMs >= WAKE_DEDUP_MS) {
                        lastWakeAtMs = now
                        _wakeDetected.tryEmit(Unit)
                    }
                    commandTimeoutJob = scope.launch {
                        delay(maxCommandDurationMs)
                        finishCommand(lastPartial)
                    }
                    if (result.isEndpoint) {
                        finishCommand(seeded)
                    } else if (seeded.isNotBlank() &&
                        !AsrPhraseNormalizer.isIncompleteCommand(seeded)
                    ) {
                        finishCommand(seeded)
                    }
                } else {
                    lastWeakMatchAtMs = if (strength == WakeStrength.WEAK) now else 0L
                    maybeResetIdleNoise(text)
                }
                if (state == WakeWordState.IDLE && result.isEndpoint) {
                    if (text.isNotBlank() && (text.looksLikeWakePrefix() || text.hasWakeFragment())) {
                        hangoverText = text
                        hangoverUntilMs = now + HANGOVER_MS
                    } else if (hit == null) {
                        clearHangover()
                    }
                    session.reset()
                }
            }
            WakeWordState.LISTENING -> {
                val incoming = text.stripWakeWord()
                val merged = mergeListeningPartial(lastPartial, incoming)
                lastPartial = merged
                if (result.isEndpoint) {
                    finishCommand(merged)
                }
            }
            WakeWordState.AWAITING_COMMAND -> {
                val candidate = withPendingPrefix(text.stripWakeWord())
                if (candidate.isNotBlank()) {
                    lastPartial = candidate
                    restartAwaitWatchdog()
                    if (result.isEndpoint) {
                        finishCommand(candidate)
                    }
                } else if (text.containsWakeWord()) {
                    restartAwaitWatchdog()
                    if (result.isEndpoint) {
                        session.reset()
                    }
                } else if (result.isEndpoint) {
                    session.reset()
                }
            }
        }
    }

    private fun finishCommand(rawText: String) {
        if (state != WakeWordState.LISTENING && state != WakeWordState.AWAITING_COMMAND) return
        if (rejectForeignVoice(rawText)) return
        commandTimeoutJob?.cancel()
        commandTimeoutJob = null
        awaitTimeoutJob?.cancel()
        awaitTimeoutJob = null
        session.reset()
        clearHangover()

        val pcmSnapshot = if (commandPcm.isEmpty()) FloatArray(0) else {
            FloatArray(commandPcm.size).also { out ->
                for (i in commandPcm.indices) out[i] = commandPcm[i]
            }
        }
        val streaming = withPendingPrefix(rawText.stripWakeWord())
        val refined = if (pcmSnapshot.isNotEmpty()) {
            refineWithPcm?.invoke(pcmSnapshot, streaming)
        } else {
            null
        }
        val command = CommandTextSelector.select(
            streamingRu = streaming,
            offlineMulti = refined?.multilingual,
            englishPass = refined?.english,
        )
        val now = System.currentTimeMillis()
        val dup = now - lastEmitAtMs < EMIT_DEDUP_MS && lastEmitText == command
        if (command.isBlank()) {
            pendingCommandPrefix = ""
            state = WakeWordState.AWAITING_COMMAND
            clearPcm()
            restartAwaitWatchdog()
            if (!dup) {
                lastEmitAtMs = now
                lastEmitText = command
                _userRequests.tryEmit(UserRequest(text = "", source = RequestSource.VOICE))
            }
        } else if (AsrPhraseNormalizer.isIncompleteCommand(command)) {
            pendingCommandPrefix = command
            state = WakeWordState.AWAITING_COMMAND
            lastPartial = command
            restartAwaitWatchdog()
        } else {
            pendingCommandPrefix = ""
            state = WakeWordState.IDLE
            clearPcm()
            if (!dup) {
                lastEmitAtMs = now
                lastEmitText = command
                _userRequests.tryEmit(UserRequest(text = command, source = RequestSource.VOICE))
            }
        }
    }

    /** Чужая речь не становится командой, а окно ожидания дослушивается до своего срока. */
    private fun rejectForeignVoice(rawText: String): Boolean {
        if (rawText.isBlank() || commandPcm.isEmpty()) return false
        val pcm = FloatArray(commandPcm.size) { commandPcm[it] }
        val awaited = state == WakeWordState.AWAITING_COMMAND
        if (voiceAllowed(pcm, awaited)) return false
        session.reset()
        clearHangover()
        clearPcm()
        pendingCommandPrefix = ""
        lastPartial = ""
        if (!awaited) {
            commandTimeoutJob?.cancel()
            commandTimeoutJob = null
            state = WakeWordState.IDLE
        }
        return true
    }

    private fun appendPcm(samples: FloatArray) {
        if (samples.isEmpty()) return
        val maxSamples = SAMPLE_RATE * 12
        if (commandPcm.size >= maxSamples) return
        val room = maxSamples - commandPcm.size
        val n = minOf(samples.size, room)
        for (i in 0 until n) commandPcm.add(samples[i])
    }

    private fun clearPcm() {
        commandPcm.clear()
    }

    private fun withPendingPrefix(command: String): String {
        val prefix = pendingCommandPrefix.trim()
        val body = command.trim()
        if (prefix.isEmpty()) return body
        if (body.isEmpty()) return prefix
        if (body.startsWith(prefix, ignoreCase = true)) return body
        return "$prefix $body"
    }

    private fun mergeWithHangover(rawText: String): String {
        val now = System.currentTimeMillis()
        if (hangoverText.isBlank() || now > hangoverUntilMs) {
            if (now > hangoverUntilMs) clearHangover()
            return coalesceWakeTokens(rawText)
        }
        if (rawText.isBlank()) return coalesceWakeTokens(hangoverText)
        val merged = joinFragments(hangoverText, rawText)
        return coalesceWakeTokens(merged)
    }

    private fun joinFragments(left: String, right: String): String {
        val l = left.trim()
        val r = right.trim()
        if (l.isEmpty()) return r
        if (r.isEmpty()) return l
        if (r.startsWith(l, ignoreCase = true)) return r
        val leftTokens = l.tokens()
        val rightTokens = r.tokens()
        if (leftTokens.size == 1 && rightTokens.isNotEmpty()) {
            val combinedFirst = (leftTokens.first() + rightTokens.first()).lowercase()
            if (isWakeToken(combinedFirst) || combinedFirst.looksLikeWakePrefixToken()) {
                return (listOf(combinedFirst) + rightTokens.drop(1)).joinToString(" ")
            }
        }
        return "$l $r"
    }

    private fun coalesceWakeTokens(text: String): String {
        if (text.isBlank()) return text
        val tokenList = text.tokens()
        if (tokenList.size < 2) return text.trim()
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokenList.size) {
            if (i + 1 < tokenList.size) {
                val a = tokenList[i]
                val b = tokenList[i + 1]
                val glued = a + b
                if (!isWakeToken(a) && !isWakeToken(b) && isWakeToken(glued)) {
                    out += glued
                    i += 2
                    continue
                }
            }
            out += tokenList[i]
            i++
        }
        return out.joinToString(" ")
    }

    private fun clearHangover() {
        hangoverText = ""
        hangoverUntilMs = 0L
    }

    private fun restartAwaitWatchdog() {
        awaitTimeoutJob?.cancel()
        awaitTimeoutJob = scope.launch {
            delay(awaitCommandTimeoutMs)
            if (state == WakeWordState.AWAITING_COMMAND) {
                pendingCommandPrefix = ""
                state = WakeWordState.IDLE
                awaitTimeoutJob = null
                session.reset()
            }
        }
    }

    fun listenForFollowUp(timeoutMs: Long = DEFAULT_AWAIT_COMMAND_TIMEOUT_MS) {
        commandTimeoutJob?.cancel()
        commandTimeoutJob = null
        pendingCommandPrefix = ""
        lastPartial = ""
        state = WakeWordState.AWAITING_COMMAND
        awaitTimeoutJob?.cancel()
        awaitTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (state == WakeWordState.AWAITING_COMMAND) {
                state = WakeWordState.IDLE
                awaitTimeoutJob = null
                session.reset()
            }
        }
        session.reset()
    }

    private fun mergeListeningPartial(seeded: String, incoming: String): String {
        val seed = seeded.trim()
        val body = incoming.trim()
        if (seed.isEmpty()) return body
        if (body.isEmpty()) return seed
        if (body.startsWith(seed, ignoreCase = true)) return body
        if (seed.startsWith(body, ignoreCase = true)) return seed
        val seedTokens = seed.tokens()
        val bodyTokens = body.tokens()
        if (bodyTokens.isNotEmpty() && seedTokens.takeLast(bodyTokens.size) == bodyTokens) {
            return seed
        }
        if (seedTokens.isNotEmpty() && bodyTokens.take(seedTokens.size) == seedTokens) {
            return body
        }
        return "$seed $body"
    }

    private data class WakeHit(val strength: WakeStrength, val commandText: String)

    private fun findWakeHit(text: String): WakeHit? {
        val tokenList = text.tokens()
        if (tokenList.isEmpty()) return null

        var strongIdx: Int? = null
        var weakIdx: Int? = null
        for (i in tokenList.indices) {
            when (wakeStrength(tokenList[i])) {
                WakeStrength.STRONG -> strongIdx = i
                WakeStrength.WEAK -> weakIdx = i
                WakeStrength.NONE -> Unit
            }
        }

        val idx: Int
        val strength: WakeStrength
        when {
            strongIdx != null -> {
                idx = strongIdx
                strength = WakeStrength.STRONG
            }
            weakIdx != null -> {
                if (weakIdx > WEAK_MAX_PREFIX_TOKENS) return null
                idx = weakIdx
                strength = WakeStrength.WEAK
            }
            else -> return null
        }

        val before = tokenList.take(idx)
        val after = tokenList.drop(idx + 1)
        val command = when {
            after.isNotEmpty() -> after.joinToString(" ")
            before.size in 1..COMMAND_THEN_WAKE_MAX -> before.joinToString(" ")
            else -> ""
        }
        return WakeHit(strength, command)
    }

    private fun maybeResetIdleNoise(text: String) {
        val tokenList = text.tokens()
        if (tokenList.size < IDLE_NOISE_RESET_TOKENS) return
        val trailing = tokenList.takeLast(IDLE_TRAILING_KEEP_TOKENS)
        val recentStrong = trailing.any { wakeStrength(it) == WakeStrength.STRONG }
        if (recentStrong) return
        session.reset()
        clearHangover()
        lastWeakMatchAtMs = 0L
        lastPartial = ""
    }

    private fun String.containsWakeWord(): Boolean =
        tokens().any(::isWakeToken)

    private fun String.stripWakeWord(): String =
        tokens().filterNot(::isWakeToken).joinToString(" ")

    private fun String.tokens(): List<String> =
        TOKEN_REGEX.findAll(this).map { it.value.lowercase() }.toList()

    private fun String.looksLikeWakePrefix(): Boolean {
        val tokenList = tokens()
        if (tokenList.isEmpty()) return false
        if (tokenList.size > 3) return false
        return tokenList.take(2).any { it.looksLikeWakePrefixToken() }
    }

    private fun String.hasWakeFragment(): Boolean =
        tokens().any { it.looksLikeWakePrefixToken() || isWakeToken(it) }

    private fun String.looksLikeWakePrefixToken(): Boolean {
        if (length < 2 || length > 6) return false
        return WAKE_PREFIXES.any { prefix ->
            prefix.startsWith(this) || this.startsWith(prefix.take(length.coerceAtMost(prefix.length)))
        }
    }

    private enum class WakeStrength { NONE, WEAK, STRONG }

    private fun wakeStrength(token: String): WakeStrength {
        if (token in WAKE_PATTERNS) return WakeStrength.STRONG
        if (FUZZY_CORES.any { core ->
                token.length >= core.length - 1 && levenshtein(token, core) <= 1
            }
        ) {
            return WakeStrength.STRONG
        }
        if (token in WEAK_WAKE_PATTERNS) return WakeStrength.WEAK
        if (isWakeToken(token)) return WakeStrength.WEAK
        return WakeStrength.NONE
    }

    private fun isWakeToken(token: String): Boolean {
        if (token in WAKE_PATTERNS) return true
        if (token in WEAK_WAKE_PATTERNS) return true
        if (FUZZY_CORES.any { core ->
                token.length >= core.length - 1 && levenshtein(token, core) <= maxFuzzyDist
            }
        ) {
            return true
        }
        return false
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    companion object {
        const val DEFAULT_SENSITIVITY = 2
        val WAKE_PATTERNS = listOf(
            "джарвис", "джарвиз", "джервис", "джеарвис", "джарви", "джарус",
            "жарвис", "жарвиз", "джарвес", "джорвис", "джавирс", "чарвис",
            "джарвьс", "джервиз", "джеорвис",
            "jarvis", "jarvice", "jarwis", "gervis", "djarvis",
        )
        private val WEAK_WAKE_PATTERNS = listOf("джорус")
        private val FUZZY_CORES = listOf("джарвис", "jarvis")

        fun textContainsWake(text: String): Boolean {
            if (text.isBlank()) return false
            val tokens = TOKEN_REGEX.findAll(text).map { it.value.lowercase() }.toList()
            return tokens.any { token ->
                when {
                    token in WAKE_PATTERNS -> true
                    token.length >= 6 && FUZZY_CORES.any { core ->
                        companionLevenshtein(token, core) <= 1
                    } -> true
                    token.length >= 6 && token.take(2) in CONFIRM_PREFIXES &&
                        FUZZY_CORES.any { core ->
                            companionLevenshtein(token, core) <= 2
                        } -> true
                    else -> false
                }
            }
        }

        private val CONFIRM_PREFIXES = setOf("дж", "жа", "дже", "ча", "ja", "dj", "ge")

        private fun companionLevenshtein(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }

        private val WAKE_PREFIXES = listOf(
            "джа", "джар", "джарв", "джарви", "джер", "джерв", "жар", "жарв",
            "jar", "jarv", "jarvi",
        )
        private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        const val DEFAULT_MAX_COMMAND_DURATION_MS = 12000L
        const val DEFAULT_AWAIT_COMMAND_TIMEOUT_MS = 9000L
        private const val SAMPLE_RATE = 16000
        private const val HANGOVER_MS = 3200L
        private fun fuzzyDistFor(sensitivity: Int): Int = when (sensitivity) {
            0 -> 1
            2 -> 2
            else -> 1
        }
        private const val WEAK_CONFIRM_WINDOW_MS = 1400L
        private const val WEAK_MAX_PREFIX_TOKENS = 3
        private const val COMMAND_THEN_WAKE_MAX = 5
        private const val IDLE_NOISE_RESET_TOKENS = 10
        private const val IDLE_TRAILING_KEEP_TOKENS = 6
        private const val WAKE_DEDUP_MS = 1500L
        private const val EMIT_DEDUP_MS = 1000L
    }
}
