package com.jarvis.app

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.app.ui.Fonts
import com.jarvis.app.ui.dp
import com.jarvis.core.startup.UiStyle

/** Первый запуск после разрешений: какой интерфейс использовать. Менять можно потом в «Оформлении». */
class InterfaceChoiceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        root.addView(text("ИНТЕРФЕЙС", 12f, ACCENT, Fonts.display(this, 700)).apply { letterSpacing = .3f })
        root.addView(text("Как будет выглядеть Джарвис?", 26f, INK, Fonts.display(this, 700)).apply { setPadding(0, dp(6), 0, dp(6)) })
        root.addView(text("Выберите вид. Поменять его можно в любой момент: в настройках оформления.", 14f, MUTED, Fonts.body(this)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            setPadding(0, dp(22), 0, 0)
        }
        row.addView(
            card(
                R.drawable.ui_choice_modern, "Современный",
                "Реактор, режим Live и пять тем оформления.", UiStyle.MODERN,
            ),
            LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(7) },
        )
        row.addView(
            card(
                R.drawable.ui_choice_classic, "Классический",
                "Инженерная консоль: ядро, конвейер и журнал.", UiStyle.CLASSIC,
            ),
            LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(7) },
        )
        root.addView(row)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        })
    }

    private fun card(image: Int, title: String, about: String, style: UiStyle): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(CARD)
                setStroke(dp(1), LINE)
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Интерфейс: $title"
            setOnClickListener { choose(style) }
        }
        box.addView(ImageView(this).apply {
            setImageResource(image)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(-1, -2))
        box.addView(text(title, 17f, INK, Fonts.display(this, 700)).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
        box.addView(text(about, 12.5f, MUTED, Fonts.body(this)).apply { setPadding(dp(2), 0, 0, dp(12)) })
        // Кнопки обеих карточек стоят на одной линии, даже если описание разной длины.
        box.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        box.addView(text("Выбрать", 14f, Color.parseColor("#03121F"), Fonts.body(this, 700)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(ACCENT)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun choose(style: UiStyle) {
        UiMode.set(this, style)
        UiMode.continueFrom(this)
    }

    private fun text(value: String, size: Float, color: Int, face: android.graphics.Typeface) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = face
    }

    private companion object {
        val BG = Color.parseColor("#040811")
        val CARD = Color.parseColor("#0B1628")
        val LINE = Color.parseColor("#1F3654")
        val INK = Color.parseColor("#ECF4FF")
        val MUTED = Color.parseColor("#8FA8C6")
        val ACCENT = Color.parseColor("#35C8FF")
    }
}
