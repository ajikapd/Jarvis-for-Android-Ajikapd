package com.jarvis.app.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Б3: кнопки проактивного уведомления «Да» / «Нет» / «Позже».
 *
 * Receiver не экспортируется: PendingIntent создаётся с явным компонентом,
 * снаружи его вызвать нельзя. После действия уведомление снимается.
 */
class ProactiveActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val deliveryId = intent.getStringExtra(EXTRA_DELIVERY_ID) ?: return
        val patternKey = intent.getStringExtra(EXTRA_PATTERN_KEY)
        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT)
        perform(context.applicationContext, action, deliveryId, patternKey, fingerprint)
    }

    companion object {
        private const val TAG = "Jarvis.ProactiveDelivery"

        /**
         * Общая точка для PendingIntent кнопок и debug-хука
         * DEBUG_PROACTIVE_ACTION: журнал + feedback + снятие уведомления.
         */
        fun perform(
            context: Context,
            action: String,
            deliveryId: String,
            patternKey: String?,
            fingerprint: String?,
        ) {
            val app = context.applicationContext
            Log.i(
                TAG,
                "receive action=$action id=$deliveryId key=${patternKey ?: "—"} " +
                    "fp=${fingerprint ?: "—"}",
            )
            ProactiveDelivery.handleAction(
                context = app,
                action = action,
                deliveryId = deliveryId,
                patternKey = patternKey,
                fingerprint = fingerprint,
            )
            if (action == ACTION_YES || action == ACTION_NO || action == ACTION_LATER) {
                ProactiveNotification.cancel(app, deliveryId)
            }
        }

        const val ACTION_YES = "com.jarvis.assistant.PROACTIVE_YES"
        const val ACTION_NO = "com.jarvis.assistant.PROACTIVE_NO"
        const val ACTION_LATER = "com.jarvis.assistant.PROACTIVE_LATER"

        const val EXTRA_DELIVERY_ID = "deliveryId"
        const val EXTRA_PATTERN_KEY = "patternKey"
        const val EXTRA_FINGERPRINT = "fingerprint"
    }
}
