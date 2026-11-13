package com.jarvis.app

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.jarvis.core.agent.AgentShortcut
import com.jarvis.core.agent.AgentShortcuts
import com.jarvis.core.agent.AgentStep
import com.jarvis.core.agent.AppAgentRules
import com.jarvis.core.agent.RawNode
import com.jarvis.core.agent.ScreenItem
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.screencontrol.ScreenController
import com.jarvis.screencontrol.UiNode
import com.jarvis.tools.LauncherTool
import com.jarvis.tools.SystemSettingTool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Цикл «посмотрел, сделал, проверил». Текст экрана уходит в облачную модель, поэтому банки
 * и поля паролей отсекаются до отправки. Опасные кнопки нажимаются только после вашего «да».
 */
class AppAgentRunner(
    private val context: Context,
    private val ask: (String) -> String?,
) {
    private val launcher = LauncherTool(context)

    fun start(goal: String): String {
        shortcut(goal)?.let { return it }
        if (!ScreenController.isAvailable()) return "Для этого нужна служба спец. возможностей Jarvis, сэр."
        if (!running.compareAndSet(false, true)) return "Я уже занят задачей, сэр. Скажите «стоп», чтобы прервать."
        cancelled = false
        Thread({
            val reply = runCatching { work(goal) }.getOrElse {
                Log.e(TAG, "agent failed", it)
                VoiceHealthLog.write(context, "agent error ${it.javaClass.simpleName}")
                "Не получилось, сэр: произошла ошибка."
            }
            running.set(false)
            pending = null
            VoiceHealthLog.write(context, "agent finished")
            VoiceController.speak(reply)
        }, "jarvis-agent").start()
        return "Приступаю, сэр."
    }

    /** Прямой путь: приложение делает это само, без модели и угадывания. */
    private fun shortcut(goal: String): String? = when (val found = AgentShortcuts.match(goal)) {
        is AgentShortcut.YouTubeSearch -> runCatching {
            val search = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(YOUTUBE)
                putExtra(SearchManager.QUERY, found.query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(search)
            VoiceHealthLog.write(context, "agent shortcut youtube")
            "Открыл результаты, сэр."
        }.getOrNull()
        is AgentShortcut.Setting -> {
            val done = settings(found.name)
            if (done) VoiceHealthLog.write(context, "agent shortcut setting ${found.name}")
            if (done) "Открыл настройки, сэр." else null
        }
        is AgentShortcut.MapSearch -> open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(found.place)}")), "Открыл карту, сэр.")
        is AgentShortcut.WebSearch -> open(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/search/?text=${Uri.encode(found.query)}")),
            "Открыл поиск, сэр.",
        )
        null -> null
    }

    private fun open(intent: Intent, reply: String): String? = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        VoiceHealthLog.write(context, "agent shortcut view")
        reply
    }.getOrNull()

    private fun settings(name: String): Boolean {
        val call = ToolCall(
            requestId = "agent",
            toolId = "system.setting",
            arguments = mapOf("name" to name, "value" to "open"),
        )
        return runCatching { SystemSettingTool(context).execute(call).status == ToolResultStatus.SUCCESS }.getOrDefault(false)
    }

    private fun work(goal: String): String {
        val history = ArrayList<String>()
        val guard = AppAgentRules.LoopGuard()
        var rejected = 0
        var typed = false
        var blockedSteps = 0
        var lastStep: AgentStep? = null
        val queries = HashSet<String>()
        var lastScreen: List<ScreenItem> = emptyList()
        // Свой экран агенту не нужен: иначе модель начинает нажимать кнопки самого Джарвиса.
        if (ScreenController.activePackageName() == context.packageName) ScreenController.globalAction("home")
        Thread.sleep(START_PAUSE_MS)
        // План заранее: с ним модель не забывает вторую половину задачи.
        val plan = ask(AppAgentRules.planPrompt(goal, ScreenController.activePackageName().orEmpty()))
            ?.let(AppAgentRules::parsePlan).orEmpty()
        VoiceHealthLog.write(context, "agent plan ${plan.size}: ${plan.joinToString(" / ").take(RAW_LOG_CHARS)}")
        var verifyRejected = 0
        var lastLeft = ""
        var unchangedSteps = 0
        /** На прошлом круге действительно что-то сделано (а не только подсказка модели). */
        var acted = false
        for (number in 1..AppAgentRules.MAX_STEPS) {
            if (cancelled) return "Остановился, сэр."
            val packageName = ScreenController.activePackageName().orEmpty()
            if (AppAgentRules.forbiddenApp(packageName)) return "В банковских приложениях я не работаю, сэр."
            val all = ScreenController.visibleNodes(MAX_NODES)
            val rows = AppAgentRules.compact(all.map(::raw))
            val screen = rows.map { it.item }
            val nodes = rows.map { all[it.source] }
            if (plan.size <= 1 && AppAgentRules.resultOpened(goal, screen, lastScreen, typed && lastStep is AgentStep.Tap, queries)) {
                return "Готово, сэр."
            }
            // Enter срабатывает не везде: тогда запрос запускается нажатием на подсказку.
            if (acted && lastStep is AgentStep.Enter && screen == lastScreen) {
                history += "ENTER не сработал, нажми подходящую подсказку из списка"
            } else if (acted && lastStep !is AgentStep.Open && screen == lastScreen) {
                // Шаг ничего не изменил: модели надо знать, иначе она повторит его снова.
                history += "экран не изменился после шага, попробуй другой путь"
                if (++unchangedSteps > MAX_UNCHANGED_STEPS) return partial("экран перестал меняться", lastLeft, history)
            } else if (acted) {
                unchangedSteps = 0
            }
            acted = false
            lastScreen = screen
            val answer = askRetrying(AppAgentRules.prompt(goal, packageName, screen, history, typed, plan))
            if (answer == null) {
                VoiceHealthLog.write(context, "agent no answer step $number items=${screen.size}")
                return partial("облако перестало отвечать", lastLeft, history)
            }
            if (cancelled) return "Остановился, сэр."
            var step = AppAgentRules.parse(answer, screen)
            var reply: String = answer
            for (attempt in 1..MAX_FORMAT_RETRIES) {
                if (step != null) break
                // Ответ не по формату: напоминаем формат, а не сдаёмся.
                Log.w(TAG, "шаг $number: не разобран ответ «${reply.take(RAW_LOG_CHARS)}»")
                VoiceHealthLog.write(context, "agent unparsed step $number: ${reply.take(RAW_LOG_CHARS).replace('\n', ' ')}")
                reply = askRetrying(
                    AppAgentRules.prompt(goal, packageName, screen, history + AppAgentRules.formatReminder(reply), typed, plan),
                ) ?: break
                step = AppAgentRules.parse(reply, screen)
            }
            Log.i(TAG, "шаг $number: ${step?.let { describe(it, screen) }} (на экране ${screen.size})")
            VoiceHealthLog.write(context, "agent step $number ${step?.javaClass?.simpleName}")
            if (step == null) {
                // Модель так и не дала команду: шаг пропускаем, задачу не бросаем.
                if (++blockedSteps > MAX_BLOCKED_STEPS + MAX_FORMAT_RETRIES) return partial("модель перестала давать понятные шаги", lastLeft, history)
                history += "предыдущий ответ не был командой: ответь одной командой из списка"
                continue
            }
            if (step is AgentStep.Done) {
                // Сначала дешёвая проверка: явно незаконченный поиск.
                if (AppAgentRules.mustSearch(goal, screen, typed) && rejected < MAX_REJECTED_DONE) {
                    rejected++
                    history += "DONE рано: сначала воспользуйся поиском"
                    continue
                }
                val verdict = askRetrying(AppAgentRules.verifyPrompt(goal, plan, packageName, screen, history))
                    ?.let(AppAgentRules::parseVerify)
                VoiceHealthLog.write(context, "agent verify ${verdict?.first} ${verdict?.second.orEmpty().take(80)}")
                // Проверка недоступна — верим модели, но сказанному «готово» без проверки.
                if (verdict == null || verdict.first) return step.reply.ifBlank { "Готово, сэр." }
                lastLeft = verdict.second
                if (++verifyRejected > MAX_VERIFY_REJECTED) return partial("цель выполнена не полностью", lastLeft, history)
                history += "DONE отклонён проверкой: " + lastLeft.ifBlank { "цель выполнена не полностью" } + ". Продолжай шагами"
                continue
            }
            AppAgentRules.blocked(step, screen)?.let { why ->
                // Пароли не вводим никогда; на остальное даём модели исправиться (слабые модели путают табло с полем).
                if (step is AgentStep.Type && screen.getOrNull(step.index)?.password == true) return "Остановился, сэр: $why."
                if (++blockedSteps > MAX_BLOCKED_STEPS) return "Остановился, сэр: $why."
                history += AppAgentRules.blockedHint(step, why)
                continue
            }
            if (step is AgentStep.Tap && AppAgentRules.unwantedShorts(goal, screen[step.index].label)) {
                history += "Shorts не подходит, нажми обычное видео"
                continue
            }
            // Строка поиска есть и на странице результатов: второй поиск ничего не даст.
            if (typed && step is AgentStep.Tap &&
                (step.index == AppAgentRules.searchIndex(screen) || screen[step.index].label in queries)
            ) {
                history += "повторный поиск запрещён, нажми результат"
                continue
            }
            when (guard.count(screen, step)) {
                1 -> Unit
                // Первый повтор — подсказка искать другой путь, второй — остановка.
                2 -> {
                    history += "${describe(step, screen)} на этом экране уже было и не помогло, выбери другой шаг (SCROLL, BACK, поиск)"
                    continue
                }
                else -> return partial("хожу по кругу", lastLeft, history)
            }
            show("Шаг $number из ${AppAgentRules.MAX_STEPS}: ${describe(step, screen)}")
            if (step is AgentStep.Tap && AppAgentRules.needsConfirmation(screen[step.index].label)) {
                if (!confirmed("Нажать «${screen[step.index].label}», сэр?")) return "Хорошо, сэр, не нажимаю."
            }
            if (step is AgentStep.Enter && AppAgentRules.enterIsRisky(screen)) {
                if (!confirmed("Отправить введённое, сэр?")) return "Хорошо, сэр, не отправляю."
            }
            perform(step, nodes)
            acted = true
            lastStep = step
            if (step is AgentStep.Type) queries += step.text
            if (step is AgentStep.Tap && screen[step.index].label.length <= MAX_QUERY && typed) queries += screen[step.index].label
            typed = typed || step is AgentStep.Type
            history += describe(step, screen)
            Thread.sleep(STEP_PAUSE_MS)
        }
        return partial("не уложился в ${AppAgentRules.MAX_STEPS} шагов", lastLeft, history)
    }

    /** Честный итог незаконченной задачи: что сделано и что осталось. */
    private fun partial(why: String, left: String, history: List<String>): String {
        VoiceHealthLog.write(context, "agent partial: $why")
        val done = history.count { !it.contains("отклон") && !it.contains("не изменился") && !it.contains("не был командой") && !it.contains("уже было") }
        val rest = if (left.isNotBlank()) " Осталось: $left." else ""
        return "Остановился, сэр: $why. Сделал шагов: $done.$rest"
    }

    /** Бесплатные ключи ограничены по частоте: короткий сбой не должен обрывать задачу. */
    private fun askRetrying(prompt: String): String? {
        for ((i, pause) in RETRY_PAUSES_MS.withIndex()) {
            if (cancelled) return null
            if (i > 0) Thread.sleep(pause)
            runCatching { ask(prompt) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun raw(node: UiNode) = RawNode(
        text = if (node.password) "" else node.text.ifBlank { node.contentDescription },
        left = node.bounds.left,
        top = node.bounds.top,
        right = node.bounds.right,
        bottom = node.bounds.bottom,
        clickable = node.clickable,
        editable = node.editable,
        password = node.password,
    )

    private fun describe(step: AgentStep, screen: List<ScreenItem>): String = when (step) {
        is AgentStep.Tap -> "нажал «${screen.getOrNull(step.index)?.label.orEmpty()}»"
        is AgentStep.Type -> "ввёл текст"
        is AgentStep.Open -> "открыл ${step.app}"
        is AgentStep.Scroll -> "пролистал"
        AgentStep.Back -> "назад"
        AgentStep.Enter -> "нажал ввод"
        is AgentStep.Done -> "готово"
    }

    private fun perform(step: AgentStep, nodes: List<UiNode>) {
        when (step) {
            // Настоящее касание: программный клик видеолента YouTube часто игнорирует.
            is AgentStep.Tap -> nodes[step.index].bounds.let {
                ScreenController.tap(it.exactCenterX(), it.exactCenterY())
            }
            is AgentStep.Type -> ScreenController.setTextOnNode(nodes[step.index], step.text)
            is AgentStep.Open -> launcher.execute(
                ToolCall(requestId = "agent", toolId = "launcher", arguments = mapOf("action" to "open", "app" to step.app)),
            ).also { if (it.status != ToolResultStatus.SUCCESS) Log.w(TAG, "не открылось: ${step.app}") }
            is AgentStep.Scroll -> ScreenController.swipeDirection(if (step.down) "up" else "down")
            AgentStep.Back -> ScreenController.globalAction("back")
            AgentStep.Enter -> ScreenController.pressEnter()
            is AgentStep.Done -> Unit
        }
    }

    /** Вопрос голосом; без ответа считается «нет». */
    private fun confirmed(question: String): Boolean {
        val latch = CountDownLatch(1)
        val answer = Answer(latch)
        pending = answer
        VoiceController.speak(question, onComplete = {
            VoiceController.listenForFollowUp(CONFIRM_WAIT_MS)
        })
        latch.await(CONFIRM_WAIT_MS + SPEECH_MARGIN_MS, TimeUnit.MILLISECONDS)
        pending = null
        return answer.yes
    }

    private fun show(text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private class Answer(val latch: CountDownLatch) {
        @Volatile var yes = false
    }

    companion object {
        private const val MAX_BLOCKED_STEPS = 2

        private const val RAW_LOG_CHARS = 160
        private const val TAG = "Jarvis.Agent"
        private const val YOUTUBE = "com.google.android.youtube"
        private const val START_PAUSE_MS = 1_500L
        private const val STEP_PAUSE_MS = 1_500L
        private const val CONFIRM_WAIT_MS = 10_000L
        private const val SPEECH_MARGIN_MS = 6_000L
        private const val MAX_NODES = 300
        private const val MAX_REJECTED_DONE = 2
        private const val MAX_QUERY = 40
        private val RETRY_PAUSES_MS = longArrayOf(0L, 2_000L, 5_000L)
        private const val MAX_FORMAT_RETRIES = 2
        private const val MAX_VERIFY_REJECTED = 3
        private const val MAX_UNCHANGED_STEPS = 4
        private val STOP = Regex("^(стоп|хватит|отмена|отмени|останови(сь)?|прекрати)\\b")
        private val YES = Regex("^(да|ага|нажми|подтверждаю|давай)\\b")

        private val running = AtomicBoolean(false)
        @Volatile private var cancelled = false
        @Volatile private var pending: Answer? = null

        /** true: реплику забрал агент, обычным командам она не идёт. */
        fun consume(text: String): Boolean {
            if (!running.get()) return false
            val said = text.lowercase().trim()
            pending?.let { answer ->
                answer.yes = YES.containsMatchIn(said)
                answer.latch.countDown()
                return true
            }
            if (!STOP.containsMatchIn(said)) return false
            cancelled = true
            return true
        }
    }
}
