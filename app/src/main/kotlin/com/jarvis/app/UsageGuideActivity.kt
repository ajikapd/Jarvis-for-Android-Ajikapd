package com.jarvis.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Обучение после онбординга разрешений: как пользоваться ассистентом.
 */
class UsageGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage_guide)

        findViewById<android.view.View>(R.id.btnGuideDone).setOnClickListener {
            markDone()
            startActivity(
                Intent(this, DashboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
        findViewById<TextView>(R.id.btnGuideCommands).setOnClickListener {
            markDone()
            startActivity(
                Intent(this, DashboardActivity::class.java)
                    .putExtra(DashboardActivity.EXTRA_OPEN_TAB, DashboardActivity.TAB_MORE)
                    .putExtra(DashboardActivity.EXTRA_OPEN_SECONDARY, "commands")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }

    private fun markDone() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_USAGE_GUIDE_DONE, true)
            .apply()
    }

    companion object {
        const val PREFS = "jarvis_prefs"
        const val PREF_USAGE_GUIDE_DONE = "usage_guide_done_v1"

        fun needsGuide(activity: AppCompatActivity): Boolean {
            val prefs = activity.getSharedPreferences(PREFS, MODE_PRIVATE)
            return !prefs.getBoolean(PREF_USAGE_GUIDE_DONE, false)
        }
    }
}
