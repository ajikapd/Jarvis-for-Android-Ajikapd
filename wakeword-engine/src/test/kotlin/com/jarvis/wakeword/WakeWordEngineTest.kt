package com.jarvis.wakeword

import com.jarvis.asr.AsrChunkResult
import com.jarvis.asr.AsrSession
import com.jarvis.core.common.model.RequestSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeWordEngineTest {

    private class FakeAsrSession : AsrSession {
        val results = ArrayDeque<AsrChunkResult>()
        var resetCount = 0

        override fun processChunk(samples: FloatArray): AsrChunkResult =
            results.removeFirstOrNull() ?: AsrChunkResult("", false)

        override fun reset() { resetCount++ }
        override fun release() {}
    }

    private fun fakeSession(vararg first: Pair<String, Boolean>): FakeAsrSession =
        FakeAsrSession().apply {
            first.forEach { (t, ep) -> results.add(AsrChunkResult(t, ep)) }
        }

    @Test
    fun `emits voice request after wake word and endpoint`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джарвис включи" to false, "джарвис включи фонарик" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, seen.size)
        assertEquals("включи фонарик", seen[0].text)
        assertEquals(RequestSource.VOICE, seen[0].source)
        assertEquals(WakeWordState.IDLE, engine.state)
        job.cancel()
    }

    @Test
    fun `wake word alone emits empty command and stays listening`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джарвис" to false, "джарвис" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, seen.size)
        assertEquals("", seen[0].text)
        assertEquals(RequestSource.VOICE, seen[0].source)
        assertEquals(WakeWordState.AWAITING_COMMAND, engine.state)
        job.cancel()
    }

    @Test
    fun `after bare wake the follow-up command is accepted without wake word`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession(
            "джарвис" to false, "джарвис" to true,
            "включи свет" to false, "включи свет" to true,
        )
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        repeat(4) { engine.onAudioChunk(floatArrayOf(0f)) }
        yield()

        assertEquals(listOf("", "включи свет"), seen.map { it.text })
        assertEquals(WakeWordState.IDLE, engine.state)
        job.cancel()
    }

    @Test
    fun `re-fed wake word after bare wake does not double emit`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession(
            "джарвис" to false, "джарвис" to true,
            "джарвис" to false, "джарвис" to true,
        )
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        var wakes = 0
        val job = launch {
            engine.userRequests.collect { seen.add(it) }
        }
        val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
        yield()

        repeat(4) { engine.onAudioChunk(floatArrayOf(0f)) }
        yield()

        assertEquals(1, wakes)
        assertEquals(listOf(""), seen.map { it.text })
        assertEquals(WakeWordState.AWAITING_COMMAND, engine.state)
        job.cancel()
        wakeJob.cancel()
    }

    @Test
    fun `foreign voice after wake gives no command`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джарвис включи" to false, "джарвис включи фонарик" to true)
        val engine = WakeWordEngine(
            session,
            CoroutineScope(Dispatchers.Unconfined),
            voiceAllowed = { _, _ -> false },
        )
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(0, seen.size)
        assertEquals(WakeWordState.IDLE, engine.state)
        job.cancel()
    }

    @Test
    fun `foreign voice in follow-up keeps waiting for the owner`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("чужая речь из видео" to true, "выключи фонарик" to true)
        val verdicts = ArrayDeque(listOf(false, true))
        val awaited = mutableListOf<Boolean>()
        val engine = WakeWordEngine(
            session,
            CoroutineScope(Dispatchers.Unconfined),
            voiceAllowed = { _, wait -> awaited += wait; verdicts.removeFirst() },
        )
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.listenForFollowUp(60_000)
        engine.onAudioChunk(floatArrayOf(0f))
        yield()
        assertEquals(0, seen.size)
        assertEquals(WakeWordState.AWAITING_COMMAND, engine.state)

        engine.onAudioChunk(floatArrayOf(0f))
        yield()
        assertEquals(listOf("выключи фонарик"), seen.map { it.text })
        assertEquals(listOf(true, true), awaited)
        job.cancel()
    }

    @Test
    fun `wakeDetected fires on wake word`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джарвис привет" to false, "джарвис привет" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val job = launch { engine.wakeDetected.collect { wakes++ } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, wakes)
        job.cancel()
    }

    @Test
    fun `wakeDetected does not fire without wake word`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("включи свет" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val job = launch { engine.wakeDetected.collect { wakes++ } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(0, wakes)
        job.cancel()
    }

    @Test
    fun `stays idle without wake word`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("включи фонарик" to false, "привет как дела" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(WakeWordState.IDLE, engine.state)
        assertEquals(0, seen.size)
        job.cancel()
    }

    @Test
    fun `resets session on endpoint while idle`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("привет" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        engine.onAudioChunk(floatArrayOf(0f))
        assertEquals(1, session.resetCount)
    }

    @Test
    fun `timeout emits partial command without wake word`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джервис включи свет" to false, "джервис включи свет" to false)
        val engine = WakeWordEngine(
            session, CoroutineScope(Dispatchers.Default), maxCommandDurationMs = 30
        )
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        engine.onAudioChunk(floatArrayOf(0f))
        Thread.sleep(200)
        yield()
        yield()

        assertEquals(1, seen.size)
        assertEquals("включи свет", seen[0].text)
        job.cancel()
    }

    @Test
    fun `handles jarvis with comma separator`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джарвис, выключи фонарик" to false, "джарвис, выключи фонарик" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, seen.size)
        assertEquals("выключи фонарик", seen[0].text)
        job.cancel()
    }

    @Test
    fun `fuzzy wake variant triggers and strips`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джарвиз включи свет" to false, "джарвиз включи свет" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, wakes)
        assertEquals("включи свет", seen.single().text)
        wakeJob.cancel()
        job.cancel()
    }

    @Test
    fun `shortened wake token is accepted`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("жарвис" to false, "жарвис" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, wakes)
        wakeJob.cancel()
    }

    @Test
    fun `noise variant djarus triggers like wake`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джарус включи свет" to false, "джарус включи свет" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, wakes)
        assertEquals("включи свет", seen.single().text)
        wakeJob.cancel()
        job.cancel()
    }

    @Test
    fun `unrelated speech does not trigger fuzzy wake`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("привет как дела" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(0, wakes)
        wakeJob.cancel()
    }

    @Test
    fun `noise variant dzhorus with swapped vowel triggers via consonant skeleton`() =
        runBlocking(Dispatchers.Unconfined) {
            val session = fakeSession("джорус включи свет" to false, "джорус включи свет" to true)
            val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
            var wakes = 0
            val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
            val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
            val job = launch { engine.userRequests.collect { seen.add(it) } }
            yield()

            engine.onAudioChunk(floatArrayOf(0f))
            engine.onAudioChunk(floatArrayOf(0f))
            yield()

            assertEquals(1, wakes)
            assertEquals("включи свет", seen.single().text)
            wakeJob.cancel()
            job.cancel()
        }

    @Test
    fun `short noise fragment does not trigger skeleton wake`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джа" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(0, wakes)
        wakeJob.cancel()
    }

    @Test
    fun `wake-like token in the middle of long noise speech does not trigger`() =
        runBlocking(Dispatchers.Unconfined) {
            val session = fakeSession(
                "я хочу папе сделать сюрприз джорус" to false,
                "я хочу папе сделать сюрприз джорус" to true,
            )
            val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
            var wakes = 0
            val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
            yield()

            engine.onAudioChunk(floatArrayOf(0f))
            engine.onAudioChunk(floatArrayOf(0f))
            yield()

            assertEquals(0, wakes)
            wakeJob.cancel()
        }

    @Test
    fun `strong wake after background noise extracts command`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession(
            "новости погода пробки джарвис включи свет" to false,
        )
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, wakes)
        assertEquals("включи свет", seen.single().text)
        wakeJob.cancel()
        job.cancel()
    }

    @Test
    fun `command then wake keeps short command prefix`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("включи свет джарвис" to false)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, wakes)
        assertEquals("включи свет", seen.single().text)
        wakeJob.cancel()
        job.cancel()
    }

    @Test
    fun `long idle noise without wake resets asr buffer`() = runBlocking(Dispatchers.Unconfined) {
        val noise = (1..12).joinToString(" ") { "слово$it" }
        val session = fakeSession(noise to false)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        engine.onAudioChunk(floatArrayOf(0f))
        assertEquals(1, session.resetCount)
    }

    @Test
    fun `external KWS wake arms ASR listening without prior transcript`() =
        runBlocking(Dispatchers.Unconfined) {
            val session = fakeSession("включи свет" to false, "включи свет" to true)
            val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
            var wakes = 0
            val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
            val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
            val job = launch { engine.userRequests.collect { seen.add(it) } }
            yield()

            engine.notifyExternalWake()
            yield()
            assertEquals(1, wakes)
            assertEquals(WakeWordState.LISTENING, engine.state)

            engine.onAudioChunk(floatArrayOf(0f))
            engine.onAudioChunk(floatArrayOf(0f))
            yield()

            assertEquals("включи свет", seen.single().text)
            wakeJob.cancel()
            job.cancel()
        }

    @Test
    fun `single frame strong wake fires immediately`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джарвис" to false)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, wakes)
        assertEquals(WakeWordState.LISTENING, engine.state)
        wakeJob.cancel()
    }

    @Test
    fun `single endpoint strong wake fires`() = runBlocking(Dispatchers.Unconfined) {
        val session = fakeSession("джарвис" to true)
        val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
        var wakes = 0
        val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
        val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
        val job = launch { engine.userRequests.collect { seen.add(it) } }
        yield()

        engine.onAudioChunk(floatArrayOf(0f))
        yield()

        assertEquals(1, wakes)
        assertEquals(listOf(""), seen.map { it.text })
        assertEquals(WakeWordState.AWAITING_COMMAND, engine.state)
        wakeJob.cancel()
        job.cancel()
    }

    @Test
    fun `single frame wake spike does not fire without confirmation`() =
        runBlocking(Dispatchers.Unconfined) {
            val session = fakeSession("джорус" to false, "привет" to true)
            val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
            var wakes = 0
            val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
            yield()

            engine.onAudioChunk(floatArrayOf(0f))
            engine.onAudioChunk(floatArrayOf(0f))
            yield()

            assertEquals(0, wakes)
            wakeJob.cancel()
        }

    @Test
    fun `weak wake fires on endpoint without second frame`() =
        runBlocking(Dispatchers.Unconfined) {
            val session = fakeSession("джорус" to true)
            val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
            var wakes = 0
            val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
            yield()

            engine.onAudioChunk(floatArrayOf(0f))
            yield()

            assertEquals(1, wakes)
            wakeJob.cancel()
        }

    @Test
    fun `wake split across endpoints is reassembled via hangover`() =
        runBlocking(Dispatchers.Unconfined) {
            val session = fakeSession(
                "джа" to true,                 // endpoint mid-wake → hangover
                "рвис включи свет" to false,   // continue → strong wake on first frame
                "рвис включи свет" to true,
            )
            val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
            var wakes = 0
            val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
            val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
            val job = launch { engine.userRequests.collect { seen.add(it) } }
            yield()

            engine.onAudioChunk(floatArrayOf(0f))
            engine.onAudioChunk(floatArrayOf(0f))
            engine.onAudioChunk(floatArrayOf(0f))
            yield()

            assertEquals(1, wakes)
            assertEquals("включи свет", seen.single().text)
            wakeJob.cancel()
            job.cancel()
        }

    @Test
    fun `wake split as two tokens in one utterance is coalesced`() =
        runBlocking(Dispatchers.Unconfined) {
            val session = fakeSession(
                "джа рвис включи свет" to false,
                "джа рвис включи свет" to true,
            )
            val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
            var wakes = 0
            val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
            val wakeJob = launch { engine.wakeDetected.collect { wakes++ } }
            val job = launch { engine.userRequests.collect { seen.add(it) } }
            yield()

            engine.onAudioChunk(floatArrayOf(0f))
            engine.onAudioChunk(floatArrayOf(0f))
            yield()

            assertEquals(1, wakes)
            assertEquals("включи свет", seen.single().text)
            wakeJob.cancel()
            job.cancel()
        }

    @Test
    fun `incomplete bare verb is held and completed on next utterance`() =
        runBlocking(Dispatchers.Unconfined) {
            val session = fakeSession(
                "джарвис включи" to false,
                "джарвис включи" to true,   // incomplete → AWAITING, no emit
                "свет" to false,
                "свет" to true,             // completes → "включи свет"
            )
            val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
            val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
            val job = launch { engine.userRequests.collect { seen.add(it) } }
            yield()

            repeat(4) { engine.onAudioChunk(floatArrayOf(0f)) }
            yield()

            assertEquals(listOf("включи свет"), seen.map { it.text })
            assertEquals(WakeWordState.IDLE, engine.state)
            job.cancel()
        }

    @Test
    fun `cyrillic english name is normalized to latin in command`() =
        runBlocking(Dispatchers.Unconfined) {
            val session = fakeSession(
                "джарвис открой инси" to false,
                "джарвис открой инси" to true,
            )
            val engine = WakeWordEngine(session, CoroutineScope(Dispatchers.Unconfined))
            val seen = mutableListOf<com.jarvis.core.common.model.UserRequest>()
            val job = launch { engine.userRequests.collect { seen.add(it) } }
            yield()

            engine.onAudioChunk(floatArrayOf(0f))
            engine.onAudioChunk(floatArrayOf(0f))
            yield()

            assertEquals("открой incy", seen.single().text)
            job.cancel()
        }
}