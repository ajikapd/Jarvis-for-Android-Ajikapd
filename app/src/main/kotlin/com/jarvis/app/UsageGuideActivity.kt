package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.app.ui.Fonts
import com.jarvis.app.ui.Skins
import com.jarvis.app.ui.dp
import com.jarvis.core.startup.UiStyle

/**
 * Гид после выбора интерфейса: как говорить, ключи облачного ИИ, запись голоса.
 * Оформление и пути в настройках зависят от того, какой интерфейс выбран.
 */
class UsageGuideActivity : AppCompatActivity() {

    /** Цвета и шрифты одного из двух оформлений. */
    private class Look(
        val bg: Int, val card: Int, val line: Int, val ink: Int, val muted: Int,
        val accent: Int, val warm: Int, val onAccent: Int, val light: Boolean,
        val head: Typeface, val body: Typeface, val classic: Boolean,
    )

    private lateinit var look: Look
    private var classic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        classic = UiMode.isClassic(this)
        look = if (classic) classicLook() else modernLook()
        window.statusBarColor = look.bg
        window.navigationBarColor = look.bg
        window.decorView.systemUiVisibility = if (look.light) {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else 0
        setContentView(build())
    }

    private fun modernLook(): Look {
        val s = Skins.current(this)
        return Look(
            bg = s.bg, card = s.glass, line = s.line, ink = s.text, muted = s.muted,
            accent = s.accent, warm = s.hot, onAccent = s.onAccent, light = s.light,
            head = Fonts.display(this, 700), body = Fonts.body(this), classic = false,
        )
    }

    private fun classicLook(): Look {
        fun c(id: Int) = ContextCompat.getColor(this, id)
        return Look(
            bg = c(R.color.jarvis_bg), card = c(R.color.jarvis_panel), line = c(R.color.jarvis_border),
            ink = c(R.color.jarvis_text), muted = c(R.color.jarvis_muted), accent = c(R.color.jarvis_cyan),
            warm = c(R.color.jarvis_primary), onAccent = c(R.color.jarvis_bg), light = false,
            head = Typeface.MONOSPACE, body = Typeface.DEFAULT, classic = true,
        )
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(28))
        }
        if (look.classic) {
            root.addView(text("// JARVIS · ИНСТРУКЦИЯ", 12f, look.warm, look.head).apply { letterSpacing = .12f })
            root.addView(text("КАК ЭТИМ ПОЛЬЗОВАТЬСЯ", 22f, look.ink, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { setPadding(0, dp(6), 0, dp(6)) })
        } else {
            root.addView(text("ГИД", 12f, look.accent, look.head).apply { letterSpacing = .3f })
            root.addView(text("Как пользоваться Джарвисом", 26f, look.ink, look.head).apply { setPadding(0, dp(6), 0, dp(6)) })
        }
        root.addView(text("Шесть коротких пунктов. Гид можно открыть снова в настройках.", 14f, look.muted, look.body))

        sections().forEachIndexed { i, s -> root.addView(section(i + 1, s), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }) }

        root.addView(button(if (look.classic) "[ НАЧАТЬ РАБОТУ ]" else "Начать", primary = true) { done() },
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(22) })
        return ScrollView(this).apply {
            setBackgroundColor(look.bg)
            addView(root)
        }
    }

    /** Пункт гида: заголовок, абзацы, шаги, кнопки. */
    private class Part(
        val title: String,
        val blocks: List<Pair<String, List<String>>>,
        val actions: List<Pair<String, () -> Unit>> = emptyList(),
        val tip: String? = null,
    )

    private fun sections(): List<Part> {
        val keysPath = if (classic) {
            "«Профиль» → «Общее» → «Облачный LLM». Вставьте ключ в поле Groq, Google Gemini или GigaChat и нажмите «Сохранить»."
        } else {
            "«Ещё» → «ИИ и модели» → «Облачные ключи». Нажмите на строку Groq, Gemini или GigaChat, вставьте ключ и нажмите «Сохранить». Метка сменится на «Сохранён». Там же оставьте маршрут «Гибрид» и включите «Использовать ИИ-модель»."
        }
        val voicePlace = if (classic) {
            "Запись голоса открывается отдельным экраном кнопкой ниже. После записи нажмите «Назад», и вы вернётесь сюда."
        } else {
            "«Ещё» → «Голос и слух»."
        }
        return listOf(
            Part(
                "Как говорить",
                listOf(
                    "Скажите «Джарвис», дождитесь сигнала и говорите команду. Можно одной фразой: «Джарвис, какая погода»." to listOf(
                        "«Позвони маме», «Напиши Саше в Телеграм, что опоздаю»",
                        "«Разбуди в семь утра», «Поставь таймер на 10 минут»",
                        "«Включи фонарик», «Громкость на максимум», «Включи Bluetooth»",
                        "«Какая погода завтра», «Курс доллара», «Что нового»",
                    ),
                    "Можно несколько дел сразу: «выключи Wi-Fi и поставь будильник на 8». Звонки, отправку сообщений и удаление Джарвис переспросит." to emptyList(),
                ),
            ),
            Part(
                "Облачный ИИ: Groq, Gemini, GigaChat",
                listOf(
                    "Без ключей работают команды телефона и локальная модель. С ключами Джарвис отвечает умнее и быстрее. Оба ключа бесплатные, нужен только аккаунт." to emptyList(),
                    "Groq: основная быстрая модель" to listOf(
                        "Откройте https://console.groq.com/keys и войдите через Google или почту.",
                        "Нажмите Create API Key и дайте любое имя, например «jarvis».",
                        "Сразу скопируйте ключ: он начинается с gsk_ и больше не покажется.",
                    ),
                    "Gemini: запасная цепочка" to listOf(
                        "Откройте https://aistudio.google.com/apikey и войдите аккаунтом Google.",
                        "Нажмите Create API key, выберите или создайте проект.",
                        "Скопируйте ключ: он начинается с AIza.",
                    ),
                    "GigaChat: бесплатно и без VPN (Россия)" to listOf(
                        "Откройте https://developers.sber.ru/studio и войдите по Сбер ID.",
                        "Создайте проект «GigaChat API» и примите соглашение.",
                        "В проекте откройте «Настройки API» → «Получить ключ» и скопируйте Authorization key: он покажется один раз.",
                        "Бесплатно 365 млн токенов на 12 месяцев: на обычные разговоры хватит с запасом. Агент «сделай сам» и Live пока работают только с Groq и Gemini.",
                    ),
                    "Куда вставить: $keysPath" to emptyList(),
                ),
                actions = listOf("Открыть настройки ключей" to { openSection(SECTION_AI) }),
                tip = "Ключи хранятся на телефоне в зашифрованном виде и уходят только в Groq, Google и Сбер. Никому их не пересылайте. Groq и Gemini в некоторых странах открываются только через VPN, GigaChat в России работает без него.",
            ),
            Part(
                "Ваш голос",
                listOf(
                    "Джарвис может слушаться только вас: чужие голоса и звук из видео не будут его будить. $voicePlace" to listOf(
                        "Нажмите «Скачать модель узнавания»: она нужна один раз, 28 МБ.",
                        "Несколько раз скажите «Джарвис», затем прочитайте вслух предложенные фразы. Говорите обычным голосом в тихом месте.",
                        "Включите «Только мой голос».",
                    ),
                    "Если слово срабатывает слишком часто или редко, поменяйте «Чувствительность» там же. Если узнавать стал хуже, нажмите «Записать голос заново»." to emptyList(),
                ),
                actions = listOf("Записать голос" to { openSection(SECTION_VOICE) }),
            ),
            Part(
                "Live и «сделай сам»",
                listOf(
                    "Вкладка Live включает живой разговор без слова «Джарвис». Перебивайте, когда хотите." to emptyList(),
                    "Начните команду со слов «сделай сам», и Джарвис выполнит её в приложении: найдёт видео на YouTube, откроет раздел настроек или место на карте. «Стоп» прерывает. Пароли и карты он не вводит никогда." to emptyList(),
                ),
            ),
            Part(
                "Музыка и сценарии",
                listOf(
                    "Установите SoundCloud и войдите в аккаунт: «включи Queen», «включи мою музыку», «пауза», «дальше». Без интернета Джарвис включит скачанное в Яндекс Музыке." to emptyList(),
                    "Правила говорите вслух: «когда подключу наушники, включи музыку», «когда открою YouTube, громкость на максимум». Список правил — в разделе сценариев." to emptyList(),
                ),
            ),
            Part(
                "Если не слышит",
                listOf(
                    "" to listOf(
                        "Проверьте, что ассистент включён.",
                        "Батарея для Джарвиса: «без ограничений», автозапуск разрешён.",
                        "Android 13+ пишет «Настройки с ограниченным доступом»: «О приложении» Jarvis → ⋮ → «Разрешить ограниченные настройки».",
                        "Поднимите чувствительность слова или перезапишите голос.",
                        "Отвечает «не понял» на вопросы: проверьте ключи и интернет.",
                    ),
                ),
            ),
        )
    }

    private fun section(index: Int, part: Part): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = if (look.classic) {
                ContextCompat.getDrawable(this@UsageGuideActivity, R.drawable.bg_hud_panel)
            } else {
                GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(look.card)
                    setStroke(dp(1), look.line)
                }
            }
        }
        val title = if (look.classic) "%02d // %s".format(index, part.title.uppercase()) else part.title
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!look.classic) {
            head.addView(text("%02d".format(index), 12f, look.accent, look.head).apply { setPadding(0, 0, dp(10), 0) })
        }
        head.addView(text(title, if (look.classic) 14f else 17f, if (look.classic) look.accent else look.ink, if (look.classic) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else look.head))
        box.addView(head)
        part.blocks.forEach { (para, steps) ->
            if (para.isNotBlank()) {
                val strong = steps.isNotEmpty() && para.length < 40
                box.addView(link(text(para, if (strong) 14.5f else 14f, if (strong) look.ink else look.muted, if (strong) Typeface.create(look.body, Typeface.BOLD) else look.body).apply {
                    setPadding(0, dp(10), 0, 0)
                    setLineSpacing(0f, 1.15f)
                }))
            }
            steps.forEachIndexed { i, step ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
                val mark = if (look.classic) "›" else "${i + 1}."
                row.addView(text(mark, 14f, look.accent, look.head).apply { minWidth = dp(20) })
                row.addView(link(text(step, 14f, look.ink, look.body).apply { setLineSpacing(0f, 1.15f) }), LinearLayout.LayoutParams(0, -2, 1f))
                box.addView(row)
            }
        }
        part.tip?.let {
            box.addView(text(it, 12.5f, look.muted, look.body).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(if (look.classic) 4 else 12).toFloat()
                    setColor(Color.argb(if (look.light) 30 else 22, Color.red(look.warm), Color.green(look.warm), Color.blue(look.warm)))
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        part.actions.forEach { (label, action) ->
            box.addView(button(if (look.classic) "> ${label.uppercase()}" else label, primary = false, onClick = action),
                LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        }
        return box
    }

    private fun link(view: TextView): TextView {
        Linkify.addLinks(view, Linkify.WEB_URLS)
        view.setLinkTextColor(look.accent)
        view.movementMethod = LinkMovementMethod.getInstance()
        return view
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = if (look.classic) 13f else 15f
        typeface = if (look.classic) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Fonts.body(this@UsageGuideActivity, 700)
        setTextColor(if (primary) look.onAccent else look.accent)
        background = GradientDrawable().apply {
            cornerRadius = dp(if (look.classic) 4 else 24).toFloat()
            if (primary) setColor(look.accent) else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), look.accent)
            }
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, color: Int, face: Typeface) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = face
    }

    /** Раздел настроек отдельным окном: «Назад» вернёт в гид. */
    private fun openSection(title: String) {
        startActivity(
            Intent(this, com.jarvis.app.ui.JarvisActivity::class.java)
                .putExtra(UiMode.EXTRA_FORCE_MODERN, true)
                .putExtra(UiMode.EXTRA_OPEN_SECTION, title),
        )
    }

    private fun done() {
        markDone(this)
        // Открыт из настроек — просто закрываемся; первый запуск — дальше в выбранный интерфейс.
        if (isTaskRoot) UiMode.continueFrom(this) else finish()
    }

    companion object {
        const val PREFS = "jarvis_prefs"

        /** v2: гид с ключами облачного ИИ и записью голоса, после выбора интерфейса. */
        const val PREF_USAGE_GUIDE_DONE = "usage_guide_done_v2"
        private const val SECTION_AI = "ИИ и модели"
        private const val SECTION_VOICE = "Голос и слух"

        fun markDone(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_USAGE_GUIDE_DONE, true).apply()
        }

        fun needsGuide(activity: AppCompatActivity): Boolean {
            val prefs = activity.getSharedPreferences(PREFS, MODE_PRIVATE)
            return !prefs.getBoolean(PREF_USAGE_GUIDE_DONE, false)
        }
    }
}
