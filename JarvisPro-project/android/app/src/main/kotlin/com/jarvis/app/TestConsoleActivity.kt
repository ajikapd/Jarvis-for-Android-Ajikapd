package com.jarvis.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.jarvis.core.common.model.UserRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TestConsoleActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var voiceListener: VoiceController.Listener? = null

    private val brain by lazy {
        BrainGraph.build(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_console)

        val input = findViewById<EditText>(R.id.input)
        val status = findViewById<TextView>(R.id.status)
        val result = findViewById<TextView>(R.id.result)
        val voiceStatus = findViewById<TextView>(R.id.voiceStatus)
        val btnVoice = findViewById<Button>(R.id.btnVoice)

        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener

            status.text = "processing…"
            result.text = ""
            scope.launch {
                val response = withContext(Dispatchers.Default) { brain.process(UserRequest(text)) }
                val toolLine = response.toolResult?.let {
                    "tool=${it.toolId} status=${it.status} ${it.durationMs}ms"
                } ?: "tool=none"
                status.text = "intent=${response.intent?.type} conf=${response.intent?.confidence}\n" +
                    "status=${response.status} duration=${response.durationMs}ms\n" +
                    toolLine
                result.text = response.replyText
            }
        }

        btnVoice.setOnClickListener {
            if (VoiceController.isRunning) {
                stopVoice(btnVoice, voiceStatus)
            } else {
                startVoice(btnVoice, voiceStatus, status, result)
            }
        }
    }

    private fun startVoice(
        btnVoice: Button,
        voiceStatus: TextView,
        status: TextView,
        result: TextView,
    ) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        val listener = object : VoiceController.Listener {
            override fun onWake() {
                voiceStatus.text = "Wake! speak the command…"
            }

            override fun onRequest(userRequest: UserRequest) {
                val text = userRequest.text
                if (text.isBlank()) {
                    status.text = "[VOICE] wake — say the command"
                    result.text = ""
                    return
                }
                // Обработка и озвучивание — в VoiceService (coreListener).
                // Консоль только отображает событие, чтобы не дублировать
                // выполнение инструментов (фонарик переключается дважды).
                status.text = "[VOICE] \"$text\""
                result.text = "обрабатывается сервисом…"
            }
        }
        VoiceController.addListener(listener)
        voiceListener = listener
        VoiceController.start(applicationContext)

        btnVoice.text = "Stop Voice"
        voiceStatus.text = "Listening... say \"джарвис, ...\""
    }

    private fun stopVoice(btnVoice: Button, voiceStatus: TextView) {
        voiceListener?.let { VoiceController.removeListener(it) }
        voiceListener = null
        VoiceController.stop(applicationContext)
        btnVoice.text = "Start Voice — say \"джарвис\""
        voiceStatus.text = "Stopped"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val btnVoice = findViewById<Button>(R.id.btnVoice)
            val voiceStatus = findViewById<TextView>(R.id.voiceStatus)
            val status = findViewById<TextView>(R.id.status)
            val result = findViewById<TextView>(R.id.result)
            startVoice(btnVoice, voiceStatus, status, result)
        }
    }

    override fun onDestroy() {
        voiceListener?.let { VoiceController.removeListener(it) }
        scope.cancel()
        super.onDestroy()
    }
}
