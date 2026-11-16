package com.jarvis.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Б1: полная таблица решений доставки. Без Android.
 */
class DeliveryDeciderTest {

    private fun state(
        ringer: RingerKind = RingerKind.NORMAL,
        filter: InterruptionKind = InterruptionKind.ALL,
        call: Boolean = false,
        session: Boolean = false,
        speaking: Boolean = false,
        notifications: Boolean = true,
        voice: Boolean = true,
    ) = PhoneStateSnapshot(
        ringerMode = ringer,
        interruptionFilter = filter,
        callActive = call,
        jarvisSessionActive = session,
        jarvisSpeaking = speaking,
        notificationsAllowed = notifications,
        voiceAvailable = voice,
    )

    private fun assertDecision(
        expectedMode: DeliveryMode,
        expectedReason: DeliveryReason,
        actual: DeliveryDecision,
    ) {
        assertEquals(expectedMode, actual.mode)
        assertEquals(expectedReason, actual.reason)
    }

    @Test
    fun noOpIsAlwaysSuppressed() {
        assertDecision(
            DeliveryMode.SUPPRESS,
            DeliveryReason.NO_OP,
            DeliveryDecider.decide(
                ProactiveDecisionType.NO_OP,
                state(ringer = RingerKind.SILENT, notifications = false),
            ),
        )
    }

    @Test
    fun notificationsDisabledSuppressesEvenOnCall() {
        assertDecision(
            DeliveryMode.SUPPRESS,
            DeliveryReason.NOTIFICATIONS_DISABLED,
            DeliveryDecider.decide(
                ProactiveDecisionType.SUGGESTION,
                state(notifications = false, call = true),
            ),
        )
    }

    @Test
    fun activeJarvisSessionSuppresses() {
        assertDecision(
            DeliveryMode.SUPPRESS,
            DeliveryReason.JARVIS_SESSION_ACTIVE,
            DeliveryDecider.decide(ProactiveDecisionType.QUESTION, state(session = true)),
        )
    }

    @Test
    fun jarvisSpeakingSuppresses() {
        assertDecision(
            DeliveryMode.SUPPRESS,
            DeliveryReason.JARVIS_SPEAKING,
            DeliveryDecider.decide(
                ProactiveDecisionType.SUGGESTION,
                state(speaking = true),
            ),
        )
    }

    @Test
    fun sessionWinsOverSpeakingAndDnd() {
        assertDecision(
            DeliveryMode.SUPPRESS,
            DeliveryReason.JARVIS_SESSION_ACTIVE,
            DeliveryDecider.decide(
                ProactiveDecisionType.CONFIRMATION_REQUEST,
                state(session = true, speaking = true, filter = InterruptionKind.NONE),
            ),
        )
    }

    @Test
    fun callIsSilentOnly() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.CALL_ACTIVE,
            DeliveryDecider.decide(ProactiveDecisionType.SUGGESTION, state(call = true)),
        )
    }

    @Test
    fun callWinsOverRingerSilent() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.CALL_ACTIVE,
            DeliveryDecider.decide(
                ProactiveDecisionType.SUGGESTION,
                state(call = true, ringer = RingerKind.SILENT),
            ),
        )
    }

    @Test
    fun ringerSilentIsSilentOnly() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.RINGER_SILENT,
            DeliveryDecider.decide(
                ProactiveDecisionType.SUGGESTION,
                state(ringer = RingerKind.SILENT),
            ),
        )
    }

    @Test
    fun ringerVibrateIsSilentOnly() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.RINGER_VIBRATE,
            DeliveryDecider.decide(
                ProactiveDecisionType.SUGGESTION,
                state(ringer = RingerKind.VIBRATE),
            ),
        )
    }

    @Test
    fun dndPriorityIsSilentOnly() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.DND_PRIORITY,
            DeliveryDecider.decide(
                ProactiveDecisionType.QUESTION,
                state(filter = InterruptionKind.PRIORITY),
            ),
        )
    }

    @Test
    fun dndAlarmsIsSilentOnly() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.DND_ALARMS,
            DeliveryDecider.decide(
                ProactiveDecisionType.QUESTION,
                state(filter = InterruptionKind.ALARMS),
            ),
        )
    }

    @Test
    fun dndNoneIsSilentOnly() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.DND_NONE,
            DeliveryDecider.decide(
                ProactiveDecisionType.QUESTION,
                state(filter = InterruptionKind.NONE),
            ),
        )
    }

    @Test
    fun unknownFilterIsTreatedAsDnd() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.DND_UNKNOWN,
            DeliveryDecider.decide(
                ProactiveDecisionType.SUGGESTION,
                state(filter = InterruptionKind.UNKNOWN),
            ),
        )
    }

    @Test
    fun dndWinsOverNoVoice() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.DND_PRIORITY,
            DeliveryDecider.decide(
                ProactiveDecisionType.SUGGESTION,
                state(filter = InterruptionKind.PRIORITY, voice = false),
            ),
        )
    }

    @Test
    fun noVoiceIsSilentOnly() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.VOICE_UNAVAILABLE,
            DeliveryDecider.decide(
                ProactiveDecisionType.SUGGESTION,
                state(voice = false),
            ),
        )
    }

    @Test
    fun allClearMeansVoiceAndNotification() {
        assertDecision(
            DeliveryMode.VOICE_AND_NOTIFICATION,
            DeliveryReason.READY,
            DeliveryDecider.decide(ProactiveDecisionType.SUGGESTION, state()),
        )
    }

    @Test
    fun allClearWorksForQuestion() {
        assertDecision(
            DeliveryMode.VOICE_AND_NOTIFICATION,
            DeliveryReason.READY,
            DeliveryDecider.decide(ProactiveDecisionType.QUESTION, state()),
        )
    }

    @Test
    fun allClearWorksForConfirmation() {
        assertDecision(
            DeliveryMode.VOICE_AND_NOTIFICATION,
            DeliveryReason.READY,
            DeliveryDecider.decide(ProactiveDecisionType.CONFIRMATION_REQUEST, state()),
        )
    }

    @Test
    fun silentAndNoVoiceStaysSilent() {
        assertDecision(
            DeliveryMode.SILENT_NOTIFICATION_ONLY,
            DeliveryReason.RINGER_SILENT,
            DeliveryDecider.decide(
                ProactiveDecisionType.SUGGESTION,
                state(ringer = RingerKind.SILENT, voice = false),
            ),
        )
    }

    @Test
    fun dndAndSessionSuppresses() {
        assertDecision(
            DeliveryMode.SUPPRESS,
            DeliveryReason.JARVIS_SESSION_ACTIVE,
            DeliveryDecider.decide(
                ProactiveDecisionType.SUGGESTION,
                state(filter = InterruptionKind.PRIORITY, session = true),
            ),
        )
    }
}
