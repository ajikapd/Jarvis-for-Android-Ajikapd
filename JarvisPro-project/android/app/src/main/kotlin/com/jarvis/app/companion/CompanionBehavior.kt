package com.jarvis.app.companion

import android.content.Context
import com.jarvis.core.behavior.BehaviorLearningService
import com.jarvis.core.behavior.BehaviorSnapshotCodec
import com.jarvis.core.behavior.BehaviorStore

/** Process-wide BehaviorLearning (Stage 6 / 6.5). Persist candidates + feedback. */
object CompanionBehavior {
    @Volatile
    private var service: BehaviorLearningService? = null

    fun get(context: Context): BehaviorLearningService {
        service?.let { return it }
        return synchronized(this) {
            service ?: run {
                val appCtx = context.applicationContext
                val store = BehaviorStore()
                val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val snapRaw = prefs.getString(KEY_SNAPSHOT, null)
                if (!snapRaw.isNullOrBlank()) {
                    store.restore(BehaviorSnapshotCodec.decode(snapRaw))
                } else {
                    store.loadDisabled(prefs.getStringSet(KEY_DISABLED, emptySet()) ?: emptySet())
                    store.loadRejected(prefs.getStringSet(KEY_REJECTED, emptySet()) ?: emptySet())
                }
                BehaviorLearningService(
                    store = store,
                    onStoreChanged = { persist(appCtx, store) },
                ).also { service = it }
            }
        }
    }

    fun persistFeedback(context: Context, learning: BehaviorLearningService) {
        persist(context.applicationContext, learning.store())
    }

    private fun persist(appCtx: Context, store: BehaviorStore) {
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = BehaviorSnapshotCodec.encode(store.snapshot())
        prefs.edit()
            .putString(KEY_SNAPSHOT, encoded)
            .putStringSet(KEY_DISABLED, store.disabledKeys())
            .putStringSet(KEY_REJECTED, store.rejectedKeys())
            .apply()
    }

    private const val PREFS = "jarvis_behavior_feedback"
    private const val KEY_DISABLED = "disabled_keys"
    private const val KEY_REJECTED = "rejected_keys"
    private const val KEY_SNAPSHOT = "behavior_snapshot_v1"
}
