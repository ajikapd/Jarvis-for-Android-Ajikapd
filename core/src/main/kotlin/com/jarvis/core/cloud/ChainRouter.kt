package com.jarvis.core.cloud

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

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

    /** Получен любой терминальный/токенный колбэк от вызова (иначе — «висел без ответа»). */
    @Volatile var receivedEvent: Boolean = false
    /** wall-clock старта попытки (мс), для отчёта «класс@мс». */
    @Volatile var startedAtMs: Long = 0
    /** Последняя ошибка попытки (класс), для отчёта. */
    @Volatile var lastError: ErrorClass? = null

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

    @OptIn(ExperimentalCoroutinesApi::class)
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
        // все попытки run() — для отчёта «provider/model=класс@мс»
        val allAttempts = java.util.Collections.synchronizedList(mutableListOf<Attempt>())
        val winnerRef = java.util.concurrent.atomic.AtomicReference<Attempt?>(null)

        /** «provider/model=класс@мс» по каждой попытке; класс=OK для победителя. */
        fun attemptReport(): List<String> = allAttempts.map { a ->
            val cls = when {
                a.lastError != null -> a.lastError.toString()
                a === winnerRef.get() -> "OK"
                else -> "NO_RESPONSE"
            }
            val ms = (clock() - a.startedAtMs).coerceAtLeast(0)
            "${a.entry.providerId}/${a.entry.modelId}=${cls}@${ms}ms"
        }

        // доступность проверяем без захвата пробы; захват — в момент реального старта
        val entries = availableEntries.filter { health.isAvailable(it) }
        if (entries.isEmpty()) throw AllFailedException()

        var hedged = false
        var firstTokenMs: Long? = null
        var lastTokenAt = 0L
        var nextEntryIdx = 1
        var result: ChainResult? = null

        fun launchAttempt(entry: ChainEntry, paramIndex: Int): Attempt? {
            // захват пробы в момент реального старта; если запись недоступна — не стартуем
            if (!health.tryAcquireProbe(entry)) return null
            val a = Attempt(entry, paramIndex, eventChannel, ThoughtStripper())
            a.startedAtMs = clock()
            allAttempts.add(a)
            launch(Dispatchers.Default) {
                val listener = object : CallListener {
                    override fun onFirstToken(text: String) {
                        a.receivedEvent = true
                        val visible = a.stripper.strip(text)
                        if (visible.isNotEmpty()) a.channel.trySend(AttemptEvent.VisibleToken(visible, a))
                    }
                    override fun onToken(text: String) {
                        a.receivedEvent = true
                        val visible = a.stripper.strip(text)
                        if (visible.isNotEmpty()) a.channel.trySend(AttemptEvent.VisibleToken(visible, a))
                    }
                    override fun onComplete(usage: Map<String, Any>?) {
                        a.receivedEvent = true
                        val tail = a.stripper.flush()
                        if (tail.isNotEmpty()) a.channel.trySend(AttemptEvent.VisibleToken(tail, a))
                        a.channel.trySend(AttemptEvent.Completed(usage, a))
                    }
                    override fun onError(httpStatus: Int?, body: String?) {
                        a.receivedEvent = true
                        a.channel.trySend(AttemptEvent.Errored(httpStatus, body, a))
                    }
                }
                val handle = cloudCall.start(entry, paramIndex, listener)
                a.assignHandle(handle)
            }
            active.add(a)
            return a
        }

        /** Победитель оборвался после первых токенов: перезапуска нет, текст уже ушёл в sink. */
        fun failWinner(winner: Attempt, cls: ErrorClass, retryAfter: Int? = null): Nothing {
            failures.add(cls)
            winner.lastError = cls
            health.recordFailure(winner.entry, cls, retryAfterSeconds = retryAfter)
            throw AllFailedException(failures.toList(), hedged, attemptReport(), partial = true)
        }

        fun startNext(): Boolean {
            while (nextEntryIdx < entries.size) {
                val e = entries[nextEntryIdx++]
                if (launchAttempt(e, acceptedParamIndex["${e.providerId}/${e.modelId}"] ?: 0) != null) return true
                // tryAcquireProbe == false: запись недоступна — пропустить, не сбой
            }
            return false
        }

        // первый запуск; если первая запись вдруг недоступна — идём к следующей
        if (launchAttempt(entries[0], acceptedParamIndex["${entries[0].providerId}/${entries[0].modelId}"] ?: 0) == null) {
            startNext()
        }
        if (active.isEmpty() && nextEntryIdx >= entries.size) throw AllFailedException()
        val hedgeAt = startMs + cfg.hedgeAfterMs

        // цикл событий: Channel + withTimeoutOrNull (без delay(10)-опроса)
        while (result == null) {
            val now = clock()
            val winner = winnerRef.get()
            if (winner != null) {
                // дедлайн только до первого токена; дальше ограничен простой стрима
                if (now - lastTokenAt >= cfg.streamIdleTimeoutMs) failWinner(winner, ErrorClass.NETWORK_ERROR)
            } else {
                if (now >= deadline) break
                // исчерпание: активных нет, записей больше нет, победителя нет
                if (active.isEmpty() && nextEntryIdx >= entries.size) break
            }
            val canHedge = winner == null && active.size < cfg.maxParallel && nextEntryIdx < entries.size
            val timeout = when {
                winner != null -> lastTokenAt + cfg.streamIdleTimeoutMs - now
                canHedge -> minOf((hedgeAt - now).coerceAtLeast(0), deadline - now)
                else -> deadline - now
            }

            // select, а не withTimeoutOrNull { receive() }: отмена по таймауту не теряет событие
            val event = if (timeout > 0) {
                select<AttemptEvent?> {
                    eventChannel.onReceive { it }
                    onTimeout(timeout) { null }
                }
            } else {
                null
            }

            if (event == null) {
                if (canHedge && clock() >= hedgeAt && startNext()) hedged = true
                continue
            }

            when (event) {
                is AttemptEvent.VisibleToken -> {
                    val a = event.attempt
                    // атомарный выбор победителя: только первый successful CAS становится победителем
                    if (winnerRef.compareAndSet(null, a)) {
                        firstTokenMs = clock()
                        lastTokenAt = clock()
                        sink.onFirstToken(event.text)
                        // отменить проигравших и освободить их пробы (без записи сбоя)
                        active.filter { it !== a }.forEach { loser ->
                            loser.cancelAttempt()
                            health.releaseProbe(loser.entry)
                        }
                        active.removeAll { it !== a }
                    } else if (a === winnerRef.get()) {
                        lastTokenAt = clock()
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
                        result = ChainResult(a.entry, hedged, (firstTokenMs ?: clock()) - startMs, failures, attemptReport())
                        // победитель завершился — убрать из active, чтобы finally его не отменял
                        active.remove(a)
                    } else if (winnerRef.get() == null) {
                        active.remove(a)
                        failures.add(ErrorClass.EMPTY_RESPONSE)
                        health.recordFailure(a.entry, ErrorClass.EMPTY_RESPONSE)
                        if (winnerRef.get() == null && active.isEmpty()) startNext()
                    }
                }
                is AttemptEvent.Errored -> {
                    val a = event.attempt
                    val currentWinner = winnerRef.get()
                    // поздняя ошибка отменённого проигравшего — не сбой записи
                    if (currentWinner != null && a !== currentWinner) continue
                    active.remove(a)
                    if (a === currentWinner) {
                        val cls = CloudErrorClassifier.classify(event.httpStatus, event.body)
                        failWinner(a, cls, CloudErrorClassifier.parseRetryAfterSeconds(event.body))
                    }
                    if (event.httpStatus == 400) {
                        // следующий кандидат параметров той же записи — не сбой здоровья
                        val key = "${a.entry.providerId}/${a.entry.modelId}"
                        val next = a.paramIndex + 1
                        if (next < a.entry.paramCandidates.size) {
                            acceptedParamIndex[key] = next
                            // проба полуоткрытой записи ещё числится за этой попыткой
                            health.releaseProbe(a.entry)
                            if (launchAttempt(a.entry, next) == null && active.isEmpty()) startNext()
                        } else {
                            failures.add(ErrorClass.BAD_REQUEST)
                            health.recordFailure(a.entry, ErrorClass.BAD_REQUEST, paramsExhausted = true)
                            if (winnerRef.get() == null && active.isEmpty()) startNext()
                        }
                    } else {
                        val cls = CloudErrorClassifier.classify(event.httpStatus, event.body)
                        failures.add(cls)
                        a.lastError = cls
                        // Retry-After из тела ошибки (Gemini retryDelay / Groq try again in)
                        val retryAfter = if (cls == ErrorClass.RATE_LIMITED) {
                            CloudErrorClassifier.parseRetryAfterSeconds(event.body)
                        } else null
                        health.recordFailure(a.entry, cls, retryAfterSeconds = retryAfter)
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
            // дедлайн без видимого токена: всем активным NETWORK_ERROR (в т.ч. «думали» без ответа),
            // чтобы failures не был пустым; отмена активных и освобождение проб
            val nowMs = clock()
            active.forEach { a ->
                failures.add(ErrorClass.NETWORK_ERROR)
                a.lastError = ErrorClass.NETWORK_ERROR
                if (a.startedAtMs <= 0) a.startedAtMs = nowMs
                health.recordFailure(a.entry, ErrorClass.NETWORK_ERROR)
            }
            // отчёт: пометить повисшие попытки как NETWORK_ERROR@мс (deadline_no_response)
            val report = allAttempts.map { a ->
                val cls = when {
                    !a.receivedEvent -> "NETWORK_ERROR(deadline_no_response)"
                    a.lastError != null -> a.lastError.toString()
                    else -> "NO_RESPONSE"
                }
                val ms = (nowMs - a.startedAtMs).coerceAtLeast(0)
                "${a.entry.providerId}/${a.entry.modelId}=${cls}@${ms}ms"
            }
            active.forEach { it.cancelAttempt() }
            active.forEach { health.releaseProbe(it.entry) }
            throw AllFailedException(failures.toList(), hedged, report)
        }
        @Suppress("UNREACHABLE_CODE") result ?: error("unreachable: result non-null checked above")
            }
        } finally {
            // отмена вызывающим корутиной run(): активные попытки отменяются
            // через attempt-флаги (idempotent); пробы освобождаются
            active.forEach { it.cancelAttempt() }
            active.forEach { health.releaseProbe(it.entry) }
        }
    }
}

class AllFailedException(
    val failures: List<ErrorClass> = emptyList(),
    val hedged: Boolean = false,
    /** Отчёт по записям «provider/model=класс@мс» для диагностики и логов. */
    val attemptReport: List<String> = emptyList(),
    /** Сбой после первых токенов: часть ответа уже передана в sink. */
    val partial: Boolean = false,
) : RuntimeException("AllFailed")
