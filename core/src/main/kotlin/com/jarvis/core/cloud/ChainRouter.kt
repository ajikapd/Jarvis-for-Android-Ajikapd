package com.jarvis.core.cloud

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

interface TokenSink {
    fun onFirstToken(text: String)
    fun onToken(text: String)
    fun onComplete(usage: Map<String, Any>?)
}

internal sealed class AttemptEvent {
    class VisibleToken(val text: String, val attempt: Attempt) : AttemptEvent()
    class Completed(val usage: Map<String, Any>?, val attempt: Attempt) : AttemptEvent()
    class Errored(val httpStatus: Int?, val body: String?, val attempt: Attempt) : AttemptEvent()
}

internal class Attempt(
    val entry: ChainEntry,
    val paramIndex: Int,
    val channel: Channel<AttemptEvent>,
    val stripper: ThoughtStripper
)

class ChainRouter(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val config: ChainConfig = ChainConfig.Default
) {
    data class Request(val messages: List<Map<String, Any>> = emptyList())

    /** Кэш принятого индекса кандидата параметров: живёт, пока жив роутер. */
    private val acceptedParamIndex = mutableMapOf<String, Int>()

    suspend fun run(
        request: Request,
        availableEntries: List<ChainEntry>,
        health: ChainHealth,
        cloudCall: CloudCall,
        sink: TokenSink
    ): ChainResult = coroutineScope {
        val cfg = config
        val failures = mutableListOf<ErrorClass>()
        val startMs = clock()
        val deadline = startMs + cfg.totalDeadlineMs

        val entries = availableEntries.filter { health.isAvailable(it) }
        if (entries.isEmpty()) throw AllFailedException()

        val eventChannel = Channel<AttemptEvent>(Channel.UNLIMITED)
        val active = mutableListOf<Attempt>()
        val handles = mutableListOf<CancelHandle>()
        var hedged = false
        var winner: Attempt? = null
        var firstTokenMs: Long? = null
        var nextEntryIdx = 1
        var result: ChainResult? = null

        fun launchAttempt(entry: ChainEntry, paramIndex: Int): Attempt {
            val a = Attempt(entry, paramIndex, eventChannel, ThoughtStripper())
            launch(Dispatchers.Default) {
                val listener = object : CallListener {
                    override fun onFirstToken(text: String) {
                        val visible = a.stripper.strip(text)
                        if (visible.isNotEmpty()) a.channel.trySend(AttemptEvent.VisibleToken(visible, a))
                    }
                    override fun onToken(text: String) {
                        val visible = a.stripper.strip(text)
                        if (visible.isNotEmpty()) a.channel.trySend(AttemptEvent.VisibleToken(visible, a))
                    }
                    override fun onComplete(usage: Map<String, Any>?) {
                        a.channel.trySend(AttemptEvent.Completed(usage, a))
                    }
                    override fun onError(httpStatus: Int?, body: String?) {
                        a.channel.trySend(AttemptEvent.Errored(httpStatus, body, a))
                    }
                }
                handles.add(cloudCall.start(entry, paramIndex, listener))
            }
            active.add(a)
            return a
        }

        fun startNext(): Boolean {
            if (nextEntryIdx >= entries.size) return false
            val e = entries[nextEntryIdx++]
            launchAttempt(e, acceptedParamIndex["${e.providerId}/${e.modelId}"] ?: 0)
            return true
        }

        launchAttempt(entries[0], acceptedParamIndex["${entries[0].providerId}/${entries[0].modelId}"] ?: 0)
        val hedgeAt = startMs + cfg.hedgeAfterMs

        // цикл событий: Channel + withTimeoutOrNull (без delay(10)-опроса)
        while (result == null) {
            val now = clock()
            if (now >= deadline) break
            val canHedge = active.size < cfg.maxParallel && nextEntryIdx < entries.size && winner == null
            val waitHedge = (hedgeAt - now).coerceAtLeast(0)
            val timeout = if (canHedge) waitHedge else (deadline - now)

            val event = if (timeout > 0) withTimeoutOrNull(timeout) { eventChannel.receive() } else null

            if (event == null) {
                if (canHedge && clock() >= hedgeAt) {
                    startNext()
                    hedged = true
                }
                continue
            }

            when (event) {
                is AttemptEvent.VisibleToken -> {
                    val a = event.attempt
                    if (winner == null) {
                        winner = a
                        firstTokenMs = clock()
                        sink.onFirstToken(event.text)
                        // отменить проигравших
                        active.filter { it !== a }.forEach { _ -> handles.forEach { h -> h.cancel() } }
                        active.removeAll { it !== a }
                    } else if (a === winner) {
                        sink.onToken(event.text)
                    }
                }
                is AttemptEvent.Completed -> {
                    val a = event.attempt
                    if (a === winner) {
                        sink.onComplete(event.usage)
                        health.recordSuccess(a.entry)
                        result = ChainResult(a.entry, hedged, firstTokenMs!! - startMs, failures)
                    } else {
                        active.remove(a)
                        failures.add(ErrorClass.EMPTY_RESPONSE)
                        health.recordFailure(a.entry, ErrorClass.EMPTY_RESPONSE)
                        if (winner == null && active.isEmpty()) startNext()
                    }
                }
                is AttemptEvent.Errored -> {
                    val a = event.attempt
                    active.remove(a)
                    if (event.httpStatus == 400) {
                        // следующий кандидат параметров той же записи — не сбой здоровья
                        val key = "${a.entry.providerId}/${a.entry.modelId}"
                        val next = a.paramIndex + 1
                        if (next < a.entry.paramCandidates.size) {
                            acceptedParamIndex[key] = next
                            launchAttempt(a.entry, next)
                        } else {
                            failures.add(ErrorClass.BAD_REQUEST)
                            health.recordFailure(a.entry, ErrorClass.BAD_REQUEST, paramsExhausted = true)
                            if (winner == null && active.isEmpty()) startNext()
                        }
                    } else {
                        val cls = CloudErrorClassifier.classify(event.httpStatus, event.body)
                        failures.add(cls)
                        health.recordFailure(a.entry, cls)
                        if (cls == ErrorClass.AUTH_OR_REGION || cls == ErrorClass.BLOCKED_BY_CLOUDFLARE) {
                            health.reset(a.entry.providerId)
                        }
                        // падение до первого токена → следующая запись сразу
                        if (winner == null && active.isEmpty()) startNext()
                        else if (winner == null && active.size < cfg.maxParallel) {
                            if (startNext()) hedged = true
                        }
                    }
                }
            }
        }

        if (result == null) {
            handles.forEach { it.cancel() }
            throw AllFailedException()
        }
        result!!
    }
}

class AllFailedException : RuntimeException("AllFailed")
