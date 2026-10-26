package com.jarvis.core.runtime

import com.jarvis.core.voice.TtsTelemetry

fun RequestTrace.applyTts(t: TtsTelemetry) {
    ttsProvider = t.ttsProvider
    ttsModel = t.ttsModel
    ttsVoice = t.ttsVoice
    ttsRoute = t.ttsRoute
    ttsRouteReason = t.ttsRouteReason
    ttsFallback = t.ttsFallback
    ttsStreaming = t.ttsStreaming
    ttsSessionId = t.ttsSessionId
    ttsSetupMs = t.ttsSetupMs
    ttsStartupMs = t.ttsSetupMs
    ttsFirstAudioMs = t.ttsFirstAudioMs
    ttsFirstPlaybackMs = t.ttsFirstPlaybackMs
    ttsSynthesisMs = t.ttsSynthesisMs
    ttsPlaybackMs = t.ttsPlaybackMs
    ttsTotalMs = t.ttsTotalMs
    ttsQueueDepth = t.ttsQueueDepth
    ttsCancelled = t.ttsCancelled
    ttsErrorCategory = t.ttsErrorCategory
    if (t.ttsFallback) fallback = true
}
