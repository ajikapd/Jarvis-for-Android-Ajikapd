package com.jarvis.core.roadmaprest

import com.jarvis.core.agent.AgentExecutionLoop
import com.jarvis.core.agent.ProactiveAgent
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.diagnostics.DiagnosticsFormatter
import com.jarvis.core.diagnostics.SystemDiagnosticsSnapshot
import com.jarvis.core.discovery.DiscoveredItem
import com.jarvis.core.discovery.DiscoveryResult
import com.jarvis.core.execution.ToolAvailability
import com.jarvis.core.execution.ToolExecutionLocality
import com.jarvis.core.execution.ToolProviderSource
import com.jarvis.core.memory.Memory2Durability
import com.jarvis.core.memory.Memory2Kind
import com.jarvis.core.memory.Memory2Record
import com.jarvis.core.memory.Memory2Store
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.planner.DeterministicAIPlanner
import com.jarvis.core.planner.PlannerContext
import com.jarvis.core.planner.UserGoal
import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import com.jarvis.core.proactive.ProactiveReasonCode
import com.jarvis.core.remote.RemoteDevice
import com.jarvis.core.remote.RemoteDeviceKind
import com.jarvis.core.remote.RemoteDeviceRegistry
import com.jarvis.core.remote.RemoteDeviceStatus
import com.jarvis.core.remote.RemoteToolBridge
import com.jarvis.core.remote.RemoteToolRef
import com.jarvis.core.routines.RoutineGenerator
import com.jarvis.core.routines.RoutineStatus
import com.jarvis.core.routines.RoutineStore
import com.jarvis.core.streaming.StreamEvent
import com.jarvis.core.streaming.StreamEventType
import com.jarvis.core.streaming.StreamPhase
import com.jarvis.core.streaming.StreamSession
import com.jarvis.core.streaming.StreamSessionConfig
import com.jarvis.core.vision.CloudVisionStub
import com.jarvis.core.vision.LocalVisionStub
import com.jarvis.core.vision.VisionAvailability
import com.jarvis.core.vision.VisionRequest
import com.jarvis.core.vision.VisionRouter
import com.jarvis.core.vision.VisionStatus
import com.jarvis.core.voice.AsrRouter
import com.jarvis.core.voice.CloudAsrStub
import com.jarvis.core.voice.CloudTtsStub
import com.jarvis.core.voice.LocalAsrStub
import com.jarvis.core.voice.LocalTtsStub
import com.jarvis.core.voice.TtsRouter
import com.jarvis.core.voice.VoiceRouteContext
import com.jarvis.core.workflow.WorkflowConverter
import com.jarvis.core.workflow.WorkflowNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stages 19–27 unit coverage (roadmap rest).
 */
class RoadmapStages19to27Test {

    private fun discoveryFlashlight() = DiscoveryResult(
        query = "фонарик",
        items = listOf(
            DiscoveredItem(
                toolId = "flashlight",
                capabilityId = "DEVICE_LIGHT",
                name = "Flashlight",
                description = "torch",
                score = 1f,
                riskLevel = RiskLevel.SAFE,
                requiredPermissions = emptyList(),
                availability = ToolAvailability.ALWAYS,
                source = ToolProviderSource.NATIVE,
                locality = ToolExecutionLocality.LOCAL,
                hasExecutable = true,
                parameterNames = listOf("action"),
            ),
            DiscoveredItem(
                toolId = "volume",
                capabilityId = "DEVICE_AUDIO",
                name = "Volume",
                description = "vol",
                score = 0.8f,
                riskLevel = RiskLevel.SAFE,
                requiredPermissions = emptyList(),
                availability = ToolAvailability.ALWAYS,
                source = ToolProviderSource.NATIVE,
                locality = ToolExecutionLocality.LOCAL,
                hasExecutable = true,
                parameterNames = listOf("action"),
            ),
        ),
        truncated = false,
        considered = 2,
    )

    @Test
    fun stage19_generateApproveSaveRoutine() {
        val gen = RoutineGenerator()
        val draft = gen.generate("включи фонарик и сделай громче", discoveryFlashlight())
        assertTrue(draft.previewSummary.isNotBlank())
        assertTrue(draft.validationOk)
        val saved = gen.approve(draft, title = "Torch+Vol")!!
        assertEquals(RoutineStatus.SAVED, saved.status)
        val store = RoutineStore()
        store.save(saved)
        assertEquals(1, store.enabled().size)
    }

    @Test
    fun stage20_workflowRoundTrip() {
        val plan = DeterministicAIPlanner().plan(
            UserGoal("g", "фонарик"),
            PlannerContext(discovery = discoveryFlashlight()),
        )
        val graph = WorkflowConverter.fromActionPlan(plan, "Torch")
        assertTrue(graph.nodes.any { it.type == WorkflowNodeType.TRIGGER })
        assertTrue(graph.nodes.any { it.type == WorkflowNodeType.ACTION })
        val back = WorkflowConverter.toActionPlan(graph)
        assertTrue(back.steps.isNotEmpty())
    }

    @Test
    fun stage21_memory2DoesNotDurableContext() {
        val store = Memory2Store()
        val ctx = store.put(
            Memory2Record(
                id = "1",
                kind = Memory2Kind.CONTEXT,
                text = "screen on",
                durability = Memory2Durability.DURABLE,
                createdAtMillis = 1L,
            ),
        )
        assertEquals(Memory2Durability.SESSION, ctx.durability)
        val pref = store.put(
            Memory2Record(
                id = "2",
                kind = Memory2Kind.PREFERENCE,
                text = "likes jazz",
                durability = Memory2Durability.DURABLE,
                createdAtMillis = 1L,
            ),
        )
        assertEquals(Memory2Durability.DURABLE, pref.durability)
        assertEquals(1, store.durable().size)
    }

    @Test
    fun stage22_voiceRoutersFallbackLocal() {
        val asr = AsrRouter(LocalAsrStub(), CloudAsrStub(configured = false))
        val tts = TtsRouter(LocalTtsStub(), CloudTtsStub(configured = false))
        val ctx = VoiceRouteContext(isOnline = true, preferCloud = true)
        assertEquals("asr.local", asr.select(ctx).id)
        assertEquals("tts.local", tts.select(ctx).id)
        val asr2 = AsrRouter(LocalAsrStub(), CloudAsrStub(configured = true))
        assertEquals("asr.cloud", asr2.select(ctx).id)
    }

    @Test
    fun stage23_streamBargeInCancels() {
        val s = StreamSession("s1", StreamSessionConfig(allowBargeIn = true), startedAtMillis = 0)
        s.emit(StreamEvent(StreamEventType.PARTIAL_ASR, StreamPhase.ASR, "при", 1))
        s.emit(StreamEvent(StreamEventType.BARGE_IN, StreamPhase.ASR, atMillis = 2))
        assertTrue(s.bargedIn)
        assertTrue(s.cancellation.isCancelled())
    }

    @Test
    fun stage23_timeoutAndFallback() {
        val s = StreamSession("s2", StreamSessionConfig(timeoutMs = 100), startedAtMillis = 0)
        assertTrue(s.timedOut(200))
        s.markTimeout(200)
        s.markFallback("cloud_down", 201)
        assertTrue(s.events().any { it.type == StreamEventType.TIMEOUT })
        assertTrue(s.events().any { it.type == StreamEventType.FALLBACK })
    }

    @Test
    fun stage24_visionRouter() {
        val router = VisionRouter(LocalVisionStub(), CloudVisionStub(configured = false))
        val p = router.select(isOnline = true, preferCloud = true)
        assertEquals(VisionAvailability.AVAILABLE, p.availability())
        val r = p.analyze(VisionRequest("v1", ByteArray(4) { 1 }, "что на экране"))
        assertEquals(VisionStatus.SUCCESS, r.status)
    }

    @Test
    fun stage25_proactiveSuggestDryRun() {
        val agent = ProactiveAgent()
        val decision = ProactiveDecision(
            type = ProactiveDecisionType.SUGGESTION,
            behaviorPatternKey = "flashlight",
            reasonCode = ProactiveReasonCode.SELECTED,
            reason = "headphones",
            confidence = 0.9f,
            relevance = 0.9f,
        )
        val sug = agent.suggest(decision, discoveryFlashlight())
        assertTrue(sug.allowedToPropose)
        assertNotNull(sug.plan)
        val dry = agent.dryRun(sug, nowMillis = 1_000_000L)
        assertNotNull(dry)
    }

    @Test
    fun stage26_remoteToolsInRegistry() {
        val reg = RemoteDeviceRegistry()
        reg.registerDevice(
            RemoteDevice("pc1", RemoteDeviceKind.PC, "Desk PC", RemoteDeviceStatus.ONLINE),
        )
        val def = ToolDefinition(
            id = "open_url",
            name = "Open URL",
            description = "open browser",
            riskLevel = RiskLevel.LOW,
            onlineRequirement = OnlineRequirement.ONLINE_REQUIRED,
        )
        reg.registerTool(RemoteToolRef("pc1", "open_url", def))
        val bridged = RemoteToolBridge.toDefinition(reg.allTools().single())
        assertTrue(bridged.id.startsWith("remote.pc1."))
        assertEquals(1, reg.onlineDevices().size)
    }

    @Test
    fun stage27_diagnosticsRender() {
        val snap = SystemDiagnosticsSnapshot(
            capturedAtMillis = 1L,
            agentLoopPresent = true,
            plannerPresent = true,
            aiRouterPresent = true,
            cloudKeyConfigured = true,
            preferCloud = true,
            providersLocal = 1,
            providersCloud = 1,
            providersAvailable = 2,
            catalogSize = 24,
            discoveryOn = true,
            routinesSaved = 0,
            memory2Durable = 1,
            remoteDevices = 0,
            visionAvailable = true,
            asrRoute = "asr.local",
            ttsRoute = "tts.local",
        )
        val text = DiagnosticsFormatter.render(snap)
        assertTrue(text.contains("JARVIS DIAGNOSTICS"))
        assertTrue(text.contains("cloudKey=true"))
    }
}
