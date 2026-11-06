package com.jarvis.app

import android.app.SearchManager
import android.content.Context
import android.content.Intent
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
        null -> null
    }

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
        var lastStep: AgentStep? = null
        val queries = HashSet<String>()
        var lastScreen: List<ScreenItem> = emptyList()
        // Свой экран агенту не нужен: иначе модель начинает нажимать кнопки самого Джарвиса.
        if (ScreenController.activePackageName() == context.packageName) ScreenController.globalAction("home")
        Thread.sleep(START_PAUSE_MS)
        for (number in 1..AppAgentRules.MAX_STEPS) {
            if (cancelled) return "Остановился, сэр."
            val packageName = ScreenController.activePackageName().orEmpty()
            if (AppAgentRules.forbiddenApp(packageName)) return "В банковских приложениях я не работаю, сэр."
            val all = ScreenController.visibleNodes(MAX_NODES)
            val rows = AppAgentRules.compact(all.map(::raw))
            val screen = rows.map { it.item }
            val nodes = rows.map { all[it.source] }
            if (AppAgentRules.resultOpened(goal, screen, lastScreen, typed && lastStep is AgentStep.Tap, queries)) {
                return "Готово, сэр."
            }
            // Enter срабатывает не везде: тогда запрос запускается нажатием на подсказку.
            if (lastStep is AgentStep.Enter && screen == lastScreen) {
                history += "ENTER не сработал, нажми подходящую подсказку из списка"
            }
            lastScreen = screen
            val answer = askTwice(AppAgentRules.prompt(goal, packageName, screen, history, typed))
            if (answer == null) {
                VoiceHealthLog.write(context, "agent no answer step $number items=${screen.size}")
                return "Облако не ответило, сэр."
            }
            if (cancelled) return "Остановился, сэр."
            val step = AppAgentRules.parse(answer)
            Log.i(TAG, "шаг $number: ${step?.javaClass?.simpleName}")
            VoiceHealthLog.write(context, "agent step $number ${step?.javaClass?.simpleName}")
            if (step == null) return "Я не понял, что делать дальше, сэр."
            if (step is AgentStep.Done) {
                val finished = AppAgentRules.goalVisible(goal, screen) && !AppAgentRules.mustSearch(goal, screen, typed)
                if (rejected >= MAX_REJECTED_DONE || finished) {
                    return step.reply.ifBlank { "Готово, сэр." }
                }
                rejected++
                history += "DONE отклонён: цель не выполнена, воспользуйся поиском или продолжай шагами"
                continue
            }
            AppAgentRules.blocked(step, screen)?.let { return "Остановился, сэр: $it." }
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
            if (guard.repeated(screen, step)) return "Я хожу по кругу, сэр, поэтому остановился."
            show("Шаг $number из ${AppAgentRules.MAX_STEPS}: ${describe(step, screen)}")
            if (step is AgentStep.Tap && AppAgentRules.needsConfirmation(screen[step.index].label)) {
                if (!confirmed("Нажать «${screen[step.index].label}», сэр?")) return "Хорошо, сэр, не нажимаю."
            }
            if (step is AgentStep.Enter && AppAgentRules.enterIsRisky(screen)) {
                if (!confirmed("Отправить введённое, сэр?")) return "Хорошо, сэр, не отправляю."
            }
            perform(step, nodes)
            lastStep = step
            if (step is AgentStep.Type) queries += step.text
            if (step is AgentStep.Tap && screen[step.index].label.length <= MAX_QUERY && typed) queries += screen[step.index].label
            typed = typed || step is AgentStep.Type
            history += describe(step, screen)
            Thread.sleep(STEP_PAUSE_MS)
        }
        return "Не уложился в ${AppAgentRules.MAX_STEPS} шагов, сэр, и остановился."
    }

    /** Бесплатные ключи ограничены по частоте: короткий сбой не должен обрывать задачу. */
    private fun askTwice(prompt: String): String? {
        ask(prompt)?.takeIf { it.isNotBlank() }?.let { return it }
        Thread.sleep(RETRY_PAUSE_MS)
        return ask(prompt)?.takeIf { it.isNotBlank() }
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
        VoiceController.speak(question) {
            VoiceController.listenForFollowUp(CONFIRM_WAIT_MS)
        }
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
        private const val TAG = "Jarvis.Agent"
        private const val YOUTUBE = "com.google.android.youtube"
        private const val START_PAUSE_MS = 1_500L
        private const val STEP_PAUSE_MS = 1_500L
        private const val CONFIRM_WAIT_MS = 10_000L
        private const val SPEECH_MARGIN_MS = 6_000L
        private const val MAX_NODES = 300
        private const val MAX_REJECTED_DONE = 2
        private const val MAX_QUERY = 40
        private const val RETRY_PAUSE_MS = 3_000L
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
