package com.jarvis.app.proactive

import android.content.Context
import android.util.Log
import com.jarvis.app.companion.CompanionBehavior
import com.jarvis.app.companion.CompanionProactive
import com.jarvis.core.behavior.BehaviorFeedback
import com.jarvis.core.proactive.DeliveryDecider
import com.jarvis.core.proactive.DeliveryDecision
import com.jarvis.core.proactive.DeliveryMode
import com.jarvis.core.proactive.DeliveryReason
import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import com.jarvis.core.proactive.ProactiveText
import com.jarvis.core.proactive.ProactiveTextBuilder
import java.util.concurrent.atomic.AtomicLong

/**
 * Б3: единая точка доставки проактивного предложения.
 *
 * ВАЖНО: этот класс сознательно не вызывается ни из таймеров, ни из
 * CompanionProactive.tick. Доставку запускает только debug-хук (Б5) —
 * реальное подключение будет отдельной задачей.
 */
object ProactiveDelivery {

    private const val TAG = "Jarvis.ProactiveDelivery"
    private const val SNOOZE_MS = 60 * 60_000L

    private val sequence = AtomicLong(0L)

    data class Outcome(
        val deliveryId: String,
        val mode: DeliveryMode,
        val reason: DeliveryReason,
        val notificationShown: Boolean,
        val voiceRequested: Boolean,
        val duplicate: Boolean = false,
    )

    fun newDeliveryId(now: Long = System.currentTimeMillis()): String =
        "pd-$now-${sequence.incrementAndGet()}"

    /**
     * @param fakeState debug-подмена состояния (silent/dnd/call/session/nonotif/novoice).
     * @param modeOverride "silent" может только понизить голос до уведомления;
     *        "voice" не может обойти SUPPRESS/тихий режим.
     */
    fun deliver(
        context: Context,
        decision: ProactiveDecision,
        text: ProactiveText = ProactiveTextBuilder.build(decision.type, decision.reason),
        fakeState: String? = null,
        modeOverride: String? = null,
    ): Outcome {
        val now = System.currentTimeMillis()
        val deliveryId = newDeliveryId(now)
        val fingerprint = decision.fingerprint
        val patternKey = decision.behaviorPatternKey

        val base = PhoneStateReader.read(context)
        val state = PhoneStateReader.applyFake(base, fakeState)
        var resolved = DeliveryDecider.decide(decision.type, state)

        val requestedMode = modeOverride?.trim()?.lowercase().orEmpty()
        if (requestedMode == "silent" && resolved.mode != DeliveryMode.SUPPRESS) {
            resolved = DeliveryDecision(DeliveryMode.SILENT_NOTIFICATION_ONLY, resolved.reason)
        } else if (requestedMode == "voice" && resolved.mode != DeliveryMode.SUPPRESS) {
            // Debug-only: пробуем голосовой канал, даже если реальное состояние
            // деградировало до уведомления. VoiceService сам откажется говорить,
            // если сессия активна/нет движка/не дали аудиофокус.
            resolved = DeliveryDecision(DeliveryMode.VOICE_AND_NOTIFICATION, resolved.reason)
        }

        // Дедупликация и отсрочка — до показа. Сами подавления тоже пишем в журнал.
        if (decision.type != ProactiveDecisionType.NO_OP) {
            if (ProactiveDeliveryLog.isSnoozed(context, fingerprint, now)) {
                return suppress(
                    context = context,
                    deliveryId = deliveryId,
                    fingerprint = fingerprint,
                    patternKey = patternKey,
                    decision = decision,
                    reason = DeliveryReason.SNOOZED,
                    now = now,
                )
            }
            if (ProactiveDeliveryLog.hasDelivered(context, fingerprint)) {
                return suppress(
                    context = context,
                    deliveryId = deliveryId,
                    fingerprint = fingerprint,
                    patternKey = patternKey,
                    decision = decision,
                    reason = DeliveryReason.DUPLICATE,
                    now = now,
                    duplicate = true,
                )
            }
        }

        if (resolved.mode == DeliveryMode.SUPPRESS) {
            return suppress(
                context = context,
                deliveryId = deliveryId,
                fingerprint = fingerprint,
                patternKey = patternKey,
                decision = decision,
                reason = resolved.reason,
                now = now,
            )
        }

        val notificationShown = ProactiveNotification.show(
            context = context,
            deliveryId = deliveryId,
            text = text,
            patternKey = patternKey,
            fingerprint = fingerprint,
        )

        var voice = ProactiveVoice.Result(requested = false, note = "silent_only")
        if (resolved.mode == DeliveryMode.VOICE_AND_NOTIFICATION) {
            voice = ProactiveVoice.speak(context, text.voice)
        }

        ProactiveDeliveryLog.append(
            context = context,
            deliveryId = deliveryId,
            fingerprint = fingerprint,
            patternKey = patternKey,
            mode = resolved.mode,
            reason = resolved.reason,
            reasonText = decision.reason,
            voiceShown = voice.requested,
            now = now,
        )

        Log.i(
            TAG,
            "deliver id=$deliveryId type=${decision.type} fake=${fakeState ?: "none"} " +
                "mode=${resolved.mode} reason=${resolved.reason} " +
                "notification=$notificationShown voiceRequested=${voice.requested} " +
                "note=${voice.note}",
        )

        return Outcome(
            deliveryId = deliveryId,
            mode = resolved.mode,
            reason = resolved.reason,
            notificationShown = notificationShown,
            voiceRequested = voice.requested,
        )
    }

    /**
     * Общая логика кнопок «Да/Нет/Позже» для ProactiveActionReceiver и
     * debug-хука DEBUG_PROACTIVE_ACTION.
     *
     * @return короткое имя выполненного действия для лога.
     */
    fun handleAction(
        context: Context,
        action: String,
        deliveryId: String,
        patternKey: String?,
        fingerprint: String?,
    ): String {
        val now = System.currentTimeMillis()
        return when (action) {
            ProactiveActionReceiver.ACTION_LATER -> {
                val until = now + SNOOZE_MS
                ProactiveDeliveryLog.setAction(
                    context,
                    deliveryId,
                    ProactiveDeliveryLog.ACTION_LATER,
                    until,
                )
                Log.i(TAG, "action later id=$deliveryId until=$until")
                ProactiveDeliveryLog.ACTION_LATER
            }
            ProactiveActionReceiver.ACTION_YES,
            ProactiveActionReceiver.ACTION_NO,
            -> {
                val feedback = if (action == ProactiveActionReceiver.ACTION_YES) {
                    BehaviorFeedback.ACCEPT
                } else {
                    BehaviorFeedback.REJECT
                }
                val result = runCatching {
                    CompanionProactive.applyFeedback(
                        context = context,
                        behavior = CompanionBehavior.get(context),
                        feedback = feedback,
                        patternKey = patternKey,
                    )
                }
                val ok = result.isSuccess
                ProactiveDeliveryLog.setAction(
                    context,
                    deliveryId,
                    if (action == ProactiveActionReceiver.ACTION_YES) {
                        ProactiveDeliveryLog.ACTION_YES
                    } else {
                        ProactiveDeliveryLog.ACTION_NO
                    },
                )
                Log.i(
                    TAG,
                    "action ${if (action == ProactiveActionReceiver.ACTION_YES) "yes" else "no"} " +
                        "id=$deliveryId key=${patternKey ?: "—"} feedback=$feedback ok=$ok" +
                        (result.exceptionOrNull()?.let { " err=${it.message}" } ?: ""),
                )
                action
            }
            else -> {
                Log.i(TAG, "action ignored: unknown=$action id=$deliveryId")
                "ignored"
            }
        }
    }

    private fun suppress(
        context: Context,
        deliveryId: String,
        fingerprint: String?,
        patternKey: String?,
        decision: ProactiveDecision,
        reason: DeliveryReason,
        now: Long,
        duplicate: Boolean = false,
    ): Outcome {
        ProactiveDeliveryLog.append(
            context = context,
            deliveryId = deliveryId,
            fingerprint = fingerprint,
            patternKey = patternKey,
            mode = DeliveryMode.SUPPRESS,
            reason = reason,
            reasonText = decision.reason,
            voiceShown = false,
            now = now,
        )
        Log.i(
            TAG,
            "deliver id=$deliveryId type=${decision.type} mode=SUPPRESS reason=$reason " +
                "notification=false voiceRequested=false dup=$duplicate",
        )
        return Outcome(
            deliveryId = deliveryId,
            mode = DeliveryMode.SUPPRESS,
            reason = reason,
            notificationShown = false,
            voiceRequested = false,
            duplicate = duplicate,
        )
    }
}
