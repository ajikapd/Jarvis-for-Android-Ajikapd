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
        if (!running.compareAndSet(false, true)) return "Я уже занят задачей, сэр. Скажите «стоп», чтобы прервать."
        cancelled = false
        Thread({
            val reply = runCatching {
                shortcut(goal) ?: if (ScreenController.isAvailable()) work(goal)
                else "Для этой задачи нужна служба спец. возможностей Jarvis, сэр."
            }.getOrElse {
                Log.e(TAG, "agent failed", it)
                VoiceHealthLog.write(context, "agent error ${it.javaClass.simpleName}")
                "Не получилось, сэр: произошла ошибка."
            }
            pending = null
            running.set(false)
            VoiceHealthLog.write(context, "agent finished")
            VoiceController.speak(reply)
        }, "jarvis-agent").start()
        return "Приступаю, сэр."
    }

    /** Прямой путь: приложение делает это само, без модели и угадывания. */
    private fun shortcut(goal: String): String? = when (val found = AgentShortcuts.match(goal)) {
        is AgentShortcut.YouTubeSearch -> searchYouTube(found.query)
        is AgentShortcut.Setting -> {
            val done = if (found.name == "hotspot") {
                open(Intent("android.settings.TETHER_SETTINGS"), "Открыл точку доступа, сэр.") != null
            } else settings(found.name)
            if (done) VoiceHealthLog.write(context, "agent shortcut setting ${found.name}")
            when {
                done && found.name == "hotspot" -> "Открыл точку доступа, сэр."
                done -> "Открыл настройки, сэр."
                found.name == "hotspot" -> "Не удалось открыть раздел точки доступа, сэр."
                else -> null
            }
        }
        is AgentShortcut.MapSearch -> open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(found.place)}")), "Открыл карту, сэр.")
        is AgentShortcut.WebSearch -> open(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/search/?text=${Uri.encode(found.query)}")),
            "Открыл поиск, сэр.",
        )
        null -> null
    }

    private fun searchYouTube(query: String): String? {
        val active = ScreenController.activePackageName()?.takeIf { it in YOUTUBE_CLIENTS }
        val clients = (listOfNotNull(active) + YOUTUBE_CLIENTS).distinct()
        val url = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        for (client in clients) {
            val search = Intent(Intent.ACTION_SEARCH).setPackage(client)
                .putExtra(SearchManager.QUERY, query)
            val reply = "Открыл результаты поиска на YouTube, сэр."
            open(search, reply)?.let { return it }
            open(Intent(Intent.ACTION_VIEW, url).setPackage(client), reply)?.let { return it }
        }
        return open(Intent(Intent.ACTION_VIEW, url), "Открыл поиск YouTube в браузере, сэр.")
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
        val savedChatLookup = AppAgentRules.isSavedChatLookup(goal)
        val guard = AppAgentRules.LoopGuard()
        var searched = false
        var previousPackage = ""
        var successfulSteps = 0
        var blockedSteps = 0
        var lastStep: AgentStep? = null
        var lastScreen: List<ScreenItem> = emptyList()
        // Свой экран агенту не нужен: иначе модель начинает нажимать кнопки самого Джарвиса.
        if (ScreenController.activePackageName() == context.packageName) ScreenController.globalAction("home")
        Thread.sleep(START_PAUSE_MS)
        // План заранее: с ним модель не забывает вторую половину задачи.
        var plan = askRetrying(AppAgentRules.planPrompt(goal, ScreenController.activePackageName().orEmpty()))
            ?.let(AppAgentRules::parsePlan).orEmpty()
        VoiceHealthLog.write(context, "agent plan items=${plan.size}")
        var verifyRejected = 0
        var lastLeft = ""
        var recoveries = 0
        var recoveredSavedAttachments = false
        var unchangedSteps = 0
        /** На прошлом круге действительно что-то сделано (а не только подсказка модели). */
        var acted = false
        fun recover(packageName: String): Boolean {
            if (recoveries >= MAX_RECOVERIES || cancelled) return false
            recoveries++
            val revised = askRetrying(AppAgentRules.planPrompt(goal, packageName, history))
                ?.let(AppAgentRules::parsePlan).orEmpty()
            if (revised.isNotEmpty()) plan = revised
            VoiceHealthLog.write(context, "agent replanned attempt=$recoveries")
            return !cancelled
        }

        for (number in 1..AppAgentRules.MAX_STEPS) {
            if (cancelled) return "Остановился, сэр."
            val packageName = ScreenController.activePackageName().orEmpty()
            if (AppAgentRules.forbiddenApp(packageName)) return "В банковских приложениях я не работаю, сэр."
            val all = ScreenController.visibleNodes(MAX_NODES)
            if (all.isEmpty() || all.first().packageName != packageName) {
                Thread.sleep(SCREEN_RETRY_MS)
                continue
            }
            if (all.any { it.password }) return partial("на экране открыто поле пароля", lastLeft, successfulSteps)
            val rows = AppAgentRules.compact(all.map(::raw))
            val screen = rows.map { it.item }
            val nodes = rows.map { all[it.source] }
            if (savedChatLookup &&
                packageName in com.jarvis.core.common.AppFamilies.TELEGRAM &&
                AppAgentRules.savedChatOpen(screen)
            ) {
                VoiceHealthLog.write(context, "agent verified saved chat")
                return "Открыл «Избранное», сэр."
            }
            if (savedChatLookup && !recoveredSavedAttachments &&
                packageName in com.jarvis.core.common.AppFamilies.TELEGRAM &&
                AppAgentRules.savedChatAttachmentsOpen(screen)
            ) {
                recoveredSavedAttachments = true
                if (cancelled) return "Остановился, сэр."
                if (ScreenController.activePackageName() == packageName && ScreenController.globalAction("back")) {
                    history += "вернулся из вложений «Избранного». Открой беседу через список чатов или глобальный поиск"
                    VoiceHealthLog.write(context, "agent backed out of saved chat attachments")
                    Thread.sleep(STEP_PAUSE_MS)
                    continue
                }
            }
            if (previousPackage != packageName ||
                (searched && AppAgentRules.searchIndex(screen) == null && screen.any { it.editable })
            ) {
                searched = false
            }
            previousPackage = packageName
            // Enter срабатывает не везде: тогда запрос запускается нажатием на подсказку.
            if (acted && lastStep is AgentStep.Enter && screen == lastScreen) {
                history += "ENTER не сработал, нажми подходящую подсказку из списка"
            } else if (acted && lastStep !is AgentStep.Open && screen == lastScreen) {
                // Шаг ничего не изменил: модели надо знать, иначе она повторит его снова.
                history += "экран не изменился после шага, попробуй другой путь"
                if (++unchangedSteps > MAX_UNCHANGED_STEPS) {
                    if (!recover(packageName)) return partial("экран перестал меняться", lastLeft, successfulSteps)
                    unchangedSteps = 0
                    acted = false
                    continue
                }
            } else if (acted) {
                unchangedSteps = 0
            }
            acted = false
            lastScreen = screen
            val answer = askRetrying(AppAgentRules.prompt(goal, packageName, screen, history, searched, plan))
            if (answer == null) {
                VoiceHealthLog.write(context, "agent no answer step $number items=${screen.size}")
                return partial("облако перестало отвечать", lastLeft, successfulSteps)
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
                    AppAgentRules.prompt(goal, packageName, screen, history + AppAgentRules.formatReminder(reply), searched, plan),
                ) ?: break
                step = AppAgentRules.parse(reply, screen)
            }
            Log.i(TAG, "шаг $number: ${step?.let { describe(it, screen) }} (на экране ${screen.size})")
            VoiceHealthLog.write(context, "agent step $number ${step?.javaClass?.simpleName}")
            if (step == null) {
                // Модель так и не дала команду: шаг пропускаем, задачу не бросаем.
                if (++blockedSteps > MAX_BLOCKED_STEPS + MAX_FORMAT_RETRIES) return partial("модель перестала давать понятные шаги", lastLeft, successfulSteps)
                history += "предыдущий ответ не был командой: ответь одной командой из списка"
                continue
            }
            if (step is AgentStep.Done) {
                if (savedChatLookup) {
                    lastLeft = "открыть чат «Избранное»"
                    if (++verifyRejected > MAX_VERIFY_REJECTED) {
                        return partial("не подтвердил открытие нужного чата", lastLeft, successfulSteps)
                    }
                    history += "Чат «Избранное» не подтверждён. Если открыт другой чат, вернись к списку и открой «Избранное» через меню."
                    continue
                }
                val verdict = askRetrying(AppAgentRules.verifyPrompt(goal, plan, packageName, screen, history))
                    ?.let(AppAgentRules::parseVerify)
                VoiceHealthLog.write(context, "agent verify ${verdict?.first} ${verdict?.second.orEmpty().take(80)}")
                if (cancelled) return "Остановился, сэр."
                if (verdict?.first == true) return step.reply.ifBlank { "Готово, сэр." }
                if (verdict == null) return partial("не удалось проверить результат", lastLeft, successfulSteps)
                lastLeft = verdict.second
                if (++verifyRejected > MAX_VERIFY_REJECTED) return partial("цель выполнена не полностью", lastLeft, successfulSteps)
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
            when (guard.count(screen, step, packageName)) {
                1 -> Unit
                // Первый повтор — подсказка искать другой путь, второй — остановка.
                2 -> {
                    history += "${describe(step, screen)} на этом экране уже было и не помогло, выбери другой шаг (SCROLL, BACK, поиск)"
                    if (recover(packageName)) continue
                    return partial("повторяется действие без продвижения", lastLeft, successfulSteps)
                }
                else -> {
                    if (recover(packageName)) continue
                    return partial("хожу по кругу", lastLeft, successfulSteps)
                }
            }
            show("Шаг $number из ${AppAgentRules.MAX_STEPS}: ${describe(step, screen)}")
            if (step is AgentStep.Tap && (AppAgentRules.needsConfirmation(screen[step.index].label) ||
                    AppAgentRules.needsConfirmation(nodes[step.index].contentDescription))
            ) {
                val label = nodes[step.index].contentDescription.ifBlank { screen[step.index].label }
                if (!confirmed("Нажать «$label», сэр?")) return "Хорошо, сэр, не нажимаю."
            }
            if (step is AgentStep.Enter && AppAgentRules.enterIsRisky(screen)) {
                if (!confirmed("Отправить введённое, сэр?")) return "Хорошо, сэр, не отправляю."
            }
            if (cancelled) return "Остановился, сэр."
            if (ScreenController.activePackageName().orEmpty() != packageName) {
                history += "открыто другое приложение, перечитай экран"
                continue
            }
            val target = when (step) {
                is AgentStep.Tap -> nodes[step.index]
                is AgentStep.Type -> nodes[step.index]
                else -> null
            }
            if (target != null && ScreenController.visibleNodes(MAX_NODES).none { it.enabled && it == target }) {
                history += "элемент изменился или недоступен, перечитай экран"
                continue
            }
            acted = perform(step, nodes)
            lastStep = step
            if (acted) {
                successfulSteps++
                if (step is AgentStep.Type && AppAgentRules.isSearchField(screen[step.index])) searched = true
                history += describe(step, screen)
            } else {
                history += "действие не сработало: ${describe(step, screen)}. Попробуй другой шаг."
            }
            Thread.sleep(STEP_PAUSE_MS)
        }
        return partial("не уложился в ${AppAgentRules.MAX_STEPS} шагов", lastLeft, successfulSteps)
    }

    /** Честный итог незаконченной задачи: что сделано и что осталось. */
    private fun partial(why: String, left: String, done: Int): String {
        VoiceHealthLog.write(context, "agent partial: $why")
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
        text = AppAgentRules.screenLabel(
            node.text, node.contentDescription, node.hint, node.editable, node.password,
        ),
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

    private fun perform(step: AgentStep, nodes: List<UiNode>): Boolean = when (step) {
        is AgentStep.Tap -> ScreenController.tapCenter(nodes[step.index])
        is AgentStep.Type -> ScreenController.setTextOnNode(nodes[step.index], step.text)
        is AgentStep.Open -> launcher.execute(
            ToolCall(requestId = "agent", toolId = "launcher", arguments = mapOf("action" to "open", "app" to step.app)),
        ).status == ToolResultStatus.SUCCESS
        is AgentStep.Scroll -> ScreenController.swipeDirection(if (step.down) "up" else "down")
        AgentStep.Back -> ScreenController.globalAction("back")
        AgentStep.Enter -> ScreenController.pressEnter()
        is AgentStep.Done -> false
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
        private val YOUTUBE_CLIENTS = listOf("com.google.android.youtube", "app.morphe.android.youtube")
        private const val START_PAUSE_MS = 1_500L
        private const val STEP_PAUSE_MS = 1_500L
        private const val CONFIRM_WAIT_MS = 10_000L
        private const val SPEECH_MARGIN_MS = 6_000L
        private const val MAX_NODES = 300
        private val RETRY_PAUSES_MS = longArrayOf(0L, 2_000L, 5_000L)
        private const val MAX_FORMAT_RETRIES = 2
        private const val MAX_VERIFY_REJECTED = 3
        private const val MAX_UNCHANGED_STEPS = 4
        private const val MAX_RECOVERIES = 2
        private const val SCREEN_RETRY_MS = 400L
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
