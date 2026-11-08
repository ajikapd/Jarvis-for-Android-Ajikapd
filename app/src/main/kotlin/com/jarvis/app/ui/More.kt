package com.jarvis.app.ui

import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.app.BrainGraph
import com.jarvis.app.JarvisSettings
import com.jarvis.app.ModelCatalog
import com.jarvis.app.ModelManager
import com.jarvis.app.SecureKeyStore
import com.jarvis.app.TestConsoleActivity
import com.jarvis.app.UsageGuideActivity
import com.jarvis.app.UiMode
import com.jarvis.core.startup.UiStyle
import com.jarvis.app.VoiceController
import com.jarvis.app.VoiceIdentity
import com.jarvis.core.llm.CloudKeyValidator
import com.jarvis.onboarding.PermissionsHelper
import com.jarvis.wakeword.VoicePrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Раздел «Ещё»: заголовок и построитель содержимого. */
class MoreSection(val title: String, val sub: String, val icon: String, val build: SectionScreen.(LinearLayout) -> Unit)

class MoreScreen(host: JarvisActivity) : Screen(host) {
    private val body = host.column(10)

    override val view: View = host.column().apply {
        addView(header("Ещё", false))
        addView(scroll(body), lp(h = 0, weight = 1f))
    }

    override fun onShow() {
        body.removeAllViews()
        val on = host.isOn
        body.addView(card("J.A.R.V.I.S.", if (on) "Ассистент включён" else "Ассистент выключен", Ic.GLOBE))
        val menu = host.column().apply { background = host.glassBg(skin) }
        fun add(title: String, sub: String, icon: String, action: () -> Unit) {
            menu.addView(card(title, sub, icon, onClick = action).apply { background = null })
        }
        add("Рутины", "Сценарии, рекомендации, дела", Ic.CLOCK) { host.show(JarvisActivity.ROUTINES) }
        Sections.ALL.take(6).forEach { s -> add(s.title, s.sub, s.icon) { host.openSection(s) } }
        add("Система", "Службы, нагрузка, состояние", Ic.CHART) { host.show(JarvisActivity.STATUS) }
        Sections.ALL.drop(6).forEach { s -> add(s.title, s.sub, s.icon) { host.openSection(s) } }
        body.addView(menu)
    }
}

class SectionScreen(host: JarvisActivity) : Screen(host) {
    private val root = host.column()
    var section: MoreSection? = null
        private set
    override val view: View = root

    fun bind(s: MoreSection) { section = s }

    override fun onShow() {
        val s = section ?: return
        root.removeAllViews()
        root.addView(header(s.title, true))
        val body = host.column(10)
        s.build(this, body)
        root.addView(scroll(body), lp(h = 0, weight = 1f))
    }

    val app get() = host
    val prefs get() = JarvisSettings.prefs(host)

    fun head(text: String) = section(text)
    fun note(text: String) = host.label(text, 13f, skin.muted).apply { setPadding(host.dp(4), 0, host.dp(4), 0) }

    fun toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = SwitchView(host, skin, value)
        return card(title, sub, trailing = sw).apply {
            tap { sw.checked = !sw.checked; onChange(sw.checked) }
        }
    }

    fun pref(title: String, sub: String?, key: String, default: Boolean) =
        toggle(title, sub, prefs.getBoolean(key, default)) { prefs.edit().putBoolean(key, it).apply() }

    fun options(title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val box = host.column(9).pad(12).apply { background = host.glassBg(skin) }
        if (title.isNotEmpty()) box.addView(host.label(title, 15f, skin.text, Fonts.body(host, 600)))
        box.addView(host.segment(labels, selected.coerceAtLeast(0), onPick), lp(h = host.dp(36)))
        return box
    }

    /** Ключи никогда не показываются: только «сохранён» или «нет». */
    fun keyRow(title: String, sub: String, provider: String): View {
        val store = SecureKeyStore(host)
        val box = host.column(8)
        val tag = host.label("", 12f, skin.muted)
        fun refresh() {
            val has = store.hasKey(provider)
            tag.text = if (has) "Сохранён" else "Нет"
            tag.setTextColor(if (has) skin.good else skin.muted)
        }
        val form = host.column(8).pad(10).apply { background = host.glassBg(skin); visibility = View.GONE }
        val input = textField("Вставьте ключ", secret = true)
        form.addView(input, lp(h = host.dp(42)))
        val btns = host.rowLayout(10)
        btns.addView(host.button("Удалить", false) {
            store.clearKey(provider)
            input.setText("")
            BrainGraph.onCloudKeyChanged(provider)
            BrainGraph.invalidate()
            refresh()
        }, lp(0, host.dp(44), 1f))
        btns.addView(host.button("Сохранить", true) {
            val clean = CloudKeyValidator.sanitizeKey(input.text?.toString())
            if (CloudKeyValidator.isStubKey(clean)) {
                Toast.makeText(host, "Ключ не подходит", Toast.LENGTH_SHORT).show()
            } else {
                store.setKey(provider, clean)
                input.setText("")
                BrainGraph.onCloudKeyChanged(provider)
                BrainGraph.invalidate()
                refresh()
                form.visibility = View.GONE
            }
        }, lp(0, host.dp(44), 1f))
        form.addView(btns)
        box.addView(card(title, sub, trailing = tag) { form.visibility = if (form.visibility == View.GONE) View.VISIBLE else View.GONE })
        box.addView(form)
        refresh()
        return box
    }

    fun textField(hint: String, secret: Boolean = false, value: String = "") = EditText(host).apply {
        this.hint = hint
        setText(value)
        setHintTextColor(skin.muted)
        setTextColor(skin.text)
        textSize = 14f
        background = host.glassBg(skin, 10)
        setPadding(host.dp(12), 0, host.dp(12), 0)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    /** Адрес облачного сервера: сохраняется при выходе из поля. */
    fun urlField(hint: String, value: String, save: (String) -> Unit): View = textField(hint, value = value).apply {
        setOnFocusChangeListener { _, focused -> if (!focused) { save(text.toString()); BrainGraph.invalidate() } }
        layoutParams = lp(h = host.dp(42))
    }

    fun link(title: String, sub: String?, action: () -> Unit) = card(title, sub, onClick = action)

    fun threshold(): View {
        val box = host.column(8).pad(12).apply { background = host.glassBg(skin) }
        val value = host.label("", 15f, skin.accent, Fonts.display(host))
        fun show(v: Float) { value.text = "%.2f".format(v) }
        val cur = JarvisSettings.brainConfidenceThreshold(host)
        show(cur)
        box.addView(host.rowLayout().apply {
            addView(host.label("Порог уверенности", 15f, skin.text, Fonts.body(host, 600)), lp(0, -2, 1f))
            addView(value)
        })
        box.addView(SeekBar(host).apply {
            max = 60
            progress = ((cur - .3f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if (user) { show(.3f + p / 100f); JarvisSettings.setBrainConfidenceThreshold(host, .3f + p / 100f) } }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        })
        box.addView(host.label("Ниже: чаще выполняет сам. Выше: чаще переспрашивает.", 12f, skin.muted))
        return box
    }

    fun models(into: LinearLayout) {
        val mgr = ModelManager.get(host)
        mgr.refreshAll()
        val list = host.column(10)
        into.addView(list)
        fun render() {
            list.removeAllViews()
            ModelCatalog.ALL.forEach { e ->
                val st = mgr.status(e.id)
                val tag = when (st.state) {
                    ModelManager.State.READY -> host.label("Готово", 12f, skin.good)
                    ModelManager.State.DOWNLOADING -> host.label("${(st.progress * 100).toInt()}%", 12f, skin.accent)
                    else -> host.label("Скачать", 12f, skin.onAccent, Fonts.body(host, 700)).apply {
                        background = host.glassBg(skin, 14, skin.accent, skin.accent)
                        setPadding(host.dp(12), host.dp(6), host.dp(12), host.dp(6))
                        tap { mgr.download(e.id) }
                    }
                }
                list.addView(card(e.title, "${e.subtitle}, ${e.sizeBytes / 1_000_000} МБ", trailing = tag))
            }
        }
        render()
        val job = host.lifecycleScope.launch {
            host.repeatOnLifecycle(Lifecycle.State.STARTED) { mgr.statuses.collect { if (list.isAttachedToWindow) render() } }
        }
        list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { job.cancel() }
        })
    }

    /** Запись образца голоса и включение режима «Только мой голос». */
    fun voiceLock(): View {
        val box = host.column(10)
        val mgr = ModelManager.get(host)
        val samples = ArrayList<FloatArray>()
        val phrases = ArrayList<List<FloatArray>>()
        var recording = false
        var hint: String? = null
        lateinit var render: () -> Unit
        fun record(ms: Int, onDone: suspend (FloatArray) -> Unit) {
            recording = true
            hint = null
            render()
            val started = VoiceController.recordVoiceSample(ms) { pcm ->
                host.lifecycleScope.launch {
                    onDone(pcm)
                    recording = false
                    render()
                }
            }
            if (!started) {
                recording = false
                hint = "Сначала включите ассистента."
                render()
            }
        }
        render = render@{
            box.removeAllViews()
            val needed = VoicePrint.SAMPLES_NEEDED
            when {
                !VoiceIdentity.modelReady(host) -> {
                    val st = mgr.status(VoiceIdentity.MODEL_ID)
                    if (st.state == ModelManager.State.DOWNLOADING) {
                        box.addView(card("Скачивается модель", "${(st.progress * 100).toInt()}%"))
                    } else {
                        box.addView(card("Скачать модель узнавания", "Нужна один раз, 28 МБ") { mgr.download(VoiceIdentity.MODEL_ID) })
                    }
                }
                samples.isNotEmpty() || !VoiceIdentity.enrolled(host) -> {
                    val title = if (recording) "Говорите: «Джарвис»" else "Запись ${samples.size + 1} из $needed"
                    val sub = hint ?: "Нажмите и скажите «Джарвис» как обычно. Три последние записи с двух шагов."
                    box.addView(card(title, sub) {
                        if (recording) return@card
                        record(SAMPLE_MS) { pcm ->
                            val embedding = withContext(Dispatchers.Default) { VoiceIdentity.embedSample(host, pcm) }
                            if (embedding == null) {
                                hint = "Не расслышал, повторите громче."
                            } else {
                                samples += embedding
                                if (samples.size >= needed) {
                                    withContext(Dispatchers.IO) { VoiceIdentity.save(host, samples.toList()) }
                                    samples.clear()
                                }
                            }
                        }
                    })
                }
                !VoiceIdentity.phrasesEnrolled(host) -> {
                    val all = VoiceIdentity.PHRASES
                    val title = if (recording) "Читайте вслух, 6 секунд" else "Фраза ${phrases.size + 1} из ${all.size}"
                    val sub = "«${all[phrases.size]}»" + (hint?.let { "\n$it" } ?: "")
                    box.addView(card(title, sub) {
                        if (recording) return@card
                        record(VoiceIdentity.PHRASE_MS) { pcm ->
                            val pieces = withContext(Dispatchers.Default) { VoiceIdentity.embedPhrase(host, pcm) }
                            if (pieces.isEmpty()) {
                                hint = "Не расслышал, повторите громче."
                            } else {
                                phrases += pieces
                                if (phrases.size >= all.size) {
                                    withContext(Dispatchers.IO) { VoiceIdentity.savePhrases(host, phrases.toList()) }
                                    phrases.clear()
                                }
                            }
                        }
                    })
                    box.addView(note("Фразы нужны, чтобы Джарвис принимал команды только от вас."))
                }
                else -> {
                    box.addView(toggle("Только мой голос", "Чужой голос не разбудит Джарвиса и не отдаст команду", JarvisSettings.voiceLock(host)) {
                        JarvisSettings.setVoiceLock(host, it)
                        VoiceIdentity.warm(host)
                    })
                    box.addView(note("Выучено на ваших командах: ${VoiceIdentity.learnedCount(host)}"))
                    box.addView(card("Записать голос заново", "Предыдущий образец будет заменён") {
                        VoiceIdentity.forget(host)
                        render()
                    })
                }
            }
        }
        render()
        val job = host.lifecycleScope.launch {
            host.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mgr.statuses.collect {
                    if (box.isAttachedToWindow && samples.isEmpty() && phrases.isEmpty() && !recording) render()
                }
            }
        }
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { job.cancel() }
        })
        return box
    }

    /** Смена слова активации применяется перезапуском голосового движка. */
    fun restartVoice() {
        if (VoiceController.isRunning) {
            VoiceController.stop(host)
            VoiceController.start(host)
        }
    }

    fun skins(): View {
        val row = host.rowLayout(6)
        val cur = Skins.current(host).id
        Skins.ALL.forEach { s ->
            val box = host.column().pad(2, 9).apply {
                gravity = Gravity.CENTER
                background = host.glassBg(this@SectionScreen.skin, 14, this@SectionScreen.skin.glass, if (s.id == cur) this@SectionScreen.skin.accent else this@SectionScreen.skin.line)
                addView(View(host).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(s.accent)
                        setStroke(1, this@SectionScreen.skin.line)
                    }
                }, lp(host.dp(24), host.dp(24)))
                addView(host.label(s.title, 10.5f, this@SectionScreen.skin.text, gravity = Gravity.CENTER, lines = 1).apply { setPadding(0, host.dp(6), 0, 0) })
                tap { Skins.set(host, s.id); host.recreate() }
            }
            row.addView(box, lp(0, -2, 1f))
        }
        return row
    }
}

private const val SAMPLE_MS = 2500

object Sections {
    private val ASR_MODES = listOf("AUTO", "CLOUD_PREFERRED", "LOCAL_ONLY")
    private val MODE_LABELS = listOf("Авто", "Облако", "Локально")
    private val PERSONA_LABELS = listOf("Джарвис", "Друг", "Кратко", "Инженер")
    private val PROFILE_LABELS = listOf("Быстро", "Баланс", "Качество")
    private val ROUTER_MODES = listOf("HYBRID", "LOCAL", "CLOUD")

    val ALL = listOf(
        MoreSection("Голос и слух", "Слово активации, распознавание, синтез", Ic.MIC) { b ->
            val c = app
            b.addView(head("Слово активации"))
            b.addView(toggle("Слово «Джарвис»", "Команда начинается с обращения", JarvisSettings.wakeEnabled(c)) { JarvisSettings.setWakeEnabled(c, it); restartVoice() })
            b.addView(options("Чувствительность", listOf("Низкая", "Средняя", "Высокая"), JarvisSettings.wakeSensitivity(c)) { JarvisSettings.setWakeSensitivity(c, it); restartVoice() })
            b.addView(voiceLock())
            b.addView(head("Разговор"))
            b.addView(toggle("Продолжать разговор", "Без повторного обращения", JarvisSettings.conversationMode(c)) { JarvisSettings.setConversationMode(c, it) })
            b.addView(pref("Подстраивать слух", "Адаптивное прослушивание", "adaptive_listening", true))
            b.addView(pref("Беречь батарею ниже 20%", "Экономный режим прослушивания", "low_power", false))
            b.addView(head("Распознавание речи"))
            b.addView(options("Режим", MODE_LABELS, ASR_MODES.indexOf(JarvisSettings.asrMode(c))) { JarvisSettings.setAsrMode(c, ASR_MODES[it]); BrainGraph.invalidate() })
            b.addView(urlField("Адрес облачного распознавания", JarvisSettings.asrCloudBaseUrl(c)) { JarvisSettings.setAsrCloudBaseUrl(c, it) })
            b.addView(keyRow("Ключ распознавания", "Для своего сервера", "custom_asr"))
            b.addView(head("Синтез речи"))
            b.addView(options("Режим", MODE_LABELS, ASR_MODES.indexOf(JarvisSettings.ttsMode(c))) { JarvisSettings.setTtsMode(c, ASR_MODES[it]); BrainGraph.invalidate() })
            b.addView(note("Голос Джарвиса, Dmitri запасной"))
            b.addView(urlField("Адрес облачного синтеза", JarvisSettings.ttsCloudBaseUrl(c)) { JarvisSettings.setTtsCloudBaseUrl(c, it) })
            b.addView(keyRow("Ключ синтеза", "Для своего сервера", "custom_tts"))
        },
        MoreSection("Характер и ответы", "Личность, скорость, уверенность", Ic.FACE) { b ->
            val c = app
            b.addView(head("Личность"))
            b.addView(options("", PERSONA_LABELS, JarvisSettings.PERSONAS.indexOf(JarvisSettings.persona(c))) { JarvisSettings.setPersona(c, JarvisSettings.PERSONAS[it]); BrainGraph.invalidate() })
            b.addView(note("Джарвис: дворецкий, обращение «сэр»"))
            b.addView(head("Ответ"))
            b.addView(options("Скорость и качество", PROFILE_LABELS, JarvisSettings.CHAT_PROFILES.indexOf(JarvisSettings.chatProfile(c))) { JarvisSettings.setChatProfile(c, JarvisSettings.CHAT_PROFILES[it]) })
            b.addView(head("Понимание"))
            b.addView(threshold())
        },
        MoreSection("ИИ и модели", "Облако, ключи, локальные модели", Ic.CHIP) { b ->
            val c = app
            b.addView(head("Где думать"))
            b.addView(options("Маршрут", listOf("Гибрид", "Локально", "Облако"), ROUTER_MODES.indexOf(JarvisSettings.routerMode(c))) { pick ->
                repeat(ROUTER_MODES.size) { if (JarvisSettings.routerMode(c) != ROUTER_MODES[pick]) JarvisSettings.cycleRouterMode(c) }
                BrainGraph.invalidate()
            })
            b.addView(toggle("Использовать ИИ-модель", "Иначе только правила и команды", JarvisSettings.llmEnabled(c)) { JarvisSettings.setLlmEnabled(c, it); BrainGraph.invalidate() })
            b.addView(toggle("Предпочитать облако", "Есть сеть и ключ: облако, иначе локально", JarvisSettings.preferCloudLlm(c)) { JarvisSettings.setPreferCloudLlm(c, it); BrainGraph.invalidate() })
            b.addView(toggle("Предзагрузка в память", "Модель готова до первой фразы", JarvisSettings.chatPreloadEnabled(c)) { JarvisSettings.setChatPreloadEnabled(c, it) })
            b.addView(head("Облачные ключи"))
            b.addView(keyRow("Groq", "Основная быстрая модель", "groq"))
            b.addView(keyRow("Gemini", "Запасная цепочка", "gemini"))
            b.addView(urlField("Адрес своего сервера", JarvisSettings.cloudBaseUrl(c)) { JarvisSettings.setCloudBaseUrl(c, it) })
            b.addView(keyRow("Свой сервер", "Совместимый с OpenAI", "custom"))
            b.addView(note("Личные данные в облако не отправляются"))
            b.addView(head("Локальные модели"))
            models(b)
        },
        MoreSection("Оформление", "Тема, звуки, плавающий орб", Ic.PAINT) { b ->
            val c = app
            b.addView(head("Интерфейс"))
            b.addView(options("Вид", listOf("Современный", "Классический"), 0) { picked ->
                if (picked == 1) UiMode.switchTo(c, UiStyle.CLASSIC)
            })
            b.addView(note("Классический: инженерная консоль. Вернуться можно в его «Профиле»."))
            b.addView(head("Тема"))
            b.addView(skins())
            b.addView(head("Звуки и орб"))
            b.addView(toggle("Звуки интерфейса", "Сигнал активации и эффекты", JarvisSettings.hudSfx(c)) { JarvisSettings.setHudSfx(c, it) })
            b.addView(pref("Орб прилипает к краю", "Плавающая кнопка поверх приложений", "companion_orb_snap", true))
        },
        MoreSection("Присутствие", "Подсказки, объявления, тихие часы", Ic.BELL) { b ->
            val c = app
            b.addView(head("Подсказки"))
            b.addView(pref("Проактивность", "Сам предлагает помощь", "companion_proactive", true))
            b.addView(pref("Фоновые уведомления", "Подсказки в шторке", "companion_bg_notify", true))
            b.addView(pref("Тихие часы", "С 23:00 до 07:00", "companion_quiet", false))
            b.addView(head("Объявления вслух"))
            b.addView(toggle("Звонки", "Кто звонит", JarvisSettings.announceCalls(c)) { JarvisSettings.setAnnounce(c, "calls", it) })
            b.addView(toggle("Сообщения", "Кроме групп и ночи", JarvisSettings.announceMessages(c)) { JarvisSettings.setAnnounce(c, "messages", it) })
            b.addView(toggle("Встречи и сводка дня", "За 15 минут и после будильника", JarvisSettings.announceHints(c)) { JarvisSettings.setAnnounce(c, "hints", it) })
            b.addView(head("Доступы"))
            b.addView(link("Цифровой помощник", "Выбрать Jarvis") { PermissionsHelper.openAssistantPickerSettings(c) })
            b.addView(link("Чтение уведомлений", "Системная настройка") { PermissionsHelper.openNotificationListenerSettings(c) })
            b.addView(link("Статистика приложений", "Системная настройка") { PermissionsHelper.openUsageAccessSettings(c) })
        },
        MoreSection("Память и безопасность", "Факты, подтверждения, данные", Ic.SHIELD) { b ->
            val c = app
            b.addView(head("Безопасность"))
            b.addView(pref("Подтверждать важные действия", "Звонки, сообщения, удаление", "confirm_sensitive", true))
            b.addView(note("Шифрование данных включено"))
            b.addView(head("Память"))
            b.addView(toggle("Запоминать факты", "Долгосрочная память на телефоне", JarvisSettings.memoryEnabled(c)) { JarvisSettings.setMemoryEnabled(c, it) })
            b.addView(link("Сохранённые факты", "${runCatching { BrainGraph.memory(c).count() }.getOrDefault(0)}, открыть список") { c.show(JarvisActivity.MEMORY) })
            b.addView(link("Очистить память", "Все факты будут удалены") {
                c.showConfirm("Все сохранённые факты будут удалены без возможности восстановления.", "Очистить память?") {
                    runCatching { BrainGraph.memory(c).clear() }
                }
            })
        },
        MoreSection("Помощь и диагностика", "Гид, консоль, разработка", Ic.HELP) { b ->
            val c = app
            b.addView(head("Помощь"))
            b.addView(link("Как пользоваться", "Короткий гид") { c.startActivity(Intent(c, UsageGuideActivity::class.java)) })
            b.addView(link("Текущая ситуация", "Что он сейчас видит") { c.show(JarvisActivity.SCAN) })
            b.addView(head("Для разработчика"))
            b.addView(link("Консоль", "Проверка команд") { c.startActivity(Intent(c, TestConsoleActivity::class.java)) })
        },
    )
}
