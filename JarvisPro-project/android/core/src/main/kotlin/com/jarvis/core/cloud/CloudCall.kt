package com.jarvis.core.cloud

interface CloudCall {
    fun start(entry: ChainEntry, paramCandidateIndex: Int, listener: CallListener): CancelHandle
}

interface CallListener {
    fun onFirstToken(text: String)
    fun onToken(text: String)
    fun onComplete(usage: Map<String, Any>?)
    fun onError(httpStatus: Int?, body: String?)
}

interface CancelHandle {
    fun cancel()
}

data class ChainResult(
    val entry: ChainEntry,
    val hedged: Boolean,
    val timeToFirstTokenMs: Long,
    val failures: List<ErrorClass>,
    /** Отчёт по записям «provider/model=класс@мс» для диагностики и логов. */
    val attemptReport: List<String> = emptyList(),
)
