package com.jarvis.core.cloud

import kotlinx.coroutines.*

class ChainRouter(private val clock: () -> Long = { System.currentTimeMillis() }) {
    data class Request(val messages: List<Map<String, Any>> = emptyList())

    suspend fun run(
        request: Request,
        availableEntries: List<ChainEntry>,
        health: ChainHealth,
        config: ChainConfig = ChainConfig.Default,
        cloudCall: CloudCall
    ): ChainResult = withContext(Dispatchers.Default) {
        val failures = mutableListOf<ErrorClass>()
        val acceptedParamIndex = mutableMapOf<String, Int>()
        val hedgeAfter = config.hedgeAfterMs
        val deadline = clock() + config.totalDeadlineMs

        var hedged = false
        var winnerEntry: ChainEntry? = null
        var firstTokenTime: Long? = null
        val output = StringBuilder()

        // filter available
        val entries = availableEntries.filter { health.isAvailable(it) }
        if (entries.isEmpty()) {
            throw AllFailedException()
        }

        // launch first
        val jobs = mutableListOf<Job>()
        val activeCalls = mutableListOf<Pair<ChainEntry, CancelHandle>>()

        // We'll implement sequentially with hedging via coroutine delays.
        var index = 0
        var currentJob: Job? = null

        // Simplified: try entries one by one with hedge
        outer@ for (entry in entries) {
            if (clock() > deadline) break
            val entryKey = "${entry.providerId}/${entry.modelId}"
            var paramIdx = acceptedParamIndex.getOrPut(entryKey) { 0 }
            var attemptParam = paramIdx
            while (attemptParam < entry.paramCandidates.size) {
                if (clock() > deadline) break@outer
                health.markProbeInFlight(entry)
                val callHandleHolder = arrayOf<CancelHandle?>(null)
                val firstTokenDeferred = CompletableDeferred<Long?>()
                val completedDeferred = CompletableDeferred<Boolean>()
                var firstTokenReceived = false
                val listener = object : CallListener {
                    override fun onFirstToken(text: String) {
                        val stripped = ThoughtStripper().strip(text) // best effort
                        if (!firstTokenReceived) {
                            firstTokenReceived = true
                            firstTokenTime = clock()
                            firstTokenDeferred.complete(firstTokenTime)
                        }
                    }
                    override fun onToken(text: String) {}
                    override fun onComplete(usage: Map<String, Any>?) { completedDeferred.complete(true) }
                    override fun onError(httpStatus: Int?, body: String?) {
                        val cls = CloudErrorClassifier.classify(httpStatus, body)
                        if (httpStatus == 400 && !firstTokenReceived) {
                            // try next param candidate
                            attemptParam++
                            acceptedParamIndex[entryKey] = attemptParam
                            completedDeferred.complete(false)
                        } else {
                            failures.add(cls)
                            if (cls == ErrorClass.AUTH_OR_REGION || cls == ErrorClass.BLOCKED_BY_CLOUDFLARE) health.reset(entry.providerId)
                            else health.recordFailure(entry, cls)
                            completedDeferred.complete(false)
                        }
                    }
                }
                val handle = cloudCall.start(entry, attemptParam, listener)
                callHandleHolder[0] = handle
                activeCalls.add(entry to handle)

                // wait for first token or completion or hedge timeout
                val start = clock()
                while (!firstTokenReceived && !completedDeferred.isCompleted && clock() - start < hedgeAfter && clock() < deadline) {
                    delay(10)
                }
                if (firstTokenReceived) {
                    winnerEntry = entry
                    // cancel others
                    activeCalls.forEach { if (it.first != entry) it.second.cancel() }
                    health.recordSuccess(entry)
                    break@outer
                }
                if (completedDeferred.isCompleted) {
                    // failed; try next entry
                    break
                }
                // hedge: launch next entry in parallel (simplified: just continue loop after delay)
                hedged = true
                // wait a bit more for first token
                while (!firstTokenReceived && !completedDeferred.isCompleted && clock() < deadline) {
                    delay(10)
                    if (clock() - start > hedgeAfter) break
                }
                if (firstTokenReceived) {
                    winnerEntry = entry
                    activeCalls.forEach { if (it.first != entry) it.second.cancel() }
                    health.recordSuccess(entry)
                    break@outer
                }
                // cancel and move next
                handle.cancel()
                // record failure as empty? If no token, treat as empty_response
                if (!completedDeferred.isCompleted) {
                    failures.add(ErrorClass.EMPTY_RESPONSE)
                    health.recordFailure(entry, ErrorClass.EMPTY_RESPONSE)
                }
                break
            }
        }

        if (winnerEntry == null) throw AllFailedException()
        ChainResult(winnerEntry!!, hedged, firstTokenTime!! - (deadline - config.totalDeadlineMs), failures)
    }
}

class AllFailedException : RuntimeException("AllFailed")
