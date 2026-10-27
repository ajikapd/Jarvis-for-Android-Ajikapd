package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.asr.SherpaAsrSession
import com.jarvis.app.proactive.PhoneStateReader
import com.jarvis.app.proactive.ProactiveActionReceiver
import com.jarvis.app.proactive.ProactiveDelivery
import com.jarvis.app.proactive.ProactiveDeliveryLog
import com.jarvis.app.proactive.ProactiveNotification
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.companion.PhoneContextBus
import com.jarvis.core.privacy.SensitivityClassifier
import com.jarvis.core.proactive.DeliveryDecider
import com.jarvis.core.proactive.DeliveryMode
import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import com.jarvis.core.proactive.ProactiveReasonCode
import com.jarvis.core.proactive.ProactiveText
import com.jarvis.core.storage.ConversationJournal
import com.jarvis.core.storage.ConversationRepository
import com.jarvis.core.storage.FactPolicies
import com.jarvis.core.storage.FactsRepository
import com.jarvis.core.storage.JarvisDb
import com.jarvis.core.storage.JarvisSchema
import com.jarvis.core.storage.RetentionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-приёмник: гоняет запросы через весь конвейер с ADB без UI.
 *
 * Текстовая команда:
 *   adb shell am broadcast \
 *     -a com.jarvis.assistant.DEBUG_COMMAND \
 *     -n com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver \
 *     --es command "включи фонарик"
 *
 * Голос (wake word «джарвис»):
 *   adb shell am broadcast \
 *     -a com.jarvis.assistant.DEBUG_VOICE \
 *     -n com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver \
 *     --es action start|stop
 *
 * Проверка классификатора (только debug, только синтетические строки):
 *   adb shell am broadcast \
 *     -a com.jarvis.assistant.DEBUG_SANITIZE \
 *     -n com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver \
 *     --es text "Ваш код 482913, никому не сообщайте" [--es package ru.sberbankmobile]
 *
 * Снимок текущего PhoneContext (без содержимого уведомлений и экрана):
 *   adb shell am broadcast \
 *     -a com.jarvis.assistant.DEBUG_CONTEXT \
 *     -n com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver
 *
 * Приёмник зарегистрирован только в DEBUG-манифесте (app/src/debug),
 * отвечает структурированными строками в logcat (tag Jarvis.Debug).
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action=${intent.action} extras=${intent.extras?.keySet()}")
        when (intent.action) {
            ACTION_DEBUG_COMMAND -> handleCommand(context, intent)
            ACTION_DEBUG_VOICE -> handleVoice(context, intent)
            ACTION_ASR_TEST -> handleAsrTest(context, intent)
            ACTION_DEBUG_AUTONOMY -> handleAutonomy(context, intent)
            ACTION_DEBUG_SETTINGS -> handleSettings(context, intent)
            ACTION_DEBUG_SANITIZE -> if (BuildConfig.DEBUG) handleSanitize(intent)
            ACTION_DEBUG_CONTEXT -> if (BuildConfig.DEBUG) handleContextSnapshot()
            ACTION_DEBUG_MEMORY -> if (BuildConfig.DEBUG) handleMemory(context, intent)
            ACTION_DEBUG_PROACTIVE_STATE -> if (BuildConfig.DEBUG) handleProactiveState(context)
            ACTION_DEBUG_PROACTIVE -> if (BuildConfig.DEBUG) handleProactive(context, intent)
            ACTION_DEBUG_PROACTIVE_ACTION -> if (BuildConfig.DEBUG) {
                handleProactiveAction(context, intent)
            }
            ACTION_DEBUG_PROACTIVE_CLEAR -> if (BuildConfig.DEBUG) {
                handleProactiveClear(context, intent)
            }
        }
    }

    /**
     * Прогоняет строку через классификатор и печатает результат.
     *
     * Сырой текст в logcat НЕ выводится — только длина, вердикт и masked-версия,
     * чтобы проверочные строки не оседали в системном логе.
     */
    private fun handleSanitize(intent: Intent) {
        val raw = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (raw.isEmpty()) {
            Log.d(TAG, "sanitize: --es text \"...\" required")
            return
        }
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)?.trim()?.takeIf { it.isNotBlank() }
        val verdict = SensitivityClassifier.classify(raw, pkg)
        val masked = SensitivityClassifier.mask(raw)
        Log.d(
            TAG,
            "sanitize: len=${raw.length} sensitivity=$verdict " +
                "packageSensitive=${SensitivityClassifier.isSensitivePackage(pkg)} " +
                "changed=${masked != raw} masked=\"$masked\"",
        )
    }

    /** Текущий PhoneContext: только типы/пакеты наблюдений, без текста экрана и уведомлений. */
    private fun handleContextSnapshot() {
        val ctx = PhoneContextBus.current()
        Log.d(TAG, "context: ${ctx.describe()}")
        val recent = runCatching { PhoneContextBus.recent(15) }.getOrDefault(emptyList())
        recent.forEach { o ->
            Log.d(
                TAG,
                "context.event type=${o.type} source=${o.source} " +
                    "pkg=${o.packageName ?: "—"} sensitivity=${o.sensitivity}",
            )
        }
        Log.d(
            TAG,
            "context.events=${recent.size} " +
                "types=${recent.map { it.type }.distinct().joinToString(",")}",
        )
    }

    /**
     * Состояние постоянной памяти: только числа и метаданные, без текстов.
     *
     *   adb shell am broadcast -a com.jarvis.assistant.DEBUG_MEMORY \
     *     -n com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver \
     *     [--es probe 482913]
     *
     * probe — необязательная проверка «есть ли подстрока в истории» (true/false),
     * сам текст из БД не выводится.
     */
    private fun handleMemory(context: Context, intent: Intent) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val db = JarvisDb.get(app)
        val facts = FactsRepository(db)
        val conversations = ConversationRepository(db)
        val dbFile = app.getDatabasePath(JarvisSchema.DB_NAME)
        val walFile = java.io.File(dbFile.path + "-wal")
        val prefs = app.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

        Log.d(
            TAG,
            "memory: schema=v${db.readableDatabase.version} dbBytes=${dbFile.length()} " +
                "walBytes=${if (walFile.exists()) walFile.length() else 0L}",
        )
        Log.d(
            TAG,
            "memory: facts=${facts.count()} messages=${conversations.count()} " +
                "keep=${conversations.keepCount()}",
        )
        val boundaries = conversations.boundaries()
        Log.d(
            TAG,
            "memory: newestAgeMs=${RetentionPolicy.ageMs(boundaries.second, now)} " +
                "oldestAgeMs=${RetentionPolicy.ageMs(boundaries.first, now)} " +
                "ttlDays=${RetentionPolicy.CONVERSATION_TTL_MS / 86_400_000L}",
        )
        Log.d(
            TAG,
            "memory: migratedFacts=${prefs.getBoolean(FactPolicies.MIGRATED_FLAG, false)} " +
                "journalInstalled=${ConversationJournal.isInstalled()} " +
                "session=${ConversationJournal.sessionId.take(8)}",
        )

        val probe = intent.getStringExtra(EXTRA_PROBE)?.trim()
        if (!probe.isNullOrEmpty()) {
            val inMessages = conversations.containsText(probe)
            val inFacts = facts.list().any { it.text.contains(probe) }
            Log.d(TAG, "memory: probeLen=${probe.length} inMessages=$inMessages inFacts=$inFacts")
        }
    }

    /**
     * Б1: реальный снимок состояния для проактивной доставки.
     * Ничего не показывает и не говорит — только значения enum/boolean.
     *
     *   adb shell am broadcast -a com.jarvis.assistant.DEBUG_PROACTIVE_STATE \
     *     -n com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver
     */
    private fun handleProactiveState(context: Context) {
        val state = PhoneStateReader.read(context)
        Log.i(
            PROACTIVE_TAG,
            "state ringer=${state.ringerMode} filter=${state.interruptionFilter} " +
                "call=${state.callActive} session=${state.jarvisSessionActive} " +
                "speaking=${state.jarvisSpeaking} notifications=${state.notificationsAllowed} " +
                "voice=${state.voiceAvailable}",
        )
        val decision = DeliveryDecider.decide(ProactiveDecisionType.SUGGESTION, state)
        Log.i(
            PROACTIVE_TAG,
            "state decision type=SUGGESTION mode=${decision.mode} reason=${decision.reason}",
        )
    }

    /**
     * Б5: синтетическая доставка (только debug).
     *
     *   adb shell am broadcast -a com.jarvis.assistant.DEBUG_PROACTIVE \
     *     -n com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver \
     *     [--es mode auto|voice|silent] \
     *     [--es fakeState none|silent|dnd|call|session|nonotif|novoice]
     *
     * Текст фиксированный, произвольный не принимается. Лимиты проактивности и
     * бюджет не расходуются: ProactiveDecisionEngine здесь не вызывается.
     */
    private fun handleProactive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val modeRaw = intent.getStringExtra(EXTRA_MODE)?.trim()?.lowercase()
        val mode = when (modeRaw) {
            null, "", "auto" -> "auto"
            "voice", "silent" -> modeRaw
            else -> {
                Log.d(TAG, "proactive: unknown mode=$modeRaw, using auto")
                "auto"
            }
        }
        val fakeRaw = intent.getStringExtra(EXTRA_FAKE_STATE)?.trim()?.lowercase()
        val fake = when {
            fakeRaw.isNullOrBlank() -> null
            fakeRaw in PhoneStateReader.knownFakeStates() -> fakeRaw
            else -> {
                Log.d(TAG, "proactive: unknown fakeState=$fakeRaw, using real state")
                null
            }
        }
        val state = PhoneStateReader.applyFake(PhoneStateReader.read(app), fake)
        val now = System.currentTimeMillis()
        val decision = ProactiveDecision(
            type = ProactiveDecisionType.SUGGESTION,
            behaviorPatternKey = SYNTHETIC_PATTERN_KEY,
            fingerprint = "debug:synthetic:$now:${java.util.UUID.randomUUID()}",
            reasonCode = ProactiveReasonCode.SELECTED,
            reason = SYNTHETIC_TEXT,
            confidence = 1f,
            relevance = 1f,
            createdAtMillis = now,
        )
        val text = ProactiveText(
            voice = SYNTHETIC_TEXT,
            title = "Jarvis: проверка доставки",
            body = SYNTHETIC_TEXT,
        )
        val outcome = ProactiveDelivery.deliver(
            context = app,
            decision = decision,
            text = text,
            fakeState = fake,
            modeOverride = mode,
        )
        Log.i(
            PROACTIVE_TAG,
            "debug proactive mode=$mode fake=${fake ?: "none"} " +
                "state ringer=${state.ringerMode} filter=${state.interruptionFilter} " +
                "call=${state.callActive} session=${state.jarvisSessionActive} " +
                "speaking=${state.jarvisSpeaking} notifications=${state.notificationsAllowed} " +
                "voice=${state.voiceAvailable}",
        )
        Log.i(
            PROACTIVE_TAG,
            "debug proactive id=${outcome.deliveryId} mode=${outcome.mode} " +
                "reason=${outcome.reason} notification=${outcome.notificationShown} " +
                "voiceRequested=${outcome.voiceRequested} duplicate=${outcome.duplicate} " +
                "focus=see VoiceService log budget=untouched",
        )
    }

    /**
     * Б5: нажать кнопку уведомления без UI.
     *
     *   adb shell am broadcast -a com.jarvis.assistant.DEBUG_PROACTIVE_ACTION \
     *     -n com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver \
     *     --es action yes|no|later [--es deliveryId ...]
     *
     * Без deliveryId берётся последняя показанная (не SUPPRESS) запись журнала.
     */
    private fun handleProactiveAction(context: Context, intent: Intent) {
        val app = context.applicationContext
        val raw = intent.getStringExtra(EXTRA_ACTION)?.trim()?.lowercase()
        val action = when (raw) {
            "yes" -> ProactiveActionReceiver.ACTION_YES
            "no" -> ProactiveActionReceiver.ACTION_NO
            "later" -> ProactiveActionReceiver.ACTION_LATER
            else -> {
                Log.d(TAG, "proactive action: --es action yes|no|later required (got=$raw)")
                return
            }
        }
        val requestedId = intent.getStringExtra(EXTRA_DELIVERY_ID)
        val entry = requestedId?.let { ProactiveDeliveryLog.find(app, it) }
            ?: ProactiveDeliveryLog.all(app).lastOrNull { it.mode != DeliveryMode.SUPPRESS.name }
        if (entry == null) {
            Log.i(PROACTIVE_TAG, "debug proactive action=$raw skipped: no delivery in log")
            return
        }
        val deliveryId = requestedId ?: entry.deliveryId
        Log.i(
            PROACTIVE_TAG,
            "debug proactive action=$raw id=$deliveryId key=${entry.patternKey ?: "—"}",
        )
        ProactiveActionReceiver.perform(
            context = app,
            action = action,
            deliveryId = deliveryId,
            patternKey = entry.patternKey,
            fingerprint = entry.fingerprint,
        )
    }

    /**
     * Б5: снять синтетические уведомления. С --ez log true очищает журнал
     * доставки целиком.
     */
    private fun handleProactiveClear(context: Context, intent: Intent) {
        val app = context.applicationContext
        val synthetic = ProactiveDeliveryLog.all(app).filter {
            it.patternKey?.startsWith(SYNTHETIC_PATTERN_PREFIX) == true
        }
        synthetic.forEach { ProactiveNotification.cancel(app, it.deliveryId) }
        val clearLog = intent.getBooleanExtra(EXTRA_LOG, false)
        if (clearLog) ProactiveDeliveryLog.clear(app)
        Log.i(
            PROACTIVE_TAG,
            "debug proactive clear notifications=${synthetic.size} logCleared=$clearLog",
        )
    }

    private fun handleCommand(context: Context, intent: Intent) {        val text = intent.getStringExtra(EXTRA_COMMAND)?.trim().orEmpty()
        // В logcat пишем только masked-версию: коды/карты из реплики не должны
        // оседать в системном логе. Сам запрос уходит в конвейер без изменений.
        Log.d(TAG, "command text=\"${SensitivityClassifier.mask(text)}\"")
        if (text.isEmpty()) {
            Log.d(TAG, "ignored: empty command")
            return
        }

        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Полный путь VoiceService → brain → TTS (логи slo1s / first-audio)
                if (!VoiceController.isRunning) {
                    VoiceController.start(app)
                    var waited = 0
                    while (!VoiceController.isRunning && waited < 50) {
                        Thread.sleep(100)
                        waited++
                    }
                }
                if (VoiceController.isRunning) {
                    Log.d(TAG, "inject via VoiceService e2e: \"${SensitivityClassifier.mask(text)}\"")
                    VoiceController.notifyRequest(UserRequest(text))
                    return@launch
                }
                BrainGraph.warmLlmBrain(app)
                val response = BrainGraph.build(app).process(UserRequest(text))
                Log.d(
                    TAG,
                    "request=\"${SensitivityClassifier.mask(text)}\" -> intent=${response.intent?.type} " +
                        "tool=${response.toolResult?.toolId}/${response.toolResult?.status} " +
                        "status=${response.status} durationMs=${response.durationMs} " +
                        "reply=\"${SensitivityClassifier.mask(response.replyText)}\"",
                )
            } catch (e: Throwable) {
                Log.e(TAG, "command processing failed: ${e.message}", e)
            }
        }
    }

    private fun handleVoice(context: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_ACTION)) {
            "start" -> startVoice(context)
            "stop" -> stopVoice(context)
            else -> Log.d(TAG, "voice: known action expected (start|stop)")
        }
    }

    private fun startVoice(context: Context) {
        // Обработка команд и озвучивание — в VoiceService (coreListener),
        // который живёт столько же, сколько foreground-сервис.
        VoiceController.start(context)
        Log.d(TAG, "voice: start requested")
    }

    private fun stopVoice(context: Context) {
        VoiceController.stop(context)
    }

    /**
     * Debug settings:
     *   adb shell am broadcast -a com.jarvis.assistant.DEBUG_SETTINGS \
     *     -n com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver \
     *     --es preferCloud true|false
     */
    private fun handleSettings(context: Context, intent: Intent) {
        val preferCloud = intent.getStringExtra("preferCloud")?.trim()?.lowercase()
        if (preferCloud != null) {
            val enabled = preferCloud == "true" || preferCloud == "1" || preferCloud == "on"
            JarvisSettings.setPreferCloudLlm(context, enabled)
            Log.d(TAG, "settings preferCloud=$enabled cloudConfigured=${JarvisSettings.cloudConfigured(context)}")
        } else {
            Log.d(
                TAG,
                "settings preferCloud=${JarvisSettings.preferCloudLlm(context)} " +
                    "cloudConfigured=${JarvisSettings.cloudConfigured(context)}",
            )
        }
    }

    /**
     * Stage 8F debug: autonomous flashlight через policy+gate+SafeAutonomousExecutor.
     *
     *   adb shell am broadcast \
     *     -a com.jarvis.assistant.DEBUG_AUTONOMY \
     *     -n com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver \
     *     --es action flashlight_on --ez live true
     *
     * live=false (default) — только plan/dry-run, без torch.
     */
    private fun handleAutonomy(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION)?.trim().orEmpty()
        val live = intent.getBooleanExtra("live", false)
        Log.d(TAG, "autonomy action=$action live=$live")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                BrainGraph.build(context.applicationContext)
                val executor = BrainGraph.toolExecutor()
                    ?: error("toolExecutor not ready")
                com.jarvis.app.companion.CompanionProactive.attachSafeExecutor(
                    toolExecutor = executor,
                    live = live,
                )
                val proposed = when (action) {
                    "flashlight_on" ->
                        com.jarvis.core.autonomy.ProposedActionMapper.fromToolId(
                            "flashlight",
                            args = mapOf("action" to "on"),
                        )
                    "flashlight_off" ->
                        com.jarvis.core.autonomy.ProposedActionMapper.fromToolId(
                            "flashlight",
                            args = mapOf("action" to "off"),
                        )
                    "volume_up" ->
                        com.jarvis.core.autonomy.ProposedActionMapper.fromToolId(
                            "volume",
                            actionId = "volume_up",
                            args = mapOf("action" to "up"),
                        )
                    else -> null
                }
                if (proposed == null) {
                    Log.d(TAG, "autonomy: unknown action (flashlight_on|flashlight_off|volume_up)")
                    return@launch
                }
                val decision = com.jarvis.core.proactive.ProactiveDecision(
                    type = com.jarvis.core.proactive.ProactiveDecisionType.SUGGESTION,
                    reasonCode = com.jarvis.core.proactive.ProactiveReasonCode.SELECTED,
                    confidence = 0.9f,
                    relevance = 0.9f,
                    createdAtMillis = System.currentTimeMillis(),
                    fingerprint = "debug-autonomy",
                    behaviorPatternKey = "debug:$action",
                    reason = "DEBUG_AUTONOMY",
                )
                if (!live) {
                    val plan = com.jarvis.app.companion.CompanionProactive.planAutonomy(
                        decision,
                        proposed,
                    )
                    Log.d(
                        TAG,
                        "autonomy dry-run status=${plan.status} verdict=${plan.verdict.type} " +
                            "tool=${plan.draftToolCall?.toolId} notes=${plan.notes.take(5)}",
                    )
                    return@launch
                }
                val result = com.jarvis.app.companion.CompanionProactive.trySafeExecute(
                    decision,
                    proposed,
                )
                Log.d(
                    TAG,
                    "autonomy exec executed=${result.executed} reason=${result.reason} " +
                        "plan=${result.plan?.status} tool=${result.result?.toolId}/" +
                        "${result.result?.status}",
                )
            } catch (e: Throwable) {
                Log.e(TAG, "autonomy failed: ${e.message}", e)
            }
        }
    }

    /**
     * Прогоняет WAV-файл (PCM16, 16 kHz) через ASR и логирует распознанный текст.
     * Используется для проверки ASR-модели без реального голоса.
     */
    private fun handleAsrTest(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: run {
            Log.d(TAG, "asrtest: --es path /path/to/file.wav required")
            return
        }
        val app = context.applicationContext
        val modelDir = File(app.getExternalFilesDir(null), "models/${SherpaAsrSession.MODEL_DIR}")
        if (!modelDir.isDirectory) {
            Log.d(TAG, "asrtest: model dir missing: ${modelDir.absolutePath}")
            return
        }
        val wavFile = File(path)
        if (!wavFile.isFile) {
            Log.d(TAG, "asrtest: wav not found: $path")
            return
        }
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Один asrtest за раз — иначе параллельные broadcast'ы портят логи
                synchronized(asrTestLock) {
                val session = SherpaAsrSession(modelDir.absolutePath)
                val data = wavFile.readBytes()
                require(data.size > 44) { "WAV too small (header corrupted?)" }
                // Ищем data-chunk — у espeak/ffmpeg заголовок часто > 44 байт
                var offset = findWavDataOffset(data)
                var lastText = ""
                var endpoint = false
                val chunk = FloatArray(SherpaAsrSession.SAMPLE_RATE / 10)
                val all = ArrayList<Float>()
                while (offset + 1 < data.size) {
                    val remain = (data.size - offset) / 2
                    val n = minOf(chunk.size, remain)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        val b = offset + i * 2
                        val s = (data[b].toInt() and 0xFF) or ((data[b + 1].toInt() and 0xFF) shl 8)
                        chunk[i] = s.toShort() / 32768.0f
                        all.add(chunk[i])
                    }
                    // хвост чанка нулями, если WAV короче step
                    if (n < chunk.size) {
                        for (i in n until chunk.size) chunk[i] = 0f
                    }
                    offset += n * 2
                    val r = session.processChunk(if (n == chunk.size) chunk else chunk.copyOf(n))
                    if (r.text.isNotBlank()) lastText = r.text
                    if (r.isEndpoint) endpoint = true
                    // не break — для offline/EN нужен весь PCM
                }
                session.release()
                Log.d(TAG, "asrtest: streamingRU endpoint=$endpoint text=\"$lastText\" pcm=${all.size}")

                val multiDir = File(
                    app.getExternalFilesDir(null),
                    "models/${com.jarvis.asr.OfflineMultilingualAsr.MODEL_DIR}",
                )
                if (multiDir.isDirectory && File(multiDir, "tokens.txt").isFile) {
                    val off = com.jarvis.asr.OfflineMultilingualAsr(multiDir.absolutePath)
                    val pcm = FloatArray(all.size) { all[it] }
                    val multi = off.decode(pcm)
                    val tail = com.jarvis.asr.CommandPcmRefiner.tailOf(pcm)
                    val multiTail = if (tail.size < pcm.size) off.decode(tail) else ""
                    off.close()
                    val enDir = File(
                        app.getExternalFilesDir(null),
                        "models/${com.jarvis.asr.AsrModelSpec.EN_STREAMING.dirName}",
                    )
                    val enFull: String
                    val enTail: String
                    if (
                        File(enDir, "encoder.int8.onnx").isFile && File(enDir, "tokens.txt").isFile
                    ) {
                        val en = com.jarvis.asr.BufferedEnAsr(enDir.absolutePath)
                        enFull = en.decode(pcm)
                        enTail = if (tail.size < pcm.size) en.decode(tail) else ""
                        en.close()
                    } else {
                        enFull = ""
                        enTail = ""
                    }
                    val enHint = com.jarvis.asr.CommandPcmRefiner.bestLatinHint(
                        enFull, enTail, multiTail,
                    )
                    val selected = com.jarvis.core.nlp.CommandTextSelector.select(
                        lastText, multi, enHint,
                    )
                    Log.d(
                        TAG,
                        "asrtest: offlineMulti=\"$multi\" multiTail=\"$multiTail\" " +
                            "en=\"$enFull\" enTail=\"$enTail\" hint=\"$enHint\" selected=\"$selected\"",
                    )
                } else {
                    Log.d(TAG, "asrtest: offline multilingual model missing")
                }
                } // synchronized
            } catch (e: Exception) {
                Log.d(TAG, "asrtest: failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "Jarvis.Debug"
        private const val PROACTIVE_TAG = "Jarvis.ProactiveDelivery"
        private val asrTestLock = Any()
        const val ACTION_DEBUG_COMMAND = "com.jarvis.assistant.DEBUG_COMMAND"
        const val ACTION_DEBUG_VOICE = "com.jarvis.assistant.DEBUG_VOICE"
        const val ACTION_ASR_TEST = "com.jarvis.assistant.ASR_TEST"
        const val ACTION_DEBUG_AUTONOMY = "com.jarvis.assistant.DEBUG_AUTONOMY"
        const val ACTION_DEBUG_SETTINGS = "com.jarvis.assistant.DEBUG_SETTINGS"
        const val ACTION_DEBUG_SANITIZE = "com.jarvis.assistant.DEBUG_SANITIZE"
        const val ACTION_DEBUG_CONTEXT = "com.jarvis.assistant.DEBUG_CONTEXT"
        const val ACTION_DEBUG_MEMORY = "com.jarvis.assistant.DEBUG_MEMORY"
        const val ACTION_DEBUG_PROACTIVE_STATE = "com.jarvis.assistant.DEBUG_PROACTIVE_STATE"
        const val ACTION_DEBUG_PROACTIVE = "com.jarvis.assistant.DEBUG_PROACTIVE"
        const val ACTION_DEBUG_PROACTIVE_ACTION = "com.jarvis.assistant.DEBUG_PROACTIVE_ACTION"
        const val ACTION_DEBUG_PROACTIVE_CLEAR = "com.jarvis.assistant.DEBUG_PROACTIVE_CLEAR"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_PROBE = "probe"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_FAKE_STATE = "fakeState"
        private const val EXTRA_DELIVERY_ID = "deliveryId"
        private const val EXTRA_LOG = "log"
        private const val SYNTHETIC_TEXT = "Синтетика: проверка доставки"
        private const val SYNTHETIC_PATTERN_KEY = "debug:synthetic"
        private const val SYNTHETIC_PATTERN_PREFIX = "debug:"

        /** Смещение до PCM в RIFF/WAVE (ищем chunk `data`). */
        private fun findWavDataOffset(data: ByteArray): Int {
            if (data.size < 44) return 44
            var pos = 12
            while (pos + 8 <= data.size) {
                val id = String(data, pos, 4, Charsets.US_ASCII)
                val size = (data[pos + 4].toInt() and 0xFF) or
                    ((data[pos + 5].toInt() and 0xFF) shl 8) or
                    ((data[pos + 6].toInt() and 0xFF) shl 16) or
                    ((data[pos + 7].toInt() and 0xFF) shl 24)
                if (id == "data") return pos + 8
                pos += 8 + size
                if (size < 0) break
            }
            return 44
        }
    }
}