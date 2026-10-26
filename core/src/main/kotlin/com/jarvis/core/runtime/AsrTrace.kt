package com.jarvis.core.runtime

import com.jarvis.core.voice.AsrTelemetry

fun RequestTrace.applyAsr(t: AsrTelemetry) {
    asrProvider = t.asrProvider
    asrModel = t.asrModel
    asrRoute = t.asrRoute
    asrRouteReason = t.asrRouteReason
    asrSetupMs = t.asrSetupMs
    asrFirstPartialMs = t.asrFirstPartialMs
    asrFinalizeMs = t.asrFinalizeMs
    asrTotalMs = t.asrTotalMs
    asrFallback = t.asrFallback
    asrFallbackReason = t.asrFallbackReason
    asrMs = t.asrTotalMs ?: t.asrFinalizeMs
    interactionLatency?.let { lat ->
        lat.asrStartAt = t.asrSessionStartAt
        lat.asrPartialAt = t.asrFirstPartialAt
        lat.asrFinalAt = t.asrFinalAt
        if (t.asrFallback) lat.fallback = true
    }
}
