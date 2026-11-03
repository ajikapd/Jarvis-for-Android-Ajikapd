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
) {
    @Volatile var handle: CancelHandle? = null
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Атомарная идемпотентная отмена: первый вызов ставит флаг и отменяет handle. */
    fun cancelAttempt() {
        if (!cancelled.compareAndSet(false, true)) return
        handle?.cancel()
    }

    /** Вызывается при присвоении handle: если отмена уже была — отменить сразу. */
    fun assignHandle(h: CancelHandle) {
        handle = h
        if (cancelled.get()) h.cancel()
    }
}

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
    ): ChainResult {
        // состояние, видимое в finally: отмена вызывающим корутиной run()
        val eventChannel = Channel<AttemptEvent>(Channel.UNLIMITED)
        val active = java.util.Collections.synchronizedList(mutableListOf<Attempt>())
        try {
            return coroutineScope {
        val cfg = config
        val failures = mutableListOf<ErrorClass>()
        val startMs = clock()
        val deadline = startMs + cfg.totalDeadlineMs

        val entries = availableEntries.filter { health.tryAcquireProbe(it) }
        if (entries.isEmpty()) throw AllFailedException()

        var hedged = false
        val winnerRef = java.util.concurrent.atomic.AtomicReference<Attempt?>(null)
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
                val handle = cloudCall.start(entry, paramIndex, listener)
                a.assignHandle(handle)
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
            // исчерпание: активных нет, записей больше нет, победителя нет
            if (winnerRef.get() == null && active.isEmpty() && nextEntryIdx >= entries.size) break
            val canHedge = active.size < cfg.maxParallel && nextEntryIdx < entries.size && winnerRef.get() == null
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
                    // атомарный выбор победителя: только первый successful CAS становится победителем
                    if (winnerRef.compareAndSet(null, a)) {
                        firstTokenMs = clock()
                        sink.onFirstToken(event.text)
                        // отменить проигравших — только их handles, победителя не трогаем
                        active.filter { it !== a }.forEach { loser -> loser.cancelAttempt() }
                        active.removeAll { it !== a }
                    } else if (a === winnerRef.get()) {
                        sink.onToken(event.text)
                    }
                }
                is AttemptEvent.Completed -> {
                    val a = event.attempt
                    if (a === winnerRef.get()) {
                        sink.onComplete(event.usage)
                        health.recordSuccess(a.entry)
                        // безопасно: firstTokenMs всегда установлен при победителе,
                        // но на всякий случай — 0 вместо NPE
                        result = ChainResult(a.entry, hedged, (firstTokenMs ?: clock()) - startMs, failures)
                        // победитель завершился — убрать из active, чтобы finally его не отменял
                        active.remove(a)
                    } else {
                        active.remove(a)
                        failures.add(ErrorClass.EMPTY_RESPONSE)
                        health.recordFailure(a.entry, ErrorClass.EMPTY_RESPONSE)
                        if (winnerRef.get() == null && active.isEmpty()) startNext()
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
                            if (winnerRef.get() == null && active.isEmpty()) startNext()
                        }
                    } else {
                        val cls = CloudErrorClassifier.classify(event.httpStatus, event.body)
                        failures.add(cls)
                        health.recordFailure(a.entry, cls)
                        if (cls == ErrorClass.AUTH_OR_REGION || cls == ErrorClass.BLOCKED_BY_CLOUDFLARE) {
                            health.reset(a.entry.providerId)
                        }
                        // падение до первого токена → следующая запись сразу
                        if (winnerRef.get() == null && active.isEmpty()) startNext()
                        else if (winnerRef.get() == null && active.size < cfg.maxParallel) {
                            if (startNext()) hedged = true
                        }
                    }
                }
            }
        }

        if (result == null) {
            // отмена только через attempt-флаги — по одному cancel на attempt
            active.forEach { it.cancelAttempt() }
            throw AllFailedException(failures.toList(), hedged)
        }
        @Suppress("UNREACHABLE_CODE") result ?: error("unreachable: result non-null checked above")
            }
        } finally {
            // отмена вызывающим корутиной run(): все активные handles
            // отменяются через attempt-флаги (idempotent, по одному разу)
            active.forEach { it.cancelAttempt() }
        }
    }
}

class AllFailedException(
    val failures: List<ErrorClass> = emptyList(),
    val hedged: Boolean = false
) : RuntimeException("AllFailed")
