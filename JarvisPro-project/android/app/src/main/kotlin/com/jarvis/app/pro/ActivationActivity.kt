package com.jarvis.app.pro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран «Активация Jarvis Pro»: показывается вместо Pro, пока нет действующего ключа.
 * Код телефона → бот (кнопка сразу передаёт код) → ключ → «Вставить» → «Активировать».
 */
class ActivationActivity : AppCompatActivity() {
    private lateinit var keyField: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = ProLicense.prettyCode(this)
        val info = ProLicense.current(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(32))
        }
        col.addView(text("JARVIS PRO", 13f, GOLD, bold = true, spacing = 0.3f))
        col.addView(text("Активация", 30f, WHITE, bold = true).apply { setPadding(0, dp(6), 0, dp(10)) })
        col.addView(text(intro(info), 15f, MUTED))

        col.addView(text("КОД ЭТОГО ТЕЛЕФОНА", 12f, MUTED, bold = true, spacing = 0.2f).apply { setPadding(0, dp(28), 0, dp(8)) })
        col.addView(text(code, 34f, GOLD, mono = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            background = box(0xFF0B0F14.toInt(), GOLD_DIM)
            setOnClickListener { copy(code) }
        })
        col.addView(button("Получить ключ в боте", primary = true) {
            val uri = Uri.parse("https://t.me/${ProLicense.BOT}?start=k_${ProLicense.deviceCode(this)}")
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { toast("Установите Telegram или напишите @${ProLicense.BOT}") }
        })
        col.addView(text("Нет Pro? В боте его можно купить: 200 ⭐ в месяц или 1000 ⭐ навсегда.", 13f, MUTED).apply { setPadding(0, dp(10), 0, 0) })

        col.addView(text("КЛЮЧ ИЗ БОТА", 12f, MUTED, bold = true, spacing = 0.2f).apply { setPadding(0, dp(32), 0, dp(8)) })
        keyField = EditText(this).apply {
            hint = "JP1.…"
            setHintTextColor(0xFF4A525C.toInt())
            setTextColor(WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = box(0xFF0B0F14.toInt(), 0xFF1E252E.toInt())
        }
        col.addView(keyField)
        col.addView(button("Вставить ключ", primary = false) {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (t.isNullOrEmpty()) toast("Буфер пуст — нажмите «Скопировать ключ» в боте") else { keyField.setText(t); tryActivate() }
        })
        col.addView(button("Активировать", primary = true) { tryActivate() })
        status = text("", 14f, MUTED).apply { setPadding(0, dp(14), 0, 0); gravity = Gravity.CENTER }
        col.addView(status)

        setContentView(ScrollView(this).apply { setBackgroundColor(ProActivity.BG); isFillViewport = true; addView(col) })
        window.statusBarColor = ProActivity.BG
        window.navigationBarColor = ProActivity.BG
    }

    private fun intro(info: ProLicense.Info) = when (info.status) {
        ProLicense.Status.EXPIRED -> "Срок Jarvis Pro закончился. Продлите в боте и получите новый ключ."
        ProLicense.Status.CLOCK -> "Похоже, время на телефоне переведено назад. Поставьте правильную дату."
        ProLicense.Status.WRONG_PHONE -> "Сохранённый ключ выдан для другого телефона. Получите ключ для этого."
        else -> "Чтобы включить Jarvis Pro, получите ключ в нашем Telegram-боте. Ключ работает только на этом телефоне."
    }

    private fun tryActivate() {
        val key = keyField.text.toString().replace("\\s".toRegex(), "")
        if (key.isEmpty()) { show("Вставьте ключ из бота", ERR); return }
        val info = ProLicense.activate(this, key)
        when (info.status) {
            ProLicense.Status.OK -> {
                val left = ProLicense.daysLeft(info)
                toast(if (left < 0) "Jarvis Pro активирован навсегда" else "Jarvis Pro активирован: осталось дней — $left")
                startActivity(Intent(this, ProActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            ProLicense.Status.WRONG_PHONE -> show("Этот ключ для другого телефона. Пришлите боту код ${ProLicense.prettyCode(this)}", ERR)
            ProLicense.Status.EXPIRED -> show("Срок этого ключа закончился — продлите Pro в боте", ERR)
            ProLicense.Status.CLOCK -> show("Проверьте дату и время на телефоне", ERR)
            else -> show("Ключ не подходит. Скопируйте его из бота целиком", ERR)
        }
    }

    private fun show(msg: String, color: Int) { status.text = msg; status.setTextColor(color) }
    private fun copy(code: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Код телефона", code))
        toast("Код скопирован")
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun box(fill: Int, stroke: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(14).toFloat(); setStroke(dp(1), stroke) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false, spacing: Float = 0f) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); letterSpacing = spacing
        typeface = if (mono) Typeface.MONOSPACE else Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun button(label: String, primary: Boolean, onClick: (View) -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f
        setTextColor(if (primary) 0xFF1A1206.toInt() else GOLD)
        background = if (primary) GradientDrawable().apply { setColor(GOLD); cornerRadius = dp(14).toFloat() } else box(0x00000000, GOLD_DIM)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) }
        setOnClickListener(onClick)
    }

    companion object {
        private const val GOLD = 0xFFFFC46B.toInt()
        private const val GOLD_DIM = 0x66FFC46B
        private const val WHITE = 0xFFF2F4F7.toInt()
        private const val MUTED = 0xFF8A93A0.toInt()
        private const val ERR = 0xFFFF8A6B.toInt()
    }
}
