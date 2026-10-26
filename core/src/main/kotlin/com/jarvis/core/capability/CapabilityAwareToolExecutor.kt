package com.jarvis.core.capability

import android.util.Log
import com.jarvis.core.common.ToolExecutor
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolResult

/**
 * Декоратор ToolExecutor: выполняет tool как раньше, затем верифицирует
 * через Capability-слой и помечает результат (capabilityId / verified).
 */
class CapabilityAwareToolExecutor(
    private val inner: ToolExecutor,
    private val capabilityRegistry: CapabilityRegistry,
    private val verifier: CapabilityVerifier = DefaultCapabilityVerifier(),
) : ToolExecutor {

    override val registry: ToolRegistry get() = inner.registry

    override fun execute(call: ToolCall): ToolResult {
        val result = inner.execute(call)
        val cap = capabilityRegistry.findByToolId(call.toolId) ?: return result
        val outcome = verifier.verify(
            capability = cap,
            call = CapabilityCall(
                requestId = call.requestId,
                capabilityId = cap.id,
                arguments = call.arguments,
            ),
            toolResult = result,
        )
        runCatching {
            Log.i(
                TAG,
                "cap=${cap.id} tool=${call.toolId} status=${result.status} " +
                    "verified=${outcome.ok} notes=${outcome.notes.take(3)}",
            )
        }
        return result.copy(
            data = result.data + mapOf(
                "capabilityId" to cap.id,
                "verified" to outcome.ok,
                "verificationNotes" to outcome.notes.joinToString("|"),
            ),
        )
    }

    companion object {
        private const val TAG = "Jarvis.Capability"
    }
}
