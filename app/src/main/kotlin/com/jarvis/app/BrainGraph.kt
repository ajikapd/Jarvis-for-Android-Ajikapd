package com.jarvis.app

import android.content.Context
import android.os.Process
import com.jarvis.app.asr.CloudAsrFactory
import com.jarvis.app.tts.CloudTtsFactory
import com.jarvis.brain.AssistantOrchestrator
import com.jarvis.brain.ChatLlmEngine
import com.jarvis.brain.DialogueResponder
import com.jarvis.brain.LlmBrain
import com.jarvis.brain.LongTermMemory
import com.jarvis.brain.RuleBasedBrain
import com.jarvis.brain.SkillManifestLoader
import com.jarvis.core.capability.CapabilityAwareToolExecutor
import com.jarvis.core.capability.CapabilityDiscovery
import com.jarvis.core.capability.CapabilityExecutor
import com.jarvis.core.capability.CapabilityRegistry
import com.jarvis.core.capability.ToolCapabilityCatalog
import com.jarvis.core.common.DefaultToolExecutor
import com.jarvis.core.common.RiskBasedPolicy
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.ConversationContext
import com.jarvis.core.common.model.PendingAction
import com.jarvis.core.skills.SkillManifestRegistry
import com.jarvis.core.skills.SkillRetriever
import com.jarvis.tools.BrightnessTool
import com.jarvis.tools.CalendarTool
import com.jarvis.tools.CallTool
import com.jarvis.tools.ClipboardTool
import com.jarvis.tools.ContactResolver
import com.jarvis.tools.ContactsTool
import com.jarvis.tools.FilesTool
import com.jarvis.tools.FlashlightTool
import com.jarvis.tools.KnowledgeTool
import com.jarvis.tools.LauncherTool
import com.jarvis.tools.MusicTool
import com.jarvis.tools.NotificationTool
import com.jarvis.tools.ScreenshotTool
import com.jarvis.tools.SmsTool
import com.jarvis.tools.SystemSettingTool
import com.jarvis.tools.TelegramSendTool
import com.jarvis.tools.TimerTool
import com.jarvis.tools.UiClickTool
import com.jarvis.tools.UiGlobalTool
import com.jarvis.tools.UiReadTool
import com.jarvis.tools.UiSwipeTool
import com.jarvis.tools.UiTypeTool
import com.jarvis.tools.UiWaitTool
import com.jarvis.tools.VolumeTool
import com.jarvis.tools.WeatherTool
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Composition root: собирает граф ассистента (JARVIS_BRAIN_ARCHITECTURE §1, §10).
 */
object BrainGraph {

    private val lock = ReentrantLock()

    @Volatile private var cached: AssistantOrchestrator? = null
    @Volatile private var memoryInstance: LongTermMemory? = null
    /** Тот же ConversationContext, что у оркестратора (для очистки с экрана «Память»). */
    @Volatile private var dialogueContext: com.jarvis.core.common.model.ConversationContext? = null
    @Volatile private var llmBrain: LlmBrain? = null
    @Volatile private var chatLlm: LlamaChatLlmEngine? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var capabilityRegistry: CapabilityRegistry? = null
    @Volatile private var capabilityDiscovery: CapabilityDiscovery? = null
    @Volatile private var capabilityExecutor: CapabilityExecutor? = null
    @Volatile private var catalogToolRegistry: com.jarvis.core.execution.CatalogToolRegistry? = null
    @Volatile private var catalogToolDiscovery: com.jarvis.core.discovery.CatalogToolDiscovery? = null
    @Volatile private var aiProviderRegistry: com.jarvis.brain.aiprovider.AIProviderRegistry? = null
    @Volatile private var chainCloudProvider: com.jarvis.brain.cloud.ChainCloudProvider? = null
    @Volatile private var aiRouter: com.jarvis.brain.aiprovider.AIRouter? = null
    @Volatile private var aiPlanner: com.jarvis.core.planner.AIPlanner? = null
    @Volatile private var agentExecutionLoop: com.jarvis.core.agent.AgentExecutionLoop? = null
    @Volatile private var resultVerifier: com.jarvis.core.verification.ResultVerifier? = null
    @Volatile private var pluginRegistry: com.jarvis.core.plugin.PluginRegistry? = null
    @Volatile private var mcpRegistry: com.jarvis.core.mcp.McpRegistry? = null
    @Volatile private var filesAgent: com.jarvis.core.filesagent.FilesAgent? = null
    @Volatile private var browserAgent: com.jarvis.core.browseragent.BrowserAgent? = null
    @Volatile private var routineStore: com.jarvis.core.routines.RoutineStore? = null
    @Volatile private var routineGenerator: com.jarvis.core.routines.RoutineGenerator? = null
    @Volatile private var memory2Store: com.jarvis.core.memory.Memory2Store? = null
    @Volatile private var asrRouter: com.jarvis.core.voice.AsrRouter? = null
    @Volatile private var ttsRouter: com.jarvis.core.voice.TtsRouter? = null
    @Volatile private var visionRouter: com.jarvis.core.vision.VisionRouter? = null
    @Volatile private var proactiveAgent: com.jarvis.core.agent.ProactiveAgent? = null
    @Volatile private var remoteDeviceRegistry: com.jarvis.core.remote.RemoteDeviceRegistry? = null

    fun capabilityRegistry(): CapabilityRegistry? = capabilityRegistry
    fun capabilityDiscovery(): CapabilityDiscovery? = capabilityDiscovery
    fun capabilityExecutor(): CapabilityExecutor? = capabilityExecutor
    fun catalogToolRegistry(): com.jarvis.core.execution.CatalogToolRegistry? = catalogToolRegistry
    fun catalogToolDiscovery(): com.jarvis.core.discovery.CatalogToolDiscovery? = catalogToolDiscovery
    fun aiProviderRegistry(): com.jarvis.brain.aiprovider.AIProviderRegistry? = aiProviderRegistry
    fun aiRouter(): com.jarvis.brain.aiprovider.AIRouter? = aiRouter
    fun aiPlanner(): com.jarvis.core.planner.AIPlanner? = aiPlanner
    fun agentExecutionLoop(): com.jarvis.core.agent.AgentExecutionLoop? = agentExecutionLoop
    fun resultVerifier(): com.jarvis.core.verification.ResultVerifier? = resultVerifier
    fun pluginRegistry(): com.jarvis.core.plugin.PluginRegistry? = pluginRegistry
    fun mcpRegistry(): com.jarvis.core.mcp.McpRegistry? = mcpRegistry
    fun filesAgent(): com.jarvis.core.filesagent.FilesAgent? = filesAgent
    fun browserAgent(): com.jarvis.core.browseragent.BrowserAgent? = browserAgent
    fun routineStore(): com.jarvis.core.routines.RoutineStore? = routineStore
    fun routineGenerator(): com.jarvis.core.routines.RoutineGenerator? = routineGenerator
    fun memory2Store(): com.jarvis.core.memory.Memory2Store? = memory2Store
    fun asrRouter(): com.jarvis.core.voice.AsrRouter? = asrRouter
    fun ttsRouter(): com.jarvis.core.voice.TtsRouter? = ttsRouter
    fun visionRouter(): com.jarvis.core.vision.VisionRouter? = visionRouter
    fun proactiveAgent(): com.jarvis.core.agent.ProactiveAgent? = proactiveAgent
    fun remoteDeviceRegistry(): com.jarvis.core.remote.RemoteDeviceRegistry? = remoteDeviceRegistry
    fun toolExecutor(): com.jarvis.core.common.ToolExecutor? = toolExecutor

    fun diagnosticsSnapshot(context: android.content.Context): com.jarvis.core.diagnostics.SystemDiagnosticsSnapshot {
        val app = context.applicationContext
        val asr = asrRouter?.select(
            com.jarvis.core.voice.VoiceRouteContext(
                isOnline = CloudAsrFactory.isOnline(app),
                preferCloud = JarvisSettings.asrMode(app) == "CLOUD_PREFERRED",
                privacyRestricted = JarvisSettings.asrMode(app) == "LOCAL_ONLY",
            ),
        )
        val tts = ttsRouter?.select(
            com.jarvis.core.voice.VoiceRouteContext(
                isOnline = CloudTtsFactory.isOnline(app),
                preferCloud = JarvisSettings.ttsMode(app) == "CLOUD_PREFERRED",
                privacyRestricted = JarvisSettings.ttsMode(app) == "LOCAL_ONLY",
            ),
        )
        val vision = visionRouter?.select(isOnline = true, preferCloud = false)
        val features = featureStatusSnapshot(app)
        return com.jarvis.core.diagnostics.SystemDiagnosticsSnapshot(
            capturedAtMillis = System.currentTimeMillis(),
            agentLoopPresent = agentExecutionLoop != null,
            plannerPresent = aiPlanner != null,
            aiRouterPresent = aiRouter != null,
            cloudKeyConfigured = JarvisSettings.cloudConfigured(app),
            preferCloud = JarvisSettings.preferCloudLlm(app),
            providersLocal = aiProviderRegistry?.localProviders()?.size ?: 0,
            providersCloud = aiProviderRegistry?.cloudProviders()?.size ?: 0,
            providersAvailable = aiProviderRegistry?.available()?.size ?: 0,
            catalogSize = catalogToolRegistry?.size() ?: 0,
            discoveryOn = catalogToolDiscovery != null,
            routinesSaved = routineStore?.enabled()?.size ?: 0,
            memory2Durable = memory2Store?.durable()?.size ?: 0,
            remoteDevices = remoteDeviceRegistry?.size() ?: 0,
            visionAvailable = vision?.availability() ==
                com.jarvis.core.vision.VisionAvailability.AVAILABLE,
            asrRoute = asr?.id ?: "none",
            ttsRoute = tts?.id ?: "none",
            notes = listOf(
                "roadmap=v2-09",
                "asrMode=${JarvisSettings.asrMode(app)}",
                "asrCloud=${CloudAsrFactory.cloudConfiguredLabel(app)}",
                "ttsMode=${JarvisSettings.ttsMode(app)}",
                "ttsCloud=${CloudTtsFactory.cloudConfiguredLabel(app)}",
            ) + features.lines() +
                com.jarvis.core.runtime.LatencyStatsStore.summaryLines(),
            featureStatuses = features.features.map { it.short() },
        )
    }

    /**
     * V2-01: truthful feature lifecycle (constructed ≠ active_on_live_path).
     */
    fun featureStatusSnapshot(context: android.content.Context): com.jarvis.core.runtime.FeatureStatusSnapshot {
        val app = context.applicationContext
        val cloudOk = JarvisSettings.cloudConfigured(app)
        fun st(
            id: String,
            constructed: Boolean,
            available: Boolean = constructed,
            enabled: Boolean = available,
            active: Boolean = false,
            note: String = "",
        ): com.jarvis.core.runtime.FeatureStatus {
            val life = when {
                !constructed -> com.jarvis.core.runtime.FeatureLifecycle.DISABLED
                active -> com.jarvis.core.runtime.FeatureLifecycle.ACTIVE_ON_LIVE_PATH
                enabled && available -> com.jarvis.core.runtime.FeatureLifecycle.ENABLED
                available -> com.jarvis.core.runtime.FeatureLifecycle.AVAILABLE
                else -> com.jarvis.core.runtime.FeatureLifecycle.CONSTRUCTED
            }
            return com.jarvis.core.runtime.FeatureStatus(id, life, note)
        }
        return com.jarvis.core.runtime.FeatureStatusSnapshot(
            capturedAtMillis = System.currentTimeMillis(),
            features = listOf(
                st("executionOrchestrator", true, active = true, note = "live_skills"),
                st("aiRouter", aiRouter != null, available = aiRouter != null, enabled = true, active = true, note = "chat_path"),
                st("planner", aiPlanner != null, note = "compound_boundary"),
                st(
                    "agentLoop",
                    agentExecutionLoop != null,
                    note = "compound_dry_run",
                ),
                st("verifier", resultVerifier != null, active = true, note = "soft_verify"),
                st("catalogDiscovery", catalogToolDiscovery != null, active = true, note = "log+plan"),
                st("memory2", memory2Store != null, note = "not_on_live_path"),
                st("vision", visionRouter != null, note = "stub_not_live"),
                st("proactiveAgent", proactiveAgent != null, note = "not_companion_ui"),
                st("companionProactive", true, active = true, note = "dashboard_tick"),
                st(
                    "hybridAsr",
                    asrRouter != null,
                    note = "v2-08_${CloudAsrFactory.cloudConfiguredLabel(app)}",
                ),
                st(
                    "hybridTts",
                    ttsRouter != null,
                    note = "v2-09_${CloudTtsFactory.cloudConfiguredLabel(app)}",
                ),
                st("streaming", true, note = "v2_07_real_sse_chat_when_cloud"),
                st("remote", remoteDeviceRegistry != null, note = "empty_registry"),
                st("plugins", pluginRegistry != null, note = "empty"),
                st("mcp", mcpRegistry != null, note = "empty"),
                st("filesAgent", filesAgent != null, note = "planning_only"),
                st("browserAgent", browserAgent != null, note = "planning_only"),
                st("routines", routineStore != null, note = "not_voice_driven"),
                st("cloudLlm", cloudOk, available = cloudOk, enabled = JarvisSettings.preferCloudLlm(app), active = cloudOk, note = "dialogue"),
                st("longTermMemory", memoryInstance != null, active = true),
            ),
        )
    }

    @Volatile
    private var toolExecutor: com.jarvis.core.common.ToolExecutor? = null

    fun build(context: Context): AssistantOrchestrator = lock.withLock {
        cached?.let { return it }

        val ctx = context.applicationContext
        appContext = ctx
        val customRoutines = PrefsCustomRoutines(ctx)
        val toolRegistry = ToolRegistry().apply {
            register(FlashlightTool(ctx))
            register(VolumeTool(ctx))
            register(BrightnessTool(ctx))
            register(TimerTool(ctx))
            register(CalendarTool(ctx))
            register(SmsTool(ctx))
            register(TelegramSendTool(ctx))
            register(LauncherTool(ctx))
            register(CallTool(ctx))
            register(ContactsTool(ctx))
            register(FilesTool(ctx))
            register(SystemSettingTool(ctx))
            register(NotificationTool())
            register(ClipboardTool(ctx))
            register(ScreenshotTool(ctx))
            register(UiClickTool(ctx))
            register(UiTypeTool(ctx))
            register(UiSwipeTool(ctx))
            register(UiGlobalTool(ctx))
            register(UiReadTool(ctx))
            register(UiWaitTool(ctx))
            register(WeatherTool(ctx))
            register(com.jarvis.tools.SystemStatusTool(ctx))
            val reminders = AndroidReminderBook(ctx)
            reminders.rearm()
            register(com.jarvis.tools.ReminderTool(reminders))
            register(com.jarvis.tools.BriefingTool(::find))
            val placeBook = PrefsPlaceBook(ctx)
            register(com.jarvis.tools.navigationTool(ctx, placeBook))
            register(com.jarvis.tools.PlacesTool(placeBook))
            register(com.jarvis.tools.NotesTool(PrefsNoteBook(ctx)))
            register(com.jarvis.tools.appActionTool(ctx))
            register(com.jarvis.brain.CustomRoutineTool(customRoutines))
            register(com.jarvis.tools.webSearchTool(ctx))
            register(com.jarvis.tools.cameraTool(ctx))
            register(com.jarvis.tools.newsTool())
            register(com.jarvis.tools.ratesTool())
            register(com.jarvis.tools.locationTool(ctx))
            register(com.jarvis.tools.PhoneControlTool(ctx))
            register(com.jarvis.tools.AnnounceSettingsTool { kind, on -> JarvisSettings.setAnnounce(ctx, kind, on) })
            register(MusicTool(ctx))
            register(KnowledgeTool())
        }

        val capReg = ToolCapabilityCatalog.buildFromTools(toolRegistry)
        val capDiscovery = CapabilityDiscovery(capReg)
        val baseExecutor = DefaultToolExecutor(toolRegistry)
        val capExecutor = CapabilityExecutor(capReg, baseExecutor)
        val toolExecutor = CapabilityAwareToolExecutor(baseExecutor, capReg)
        capabilityRegistry = capReg
        capabilityDiscovery = capDiscovery
        capabilityExecutor = capExecutor
        this.toolExecutor = toolExecutor

        // Stage 8F/9: catalog registry + bounded discovery (не отдаём весь registry LLM)
        val catalog = com.jarvis.core.execution.CatalogToolRegistry()
        com.jarvis.core.execution.CatalogToolRegistryAdapter
            .importDefinitionsFromCapabilityRegistry(catalog, capReg)
        com.jarvis.core.execution.CatalogToolRegistryAdapter
            .importExecutablesFromToolRegistry(catalog, toolRegistry)
        val toolDiscovery = com.jarvis.core.discovery.CatalogToolDiscovery(catalog)
        catalogToolRegistry = catalog
        catalogToolDiscovery = toolDiscovery

        // Stage 11: AI Planner (creates ActionPlan only; no execution)
        aiPlanner = com.jarvis.core.planner.DeterministicAIPlanner()
        // Stage 12: Agent Execution Loop (dry-run by default; live via SafeAutonomousExecutor)
        agentExecutionLoop = com.jarvis.core.agent.AgentExecutionLoop(
            pipeline = com.jarvis.core.autonomy.AutonomyActionPipeline(),
            toolExecutor = toolExecutor,
            safeExecutor = com.jarvis.core.autonomy.SafeAutonomousExecutor(
                toolExecutor = toolExecutor,
                pipeline = com.jarvis.core.autonomy.AutonomyActionPipeline(),
            ),
        )
        // Stage 13: Result Verification (tool result + observed state)
        resultVerifier = com.jarvis.core.verification.DefaultResultVerifier()
        // Stage 15/16: Plugin + MCP registries (empty stubs — no network / no concrete plugins)
        pluginRegistry = com.jarvis.core.plugin.PluginRegistry()
        mcpRegistry = com.jarvis.core.mcp.McpRegistry()
        // Stage 17: Files Agent (plans file ops through ActionPlan + Risk/Policy)
        filesAgent = com.jarvis.core.filesagent.FilesAgent()
        // Stage 18: Browser Agent (ActionPlan pipeline; no network client yet)
        browserAgent = com.jarvis.core.browseragent.BrowserAgent()
        // Stages 19–27 wiring
        routineStore = com.jarvis.core.routines.RoutineStore()
        routineGenerator = com.jarvis.core.routines.RoutineGenerator()
        memory2Store = com.jarvis.core.memory.Memory2Store()
        asrRouter = com.jarvis.core.voice.AsrRouter(
            local = com.jarvis.core.voice.LocalAsrStub(ready = true),
            cloud = object : com.jarvis.core.voice.AsrProvider {
                override val id = CloudAsrFactory.cloudBackend(ctx).id
                override val kind = com.jarvis.core.voice.VoiceProviderKind.CLOUD
                override fun availability() = CloudAsrFactory.cloudBackend(ctx).availability()
                override fun transcribe(pcm16le: ByteArray, sampleRateHz: Int): String? = null
            },
        )
        ttsRouter = com.jarvis.core.voice.TtsRouter(
            local = com.jarvis.core.voice.LocalTtsStub(ready = true),
            cloud = object : com.jarvis.core.voice.TtsProvider {
                override val id = CloudTtsFactory.cloudBackend(ctx).id
                override val kind = com.jarvis.core.voice.VoiceProviderKind.CLOUD
                override fun availability() = CloudTtsFactory.cloudBackend(ctx).availability()
                override fun synthesize(text: String): ByteArray? = null
            },
        )
        visionRouter = com.jarvis.core.vision.VisionRouter(
            local = com.jarvis.core.vision.LocalVisionStub(),
            cloud = com.jarvis.core.vision.CloudVisionStub(configured = false),
        )
        proactiveAgent = com.jarvis.core.agent.ProactiveAgent(
            loop = agentExecutionLoop ?: com.jarvis.core.agent.AgentExecutionLoop(),
        )
        remoteDeviceRegistry = com.jarvis.core.remote.RemoteDeviceRegistry()
        android.util.Log.i(
            "Jarvis.Planner",
            "aiPlanner=deterministic discovery=on agentLoop=on verifier=on " +
                "nativeTools=${com.jarvis.core.nativeandroid.NativeAndroidToolCatalog.size()} " +
                "liveSafe=${com.jarvis.core.nativeandroid.NativeAndroidToolCatalog.liveDeviceSafeIds().size} " +
                "liveRead=${com.jarvis.core.nativeandroid.NativeAndroidToolCatalog.liveReadOnlyIds().size} " +
                "plugins=${pluginRegistry?.size() ?: 0} mcp=${mcpRegistry?.size() ?: 0} " +
                "filesAgent=on browserAgent=on " +
                "routines=on memory2=on voiceHybrid=on vision=on proactiveAgent=on remote=on",
        )

        // Stage 10/10.5: AI Provider + Router (Local + NaraRouter cloud)
        val aiReg = com.jarvis.brain.aiprovider.AIProviderRegistry()
        val cloudClient = com.jarvis.brain.aiprovider.OpenAiCompatibleCloudClient(
            baseUrlProvider = { JarvisSettings.cloudBaseUrl(ctx) },
            apiKeyProvider = { JarvisSettings.cloudApiKey(ctx) },
        )
        // 9б-2b: ChainCloudProvider поверх цепочки (legacy = CloudLLMProvider как fallback; не регистрируем CloudLLMProvider напрямую)
        val keyStore = SecureKeyStore(ctx)
        toolRegistry.register(
            com.jarvis.tools.WebAnswerTool(ask = { prompt ->
                chainCloudProvider?.complete(
                    com.jarvis.brain.aiprovider.AICompletionRequest(
                        requestId = java.util.UUID.randomUUID().toString(),
                        userText = prompt,
                        taskType = com.jarvis.brain.aiprovider.AiTaskType.GENERAL,
                    ),
                )?.text
            }),
        )
        val chainProvider = com.jarvis.brain.cloud.ChainCloudProvider(
            legacy = com.jarvis.brain.aiprovider.CloudLLMProvider(
                client = cloudClient,
                modelProvider = { JarvisSettings.cloudModelForProfile(ctx) },
            ),
            router = com.jarvis.core.cloud.ChainRouter(clock = { System.currentTimeMillis() }),
            health = com.jarvis.core.cloud.ChainHealth(clock = { System.currentTimeMillis() }),
            keyFor = { keyStore.getKey(it) },
            providersWithKeys = { keyStore.providersWithKeys() },
        )
        chainCloudProvider = chainProvider
        aiReg.register(chainProvider)
        chatLlmOrNull(ctx)?.let { engine ->
            aiReg.register(com.jarvis.brain.aiprovider.LocalLLMProvider(engine))
        }
        aiProviderRegistry = aiReg
        aiRouter = com.jarvis.brain.aiprovider.AIRouter(
            registry = aiReg,
            cloudFastModel = { com.jarvis.brain.aiprovider.NaraRouterDefaults.MODEL_FAST },
            cloudQualityModel = { com.jarvis.brain.aiprovider.NaraRouterDefaults.MODEL_QUALITY },
            localModelId = {
                (chatLlmOrNull(ctx) as? com.jarvis.brain.aiprovider.ModelNamed)?.modelId
                    ?: "local.gguf"
            },
        )
        android.util.Log.i(
            "Jarvis.AIProvider",
            "providers=${aiReg.size()} local=${aiReg.localProviders().size} " +
                "cloud=${aiReg.cloudProviders().size} available=${aiReg.available().size} " +
                "router=on cloudKey=${if (JarvisSettings.cloudConfigured(ctx)) "set" else "missing"} " +
                "preferCloud=${JarvisSettings.preferCloudLlm(ctx)}",
        )

        android.util.Log.i(
            "Jarvis.Capability",
            "registry ready caps=${capReg.size()} tools=${toolRegistry.available().size} " +
                "catalog=${catalog.size()} discovery=on",
        )

        // Stage 8F companion safe path (no auto-exec from proactive tick)
        com.jarvis.app.companion.CompanionProactive.attachSafeExecutor(toolExecutor, live = false)

        val manifestRegistry = SkillManifestRegistry()
        SkillManifestLoader(ctx).loadAll().forEach { manifestRegistry.register(it) }
        val retriever = SkillRetriever(manifestRegistry, k = 5)

        val memory = LongTermMemory(ctx)
        toolRegistry.register(com.jarvis.brain.ForgetFactTool { memory })
        memoryInstance = memory

        val brainRouter = buildBrainRouter(ctx)

        val conversationContext = ConversationContext().also { seedDialogueHistory(it) }
        dialogueContext = conversationContext

        val orchestrator = AssistantOrchestrator(
            brainRouter = brainRouter,
            skillRetriever = retriever,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            toolPolicy = RiskBasedPolicy(),
            context = conversationContext,
            longTermMemory = memory,
            dialogue = DialogueResponder(
                ctx,
                JarvisSettings.persona(ctx),
                chatLlm = {
                    com.jarvis.brain.aiprovider.RoutedChatLlmEngine(
                        localProvider = { chatLlmOrNull(ctx) },
                        routerProvider = { aiRouter() },
                        isOnline = { CloudAsrFactory.isOnline(ctx) },
                        preferCloud = {
                            JarvisSettings.preferCloudLlm(ctx) && JarvisSettings.cloudConfigured(ctx)
                        },
                        complexity = {
                            when (JarvisSettings.chatProfile(ctx)) {
                                "QUALITY" -> com.jarvis.brain.aiprovider.AIRequestComplexity.COMPLEX
                                else -> com.jarvis.brain.aiprovider.AIRequestComplexity.SIMPLE
                            }
                        },
                    )
                },
            ),
            executeThreshold = JarvisSettings.brainConfidenceThreshold(ctx),
            clarifyThreshold = 0.4f,
            memoryEnabled = JarvisSettings.memoryEnabled(ctx),
            memoryProvider = { query ->
                runCatching { memory.retrieve(query).joinToString("; ") { it.text } }
                    .getOrDefault("")
            },
            resolveContacts = { query ->
                ContactResolver.resolveAll(ctx, query).map { PendingAction.Choice(it.name, it.number) }
            },
            capabilityDiscovery = capDiscovery,
            executionOrchestrator = com.jarvis.core.runtime.ExecutionOrchestrator(
                toolExecutor = toolExecutor,
                pipeline = com.jarvis.core.autonomy.AutonomyActionPipeline(),
                verifier = resultVerifier ?: com.jarvis.core.verification.DefaultResultVerifier(),
                // core не знает про Android: настройка приходит лямбдой.
                confirmLevelRequired = { JarvisSettings.confirmSensitive(ctx) },
            ),
            planPathCoordinator = com.jarvis.core.runtime.PlanPathCoordinator(
                planner = aiPlanner ?: com.jarvis.core.planner.DeterministicAIPlanner(),
                agentLoop = agentExecutionLoop ?: com.jarvis.core.agent.AgentExecutionLoop(),
            ),
            catalogToolDiscovery = toolDiscovery,
            llmContextAssembly = com.jarvis.brain.llm.LlmContextAssembly(
                longTermMemory = memory,
                companionRecall = runCatching {
                    com.jarvis.app.companion.CompanionMemory.get(ctx).recall
                }.getOrNull(),
                memory2 = memory2Store(),
                toolDiscovery = toolDiscovery,
                phoneContext = { com.jarvis.core.companion.PhoneContextBus.current() },
            ),
            chatPersona = { JarvisSettings.persona(ctx) },
            chatProfile = { JarvisSettings.chatProfile(ctx) },
            cloudMemoryAllowed = {
                val online = runCatching { CloudAsrFactory.isOnline(ctx) }.getOrDefault(false)
                !(online && JarvisSettings.preferCloudLlm(ctx) && JarvisSettings.cloudConfigured(ctx))
            },
            fastSemantic = com.jarvis.core.runtime.semantic.FastSemanticClassifier(),
            llmSemantic = com.jarvis.brain.semantic.LlmStructuredSemanticInterpreter(
                chatLlm = { chatLlmOrNull(ctx) },
                routerProvider = { aiRouter() },
                isOnline = { CloudAsrFactory.isOnline(ctx) },
                preferCloud = {
                    // Structured semantic prefers local; cloud only if explicitly preferred AND no local
                    false
                },
            ),
            chatStreamPipeline = aiRouter()?.let { com.jarvis.brain.aiprovider.ChatStreamPipeline(it) },
            isOnlineForAi = { CloudAsrFactory.isOnline(ctx) },
            preferCloudForAi = {
                JarvisSettings.preferCloudLlm(ctx) && JarvisSettings.cloudConfigured(ctx)
            },
            confirmationStyle = com.jarvis.core.nlp.JarvisStyle::confirmation,
            customRoutine = customRoutines::steps,
        )
        cached = orchestrator
        orchestrator
    }

    fun warmLlmBrain(context: Context) {
        val app = context.applicationContext
        build(app)
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
            // Не ставим setProcessingBusy — иначе KWS/ASR молчат на время прогрева.
            android.util.Log.i("Jarvis.ChatLlm", "warm start (chat only, tool lazy)")
            (chatLlmOrNull(app) as? LlamaChatLlmEngine)?.warm()
        }, "llm-warm").start()
    }

    fun warmChatLlm(context: Context) {
        val app = context.applicationContext
        build(app)
        if (chatModelFile(app) == null) return
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
            android.util.Log.i("Jarvis.ChatLlm", "warm start (priority)")
            (chatLlmOrNull(app) as? LlamaChatLlmEngine)?.warm()
        }, "chat-llm-warm").start()
    }

    /**
     * Ранняя предзагрузка весов в RAM (Application / Dashboard).
     * Без generate — только loadModel, чтобы UI не ждал warm-up ping.
     */
    fun preloadChatModel(context: Context) {
        val app = context.applicationContext
        if (!JarvisSettings.chatPreloadEnabled(app)) return
        if (chatModelFile(app) == null) return
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            android.util.Log.i("Jarvis.ChatLlm", "eager preload…")
            runCatching {
                (chatLlmOrNull(app) as? LlamaChatLlmEngine)?.preload()
            }.onFailure { e ->
                android.util.Log.w("Jarvis.ChatLlm", "eager preload failed: ${e.message}")
            }
        }, "chat-preload").start()
    }

    /**
     * Холодная загрузка недавнего разговора: последние 20 реплик не старше 48 часов
     * из conversation_messages. Дедуп/«повтори» работают как раньше — лимит
     * ConversationContext (20) не меняется.
     */
    private fun seedDialogueHistory(context: ConversationContext) {
        if (!com.jarvis.core.storage.ConversationJournal.isInstalled()) return
        val startedAt = System.currentTimeMillis()
        runCatching {
            val entries = com.jarvis.core.storage.ConversationJournal.recent(
                limit = com.jarvis.core.storage.RetentionPolicy.CONTEXT_RECENT_LIMIT,
                maxAgeMs = com.jarvis.core.storage.RetentionPolicy.RECENT_WINDOW_MS,
            )
            entries.forEach { e ->
                if (e.role == com.jarvis.core.storage.JarvisSchema.ROLE_USER) {
                    context.addUser(e.text)
                } else {
                    context.addAssistant(e.text)
                }
            }
            android.util.Log.i(
                "Jarvis.ChatHistory",
                "context restored n=${entries.size} in ${System.currentTimeMillis() - startedAt}ms",
            )
        }
    }

    /** «Стереть историю чата» на экране «Память»: чистим и RAM-контекст. */
    fun clearDialogueHistory() {
        dialogueContext?.clear()
    }

    fun memory(context: Context): LongTermMemory {
        lock.withLock {
            return memoryInstance ?: LongTermMemory(context.applicationContext).also { memoryInstance = it }
        }
    }

    fun shutdownLlmBrain() {
        llmBrain?.shutdown()
        llmBrain = null
    }

    fun shutdownChatLlm() {
        chatLlm?.unload()
        chatLlm = null
    }

    /** Сброс оркестратора (после смены tool-модели / настроек). */
    fun invalidate() {
        lock.withLock {
            cached = null
            capabilityRegistry = null
            capabilityDiscovery = null
            capabilityExecutor = null
            catalogToolRegistry = null
            catalogToolDiscovery = null
            aiProviderRegistry = null
            chainCloudProvider = null
            aiRouter = null
            aiPlanner = null
            agentExecutionLoop = null
            resultVerifier = null
            pluginRegistry = null
            mcpRegistry = null
            filesAgent = null
            browserAgent = null
            routineStore = null
            routineGenerator = null
            memory2Store = null
            asrRouter = null
            ttsRouter = null
            visionRouter = null
            proactiveAgent = null
            remoteDeviceRegistry = null
            toolExecutor = null
        }
    }

    fun invalidateChatLlm() {
        shutdownChatLlm()
    }

    /** Вызывается из DashboardActivity после сохранения/удаления облачного ключа: сбрасывает health записи и кэш ключей. */
    fun onCloudKeyChanged(providerId: String) {
        chainCloudProvider?.onKeyChanged(providerId)
    }

    fun chatLlmOrNull(context: Context): ChatLlmEngine? {
        val app = context.applicationContext
        val file = chatModelFile(app) ?: return null
        val existing = chatLlm
        if (existing != null && existing.isReady() && existing.modelPath == file.absolutePath) {
            return existing
        }
        return lock.withLock {
            val again = chatLlm
            if (again != null && again.isReady() && again.modelPath == file.absolutePath) {
                return again
            }
            again?.unload()
            val engine = LlamaChatLlmEngine(
                modelFile = file,
                profileProvider = { JarvisSettings.chatProfile(app) },
            )
            chatLlm = engine
            // Refresh Stage 10 local provider binding when chat model changes
            aiProviderRegistry?.let { reg ->
                reg.unregister(com.jarvis.brain.aiprovider.LocalLLMProvider.ID)
                reg.register(com.jarvis.brain.aiprovider.LocalLLMProvider(engine))
            }
            android.util.Log.i(
                "Jarvis.ChatLlm",
                "bound chat model=${file.name} profile=${JarvisSettings.chatProfile(app)}",
            )
            engine
        }
    }

    fun chatModelFile(context: Context): File? {
        val root = context.getExternalFilesDir(null) ?: return null
        val preferred = JarvisSettings.prefs(context).getString("active_chat_model", null)
        val order = buildList {
            if (!preferred.isNullOrBlank()) add(preferred)
            addAll(ModelCatalog.CHAT_MODEL_CANDIDATES)
        }.distinct()
        for (rel in order) {
            val f = File(root, "models/$rel")
            if (f.isFile && f.length() > 50_000_000L) return f
        }
        return null
    }

    private fun buildBrainRouter(context: Context): com.jarvis.brain.BrainRouter {
        val cloudAvailable = {
            CloudAsrFactory.isOnline(context) && JarvisSettings.preferCloudLlm(context) &&
                JarvisSettings.cloudConfigured(context)
        }
        val ruleBased = RuleBasedBrain(cloudAvailable)
        val llmEnabled = JarvisSettings.llmEnabled(context)
        val modelFile = getLlmModelPath(context)
        android.util.Log.d(
            "Jarvis.LlmBrain",
            "buildBrainRouter llmEnabled=$llmEnabled model=${modelFile?.absolutePath} isFile=${modelFile?.isFile}",
        )
        if (!llmEnabled || modelFile == null || !modelFile.isFile) {
            return ruleBased
        }
        val brain = LlmBrain(
            modelPath = modelFile.absolutePath,
            fallback = ruleBased,
            cacheDir = context.cacheDir.absolutePath,
            cloudAvailable = cloudAvailable,
        )
        llmBrain = brain
        return brain
    }

    private fun getLlmModelPath(context: Context): File? {
        val externalDir = File(context.getExternalFilesDir(null), "models/llm")
        val externalModel = File(externalDir, LLM_MODEL_NAME)
        if (externalModel.isFile) return externalModel
        val internalDir = File(context.filesDir, "models/llm")
        val internalModel = File(internalDir, LLM_MODEL_NAME)
        if (internalModel.isFile) return internalModel
        return null
    }

    private const val LLM_MODEL_NAME = "brain.litertlm"
}
