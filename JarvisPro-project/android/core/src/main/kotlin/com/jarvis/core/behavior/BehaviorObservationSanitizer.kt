package com.jarvis.core.behavior

import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity

/**
 * Stage 6.5: privacy + evidence hygiene перед Detection.
 * Не полный дамп телефона — только нормализованные события.
 */
object BehaviorObservationSanitizer {

    private val SENSITIVE_PACKAGES = setOf(
        "com.google.android.apps.authenticator2",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.viber.voip",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.android.contacts",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.settings", // battery/settings OK for context patterns; keep
    )

    /** Пакеты, для которых не строим FREQUENCY/TEMPORAL suggestions (шум). */
    val NOISY_PACKAGES = setOf(
        "com.android.systemui",
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.lbe.security.miui",
        "com.jarvis.assistant",
    )

    fun sanitizeForLearning(observations: List<PhoneObservation>): List<PhoneObservation> {
        return observations.mapNotNull { o ->
            if (o.sensitivity == Sensitivity.SECRET) return@mapNotNull null
            // Уведомления: только pkg, без текста
            if (o.type == ObservationType.NOTIFICATION_POSTED ||
                o.type == ObservationType.NOTIFICATION_REMOVED
            ) {
                return@mapNotNull o.copy(
                    summary = o.packageName?.let { "notif:$it" },
                    entities = o.entities.filterKeys { it == "package" || it == "ongoing" },
                    sensitivity = Sensitivity.PRIVATE,
                )
            }
            // Не тащим длинный content hint
            if (o.type == ObservationType.WINDOW_CHANGED && o.entities["kind"] == "content") {
                return@mapNotNull o.copy(
                    summary = "window:${o.packageName}",
                    screenId = null,
                    entities = mapOf(
                        "package" to (o.packageName ?: ""),
                        "kind" to "content",
                    ),
                )
            }
            o
        }
    }

    /**
     * Dedup near-identical deliveries (same type/pkg within bucket).
     * Не даёт duplicate bus events раздувать confidence.
     */
    fun dedupeEvidence(
        observations: List<PhoneObservation>,
        bucketMs: Long = 90_000L,
    ): List<PhoneObservation> {
        if (observations.isEmpty()) return emptyList()
        val sorted = observations.sortedBy { it.timestampMillis }
        val out = ArrayList<PhoneObservation>(sorted.size)
        val seenIds = HashSet<String>()
        for (o in sorted) {
            if (!seenIds.add(o.id)) continue
            val prev = out.lastOrNull()
            if (prev != null &&
                prev.type == o.type &&
                prev.packageName == o.packageName &&
                o.timestampMillis - prev.timestampMillis < bucketMs
            ) {
                continue
            }
            out += o
        }
        return out
    }

    fun isNoisyPackage(pkg: String?): Boolean =
        pkg != null && (pkg in NOISY_PACKAGES || pkg.startsWith("com.android.systemui"))
}
