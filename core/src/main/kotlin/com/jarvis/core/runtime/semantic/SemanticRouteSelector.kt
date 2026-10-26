package com.jarvis.core.runtime.semantic

import com.jarvis.core.runtime.RouteDecision

/**
 * V2-02: выбор RouteDecision по semantic understanding.
 * Confidence — один из факторов, не единственный.
 */
object SemanticRouteSelector {

    fun select(u: SemanticUnderstanding): RouteDecision {
        // Targeted clarify when ambiguity is material
        if (u.ambiguity == AmbiguityLevel.HIGH ||
            (u.ambiguity == AmbiguityLevel.MEDIUM && u.clarifyQuestion != null)
        ) {
            return RouteDecision.CLARIFY
        }
        if (u.clarity == SemanticClarity.UNCLEAR && u.suggestedRoute != RouteDecision.CHAT) {
            return RouteDecision.CLARIFY
        }

        // Prefer suggested when clear enough
        when (u.suggestedRoute) {
            RouteDecision.TEMPLATE -> {
                if (u.clarity == SemanticClarity.CLEAR && u.confidence >= 0.85f) {
                    return RouteDecision.TEMPLATE
                }
                // Weak template → chat (avoid stealing conversation)
                return RouteDecision.CHAT
            }
            RouteDecision.FAST_SKILL -> {
                if (u.clarity == SemanticClarity.CLEAR &&
                    u.ambiguity <= AmbiguityLevel.LOW &&
                    u.confidence >= 0.55f &&
                    u.requiredCapabilities.isNotEmpty()
                ) {
                    return RouteDecision.FAST_SKILL
                }
                if (u.confidence >= 0.4f && u.rewrittenCommand != null) {
                    return RouteDecision.FAST_SKILL
                }
                return RouteDecision.CLARIFY
            }
            RouteDecision.PLAN -> {
                if (u.requiredCapabilities.size >= 2 ||
                    u.constraints.isNotEmpty() ||
                    u.notes.any { it.startsWith("steps=") }
                ) {
                    return RouteDecision.PLAN
                }
                // Single-capability "plan-ish" → try fast skill rewrite
                if (u.rewrittenCommand != null) return RouteDecision.FAST_SKILL
                return RouteDecision.PLAN
            }
            RouteDecision.CHAT -> return RouteDecision.CHAT
            RouteDecision.CLARIFY -> return RouteDecision.CLARIFY
            RouteDecision.UNKNOWN -> {
                return when {
                    u.confidence < 0.35f -> RouteDecision.CLARIFY
                    else -> RouteDecision.CHAT
                }
            }
        }
    }
}
