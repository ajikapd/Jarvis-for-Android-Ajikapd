package com.jarvis.app

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
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
import com.jarvis.core.agent.TelegramAgentScreen
import com.jarvis.core.common.AppFamilies
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
        is AgentShortcut.YouTubeSearch ->
            if (found.playFirst) playFirstYouTube(found.query) else searchYouTube(found.query)
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
        is AgentShortcut.DialDraft -> dial(found.number)
        is AgentShortcut.Timer -> startTimer(found.seconds)
        is AgentShortcut.CalculatorSum -> calculate(found.left, found.right)
        is AgentShortcut.OpenFirstResult -> openFirstResult(found.query)
        null -> null
    }

    private fun dial(number: String): String? = open(
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
        "Набрал номер, сэр. Звонок не начинал.",
    )

    private fun startTimer(seconds: Int): String? = open(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "проверка"),
        "Поставил таймер, сэр.",
    )

    private fun calculate(left: Int, right: Int): String? {
        val launch = context.packageManager.getLaunchIntentForPackage("com.miui.calculator")
            ?: return open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$left%2B$right")), "Посчитал в браузере, сэр.")
        if (open(launch, "opened") == null) return null
        if (!ScreenController.isAvailable()) return "Открыл калькулятор, сэр. Чтобы нажать кнопки, нужна служба спец. возможностей."
        Thread.sleep(START_PAUSE_MS)
        val expression = "com.miui.calculator:id/expression"
        val delete = "com.miui.calculator:id/btn_del_s"
        val current = calculatorExpression(expression).orEmpty()
        // Не продолжаем вычисление поверх старого результата или введённого выражения.
        repeat(current.filter { it.isDigit() || it in "+−-×÷.," }.length.coerceAtMost(24)) {
            if (calculatorExpression(expression).orEmpty().filter(Char::isDigit) == "0") return@repeat
            if (!tapCalculatorId(delete)) return "Открыл калькулятор, но не смог очистить прошлое выражение, сэр."
            Thread.sleep(100)
        }
        val keys = left.toString().map { "com.miui.calculator:id/digit_$it" } +
            "com.miui.calculator:id/op_add" + right.toString().map { "com.miui.calculator:id/digit_$it" } +
            "com.miui.calculator:id/btn_equal_s"
        for (key in keys) {
            if (cancelled) return "Остановился, сэр."
            if (!tapCalculatorId(key)) {
                VoiceHealthLog.write(context, "agent calculator missed $key")
                return "Открыл калькулятор, но не нашёл нужную кнопку, сэр."
            }
            Thread.sleep(250)
        }
        val expected = (left + right).toString()
        val actual = calculatorExpression("com.miui.calculator:id/result")?.filter { it.isDigit() || it == '-' }
        if (actual != expected) {
            VoiceHealthLog.write(context, "agent calculator mismatch expected=$expected actual=${actual.orEmpty()}")
            return "Ввёл пример, но не смог подтвердить ответ калькулятора, сэр."
        }
        VoiceHealthLog.write(context, "agent calculator ${left}+$right")
        return "Проверил на калькуляторе: $left плюс $right равно $expected, сэр."
    }

    private fun tapCalculatorId(viewId: String): Boolean {
        val nodes = ScreenController.visibleNodes(MAX_NODES)
        val target = nodes.firstOrNull { it.enabled && it.viewId == viewId } ?: return false
        return ScreenController.tapCenter(target)
    }

    private fun calculatorExpression(viewId: String): String? =
        ScreenController.visibleNodes(MAX_NODES).firstOrNull { it.viewId == viewId }?.text

    private fun openFirstResult(query: String): String? {
        val uri = Uri.parse("https://yandex.ru/search/").buildUpon()
            .appendQueryParameter("text", query)
            .build()
        if (open(Intent(Intent.ACTION_VIEW, uri).setPackage("com.yandex.browser"), "opened") == null &&
            open(Intent(Intent.ACTION_VIEW, uri), "opened") == null
        ) return null
        if (!ScreenController.isAvailable()) return "Открыл поиск, сэр. Чтобы открыть первый результат, нужна служба спец. возможностей."
        Thread.sleep(START_PAUSE_MS)
        repeat(12) {
            if (cancelled) return "Остановился, сэр."
            val nodes = ScreenController.visibleNodes(MAX_NODES)
            val raw = nodes.map { node ->
                RawNode(
                    text = listOf(node.text, node.contentDescription).filter { it.isNotBlank() }.joinToString(" "),
                    left = node.bounds.left,
                    top = node.bounds.top,
                    right = node.bounds.right,
                    bottom = node.bounds.bottom,
                    clickable = node.clickable && node.enabled,
                    editable = node.editable,
                )
            }
            val card = AppAgentRules.firstSearchResult(raw)
            val target = card?.let { nodes.getOrNull(raw.indexOf(it)) }
            if (target != null && ScreenController.tapCenter(target)) {
                VoiceHealthLog.write(context, "agent first result")
                return "Открыл первый результат, сэр."
            }
            Thread.sleep(SCREEN_RETRY_MS)
        }
        VoiceHealthLog.write(context, "agent first result missing")
        return "Открыл поиск, но первый результат не нашёл, сэр."
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

    /**
     * Поиск с фильтром «видео» и нажатие первой широкой карточки.
     * Так не открывается полка Shorts и не начинается угадывание по пустым кнопкам.
     */
    private fun playFirstYouTube(query: String): String? {
        val uri = Uri.parse("https://www.youtube.com/results").buildUpon()
            .appendQueryParameter("search_query", query)
            .appendQueryParameter("sp", "EgIQAQ==")
            .build()
        val view = Intent(Intent.ACTION_VIEW, uri)
        val openedInApp = YOUTUBE_CLIENTS.any { client ->
            open(Intent(view).setPackage(client), "opened") != null
        } || open(view, "opened") != null
        if (!openedInApp) return null
        if (!ScreenController.isAvailable()) {
            return "Открыл поиск видео на YouTube, сэр. Чтобы включить ролик, нужна служба спец. возможностей."
        }
        Thread.sleep(START_PAUSE_MS)
        repeat(16) {
            if (cancelled) return "Остановился, сэр."
            val nodes = ScreenController.visibleNodes(MAX_NODES)
            val raw = nodes.map { node ->
                RawNode(
                    text = listOf(node.text, node.contentDescription).filter { it.isNotBlank() }.joinToString(" "),
                    left = node.bounds.left,
                    top = node.bounds.top,
                    right = node.bounds.right,
                    bottom = node.bounds.bottom,
                    clickable = node.clickable && node.enabled,
                    editable = node.editable,
                )
            }
            val card = AppAgentRules.firstFullVideo(raw)
            val target = card?.let { nodes.getOrNull(raw.indexOf(it)) }
            if (target != null && ScreenController.tapCenter(target)) {
                VoiceHealthLog.write(context, "agent youtube first video")
                Thread.sleep(STEP_PAUSE_MS)
                return "Включил первое видео, сэр."
            }
            Thread.sleep(SCREEN_RETRY_MS)
        }
        VoiceHealthLog.write(context, "agent youtube no full video")
        return "Открыл поиск на YouTube, но обычное видео не нашёл, сэр."
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
        val draftText = TelegramAgentScreen.draftText(goal)
        val savedTask = savedChatLookup || draftText != null
        val protectChatHeader = TelegramAgentScreen.protectsHeader(goal)
        val chatLookupTitle = TelegramAgentScreen.lookupTitle(goal)
        val savedActions = HashSet<UiNode>()
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
        var plan = if (savedTask) emptyList() else
            askRetrying(AppAgentRules.planPrompt(goal, ScreenController.activePackageName().orEmpty()))
                ?.let(AppAgentRules::parsePlan).orEmpty()
                .ifEmpty { AppAgentRules.outline(goal) }
        VoiceHealthLog.write(context, "agent plan items=${plan.size}")
        var verifyRejected = 0
        var lastLeft = ""
        var recoveries = 0
        var recoveredSavedAttachments = false
        var unchangedSteps = 0
        /** В этой задаче уже ввели запрошенный текст. Вторую такую заметку создавать не нужно. */
        var wroteRequested = false
        val requestedText = AppAgentRules.requestedText(goal)
        fun writtenNow(): Boolean {
            if (requestedText == null) return false
            val visible = ScreenController.visibleNodes(MAX_NODES)
            if (visible.isEmpty()) return false
            val items = AppAgentRules.compact(visible.map(::raw)).map { it.item }
            return AppAgentRules.writeSatisfied(goal, items)
        }
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
            var all = ScreenController.visibleNodes(MAX_NODES)
            var waits = 0
            while ((all.isEmpty() || all.first().packageName != packageName) && waits < 10 && !cancelled) {
                Thread.sleep(SCREEN_RETRY_MS)
                waits++
                all = ScreenController.visibleNodes(MAX_NODES)
            }
            if (all.isEmpty() || all.first().packageName != packageName) continue
            if (all.any { it.password }) return partial("на экране открыто поле пароля", lastLeft, successfulSteps)
            val rawNodes = all.map(::raw)
            val isTelegram = packageName in AppFamilies.TELEGRAM
            val conversation = if (isTelegram) TelegramAgentScreen.conversation(rawNodes) else null
            val rows = AppAgentRules.compact(rawNodes)
            val screen = rows.map { row ->
                if (protectChatHeader && conversation != null &&
                    TelegramAgentScreen.isHeaderTarget(rawNodes, conversation, row.source)
                ) row.item.copy(readOnly = true) else row.item
            }
            val nodes = rows.map { all[it.source] }

            if (savedTask) {
                when {
                    !isTelegram -> {
                        if (!perform(AgentStep.Open("Telegram"), emptyList())) {
                            return partial("не удалось открыть Telegram", lastLeft, successfulSteps)
                        }
                        successfulSteps++
                    }
                    conversation != null && TelegramAgentScreen.isSaved(conversation.title) -> {
                        if (draftText == null || all[conversation.editor].text == draftText) {
                            VoiceHealthLog.write(context, if (draftText == null) "agent verified saved chat" else "agent verified saved draft")
                            return if (draftText == null) "Открыл «Избранное», сэр."
                            else "Черновик в «Избранном» готов, сэр."
                        }
                        val editor = all[conversation.editor]
                        if (editor.text.isNotBlank() && !TelegramAgentScreen.isMessageHint(editor.text) &&
                            editor.text != editor.hint
                        ) {
                            return partial("в «Избранном» уже есть другой черновик", "ввести запрошенный текст", successfulSteps)
                        }
                        if (!savedActions.add(editor)) return partial("ввод текста не подтвердился", lastLeft, successfulSteps)
                        if (!ScreenController.setTextOnNode(editor, draftText)) {
                            return partial("не удалось ввести текст", lastLeft, successfulSteps)
                        }
                        successfulSteps++
                    }
                    conversation != null || AppAgentRules.savedChatAttachmentsOpen(screen) -> {
                        if (recoveredSavedAttachments || !ScreenController.globalAction("back")) {
                            return partial("не удалось вернуться к списку чатов", lastLeft, successfulSteps)
                        }
                        recoveredSavedAttachments = true
                        VoiceHealthLog.write(context, "agent returned to Telegram chat list")
                    }
                    else -> {
                        val target = TelegramAgentScreen.savedNavigation(rawNodes)?.let(all::get)
                            ?.takeIf { it.enabled && it.clickable }
                            ?: return partial("пункт «Избранное» не найден на экране Telegram", lastLeft, successfulSteps)
                        if (!savedActions.add(target) || !ScreenController.tapCenter(target)) {
                            return partial("не удалось открыть «Избранное»", lastLeft, successfulSteps)
                        }
                        successfulSteps++
                        VoiceHealthLog.write(context, "agent used Telegram saved navigation")
                    }
                }
                Thread.sleep(STEP_PAUSE_MS)
                continue
            }

            if (chatLookupTitle != null && conversation?.title?.trim()?.equals(chatLookupTitle, ignoreCase = true) == true) {
                VoiceHealthLog.write(context, "agent verified requested chat")
                return "Открыл нужный чат, сэр."
            }
            if (previousPackage != packageName ||
                (searched && AppAgentRules.searchIndex(screen) == null && screen.any { it.editable })
            ) {
                searched = false
            }
            previousPackage = packageName
            if (wroteRequested && AppAgentRules.writeSatisfied(goal, screen)) {
                VoiceHealthLog.write(context, "agent verified written text")
                return "Готово, сэр."
            }
            if (requestedText != null && !wroteRequested) {
                val body = AppAgentRules.bodyField(screen, requestedText)
                if (body != null && perform(AgentStep.Type(body, requestedText), nodes)) {
                    wroteRequested = true
                    successfulSteps++
                    VoiceHealthLog.write(context, "agent typed requested text")
                    history += "ввёл «$requestedText»"
                    Thread.sleep(STEP_PAUSE_MS)
                    continue
                }
            }
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
                if (chatLookupTitle != null) {
                    lastLeft = "открыть чат «$chatLookupTitle»"
                    if (++verifyRejected > MAX_VERIFY_REJECTED) {
                        return partial("не удалось подтвердить заголовок беседы", lastLeft, successfulSteps)
                    }
                    history += "Заголовок открытой беседы не совпадает с искомым. Проверь, какой чат открыт, и продолжай поиск."
                    continue
                }
                if (wroteRequested && AppAgentRules.writeSatisfied(goal, screen)) {
                    VoiceHealthLog.write(context, "agent verified written text")
                    return step.reply.ifBlank { "Готово, сэр." }
                }
                if (requestedText != null && !wroteRequested) {
                    history += "текст «$requestedText» в этой задаче ещё не введён. Введи его и не создавай лишних заметок"
                    if (++verifyRejected > MAX_VERIFY_REJECTED) {
                        return partial("текст так и не был введён", requestedText, successfulSteps)
                    }
                    continue
                }
                val verification = askRetrying(AppAgentRules.verifyPrompt(goal, plan, packageName, screen, history))
                if (cancelled) return "Остановился, сэр."
                if (verification == null) return partial("не удалось проверить результат", lastLeft, successfulSteps)
                val evidence = AppAgentRules.completionEvidence(verification, screen)
                val visible = if (evidence.isEmpty()) emptyList() else ScreenController.visibleNodes(MAX_NODES)
                val verified = visible.firstOrNull()?.packageName == packageName &&
                    evidence.all { index -> visible.any { it == nodes[index] } }
                VoiceHealthLog.write(context, "agent verify $verified visibleEvidence=${evidence.size}")
                if (verified) return step.reply.ifBlank { "Готово, сэр." }
                lastLeft = AppAgentRules.parseVerify(verification).second
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
            if (step is AgentStep.Tap && screen[step.index].readOnly) {
                history += "Заголовок уже открытого чата ведёт в профиль или вложения. Используй поле «Сообщение» либо закончи поиск чата."
                VoiceHealthLog.write(context, "agent blocked unnecessary chat header tap")
                if (++blockedSteps > MAX_BLOCKED_STEPS && !recover(packageName)) {
                    return partial("модель повторяет лишний переход из чата", lastLeft, successfulSteps)
                }
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
                blockedSteps = 0
                successfulSteps++
                if (step is AgentStep.Type && AppAgentRules.isSearchField(screen[step.index])) searched = true
                if (step is AgentStep.Type && requestedText != null &&
                    step.text.contains(requestedText, ignoreCase = true)
                ) wroteRequested = true
                history += describe(step, screen)
            } else {
                history += "действие не сработало: ${describe(step, screen)}. Попробуй другой шаг."
            }
            Thread.sleep(STEP_PAUSE_MS)
            if (writtenNow()) {
                VoiceHealthLog.write(context, "agent verified written text")
                return "Готово, сэр."
            }
        }
        if (writtenNow()) {
            VoiceHealthLog.write(context, "agent verified written text")
            return "Готово, сэр."
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
