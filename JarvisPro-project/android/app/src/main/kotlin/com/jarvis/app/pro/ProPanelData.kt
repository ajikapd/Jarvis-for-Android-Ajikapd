package com.jarvis.app.pro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jarvis.app.AssistantCapabilities
import com.jarvis.app.BrainGraph
import com.jarvis.app.JarvisSettings
import com.jarvis.app.ModelCatalog
import com.jarvis.app.ModelManager
import com.jarvis.app.NativeGuard
import com.jarvis.app.PrefsCustomRoutines
import com.jarvis.app.PrefsEventRoutines
import com.jarvis.app.SecureKeyStore
import com.jarvis.app.VoiceController
import com.jarvis.app.VoiceIdentity
import com.jarvis.app.VoiceSecondCheck
import com.jarvis.app.companion.CompanionMemory
import com.jarvis.app.ui.Device
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.llm.CloudKeyValidator
import com.jarvis.core.routines.CommandBuilder
import com.jarvis.core.routines.EventRule
import com.jarvis.core.routines.EventTrigger
import com.jarvis.core.runtime.LatencyStatsStore
import com.jarvis.screencontrol.JarvisNotificationListenerService
import com.jarvis.tools.AppAliases
import com.jarvis.tools.CalendarTool
import com.jarvis.tools.LauncherTool
import com.jarvis.tools.WeatherTool
import com.jarvis.tools.HOME_CITY_PREF
import org.json.JSONArray
import org.json.JSONObject

/** Data and actions for the sections that previously jumped to the old activity. */
class ProPanelData(private val ctx: Context) {
    private val settings get() = JarvisSettings.prefs(ctx)
    private val models get() = ModelManager.get(ctx)
    private val routines get() = PrefsCustomRoutines(ctx)
    private val events get() = PrefsEventRoutines(ctx)
    private val keys get() = SecureKeyStore(ctx)

    fun handle(kind: String, a: JSONObject): JSONObject = try {
        when (kind) {
            "panelSettings" -> settings()
            "panelSet" -> set(a.optString("key"), a.opt("value"))
            "panelModels" -> modelList()
            "panelModel" -> modelAction(a.optString("id"), a.optString("action"))
            "panelKeys" -> JSONObject().put("items", JSONObject().apply {
                for (p in KEY_IDS) put(p, keys.hasKey(p))
            })
            "panelKey" -> keyAction(a)
            "panelRoutines" -> routineList()
            "panelRoutine" -> routineAction(a)
            "panelEvents" -> eventList()
            "panelEvent" -> eventAction(a)
            "panelAliases" -> aliases()
            "panelAlias" -> aliasAction(a)
            "panelSkills" -> skills()
            "panelSystem" -> system()
            "panelScan" -> scan()
            "panelMemoryClear" -> {
                CompanionMemory.get(ctx).repository().clearAll()
                BrainGraph.memory(ctx).clear()
                ok()
            }
            "panelMemoryFacts" -> JSONObject().put("items", JSONArray().apply {
                BrainGraph.memory(ctx).all().take(40).forEach { f ->
                    put(JSONObject().put("text", f.text).put("category", f.category))
                }
            }).put("count", BrainGraph.memory(ctx).count())
            "panelLlamaReset" -> {
                NativeGuard.reset(ctx, NativeGuard.LLAMA)
                BrainGraph.invalidateChatLlm()
                BrainGraph.invalidate()
                ok()
            }
            "panelVoice" -> {
                val (own, imp) = VoiceSecondCheck.learnedCounts(ctx)
                JSONObject().put("ready", VoiceIdentity.modelReady(ctx))
                    .put("enrolled", VoiceIdentity.enrolled(ctx))
                    .put("phrases", VoiceIdentity.phrasesEnrolled(ctx))
                    .put("learned", VoiceIdentity.learnedCount(ctx))
                    .put("secondReady", VoiceSecondCheck.modelReady(ctx))
                    .put("secondActive", VoiceSecondCheck.active(ctx))
                    .put("secondModel", VoiceSecondCheck.MODEL_ID)
                    .put("secondOwn", own)
                    .put("secondImp", imp)
            }
            "panelVoiceForget" -> { VoiceIdentity.forget(ctx); ok() }
            else -> JSONObject().put("err", "Неизвестный запрос")
        }
    } catch (e: Throwable) { JSONObject().put("err", e.message ?: e.javaClass.simpleName) }

    private fun ok() = JSONObject().put("ok", true)

    private fun settings() = JSONObject().put("items", JSONObject().apply {
        put("wake", JarvisSettings.wakeEnabled(ctx))
        put("sensitivity", JarvisSettings.wakeSensitivity(ctx))
        put("voiceLock", JarvisSettings.voiceLock(ctx))
        put("chime", JarvisSettings.wakeChime(ctx))
        put("followMedia", JarvisSettings.voiceFollowsMedia(ctx))
        put("volume", JarvisSettings.voiceVolume(ctx))
        put("conversation", JarvisSettings.conversationMode(ctx))
        put("adaptive", settings.getBoolean("adaptive_listening", true))
        put("lowPower", settings.getBoolean("low_power", false))
        put("asrMode", JarvisSettings.asrMode(ctx))
        put("asrUrl", JarvisSettings.asrCloudBaseUrl(ctx))
        put("ttsMode", JarvisSettings.ttsMode(ctx))
        put("ttsUrl", JarvisSettings.ttsCloudBaseUrl(ctx))
        put("persona", JarvisSettings.persona(ctx))
        put("chatProfile", JarvisSettings.chatProfile(ctx))
        put("confidence", JarvisSettings.brainConfidenceThreshold(ctx))
        put("router", JarvisSettings.routerMode(ctx))
        put("llm", JarvisSettings.llmEnabled(ctx))
        put("preferCloud", JarvisSettings.preferCloudLlm(ctx))
        put("preload", JarvisSettings.chatPreloadEnabled(ctx))
        put("cloudUrl", JarvisSettings.cloudBaseUrl(ctx))
        put("hudSfx", JarvisSettings.hudSfx(ctx))
        put("orbSnap", settings.getBoolean("companion_orb_snap", true))
        put("city", settings.getString(HOME_CITY_PREF, ""))
        put("proactive", settings.getBoolean("companion_proactive", true))
        put("bgNotify", settings.getBoolean("companion_bg_notify", true))
        put("quiet", settings.getBoolean("companion_quiet", false))
        put("announceCalls", JarvisSettings.announceCalls(ctx))
        put("announceMessages", JarvisSettings.announceMessages(ctx))
        put("announceHints", JarvisSettings.announceHints(ctx))
        put("confirmSensitive", settings.getBoolean("confirm_sensitive", true))
        put("memory", JarvisSettings.memoryEnabled(ctx))
        put("live", VoiceController.liveTalk)
        put("liveMuted", VoiceController.liveMuted)
        put("micGranted", ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        put("notificationsGranted", Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        put("llamaOff", NativeGuard.isOff(ctx, NativeGuard.LLAMA))
        put("facts", runCatching { BrainGraph.memory(ctx).count() }.getOrDefault(0))
    })

    private fun set(key: String, raw: Any?): JSONObject {
        val value = raw?.toString().orEmpty()
        val on = value == "true"
        val modes = setOf("AUTO", "CLOUD_PREFERRED", "LOCAL_ONLY")
        when (key) {
            "wake" -> JarvisSettings.setWakeEnabled(ctx, on)
            "sensitivity" -> JarvisSettings.setWakeSensitivity(ctx, value.toIntOrNull()?.coerceIn(0, 2) ?: return invalid())
            "voiceLock" -> {
                if (on && !VoiceIdentity.modelReady(ctx)) return JSONObject().put("err", "Сначала скачайте модель узнавания")
                if (on && (!VoiceIdentity.enrolled(ctx) || !VoiceIdentity.phrasesEnrolled(ctx)))
                    return JSONObject().put("err", "Сначала запишите голос")
                JarvisSettings.setVoiceLock(ctx, on)
            }
            "chime" -> JarvisSettings.setWakeChime(ctx, on)
            "followMedia" -> JarvisSettings.setVoiceOutput(ctx, followMedia = on)
            "volume" -> JarvisSettings.setVoiceOutput(ctx, volume = value.toIntOrNull()?.coerceIn(10, 100) ?: return invalid())
            "conversation" -> JarvisSettings.setConversationMode(ctx, on)
            "adaptive" -> settings.edit().putBoolean("adaptive_listening", on).apply()
            "lowPower" -> settings.edit().putBoolean("low_power", on).apply()
            "asrMode" -> { if (value !in modes) return invalid(); JarvisSettings.setAsrMode(ctx, value) }
            "asrUrl" -> JarvisSettings.setAsrCloudBaseUrl(ctx, value)
            "ttsMode" -> { if (value !in modes) return invalid(); JarvisSettings.setTtsMode(ctx, value) }
            "ttsUrl" -> JarvisSettings.setTtsCloudBaseUrl(ctx, value)
            "persona" -> { if (value !in JarvisSettings.PERSONAS) return invalid(); JarvisSettings.setPersona(ctx, value) }
            "chatProfile" -> { if (value !in JarvisSettings.CHAT_PROFILES) return invalid(); JarvisSettings.setChatProfile(ctx, value) }
            "confidence" -> JarvisSettings.setBrainConfidenceThreshold(ctx, value.toFloatOrNull()?.coerceIn(.3f, .9f) ?: return invalid())
            "router" -> { if (value !in setOf("HYBRID", "LOCAL", "CLOUD")) return invalid(); repeat(3) { if (JarvisSettings.routerMode(ctx) != value) JarvisSettings.cycleRouterMode(ctx) } }
            "llm" -> JarvisSettings.setLlmEnabled(ctx, on)
            "preferCloud" -> JarvisSettings.setPreferCloudLlm(ctx, on)
            "preload" -> JarvisSettings.setChatPreloadEnabled(ctx, on)
            "cloudUrl" -> JarvisSettings.setCloudBaseUrl(ctx, value)
            "hudSfx" -> JarvisSettings.setHudSfx(ctx, on)
            "orbSnap" -> settings.edit().putBoolean("companion_orb_snap", on).apply()
            "city" -> settings.edit().putString(HOME_CITY_PREF, value.trim()).apply()
            "proactive" -> settings.edit().putBoolean("companion_proactive", on).apply()
            "bgNotify" -> settings.edit().putBoolean("companion_bg_notify", on).apply()
            "quiet" -> settings.edit().putBoolean("companion_quiet", on).apply()
            "announceCalls" -> JarvisSettings.setAnnounce(ctx, "calls", on)
            "announceMessages" -> JarvisSettings.setAnnounce(ctx, "messages", on)
            "announceHints" -> JarvisSettings.setAnnounce(ctx, "hints", on)
            "confirmSensitive" -> settings.edit().putBoolean("confirm_sensitive", on).apply()
            "memory" -> JarvisSettings.setMemoryEnabled(ctx, on)
            "live" -> { VoiceController.liveTalk = on; if (on) VoiceController.liveStartedAt = System.currentTimeMillis() else VoiceController.liveMuted = false }
            "liveMuted" -> VoiceController.liveMuted = on
            else -> return invalid()
        }
        if (key in RESTART_KEYS && VoiceController.isRunning) {
            // Service start/stop is dispatched by ProActivity on the UI thread.
            return ok().put("restart", true)
        }
        if (key in BRAIN_KEYS) BrainGraph.invalidate()
        return ok()
    }

    private fun invalid() = JSONObject().put("err", "Недопустимое значение")

    private fun modelList(): JSONObject {
        return JSONObject().put("items", JSONArray().apply {
            ModelCatalog.ALL.forEach { e ->
                val st = models.status(e.id)
                put(JSONObject().put("id", e.id).put("title", e.title).put("about", e.subtitle)
                    .put("mb", e.sizeBytes / 1_000_000).put("state", st.state.name)
                    .put("progress", (st.progress * 100).toInt()).put("message", st.message))
            }
        })
    }

    private fun modelAction(id: String, action: String): JSONObject {
        if (ModelCatalog.byId(id) == null) return invalid()
        when (action) {
            "download" -> models.download(id)
            "cancel" -> models.cancel(id)
            else -> return invalid()
        }
        return ok()
    }

    private fun keyAction(a: JSONObject): JSONObject {
        val id = a.optString("id")
        if (id !in KEY_IDS) return invalid()
        if (a.optString("action") == "clear") keys.clearKey(id)
        else if (a.optString("action") == "save") {
            val key = CloudKeyValidator.sanitizeKey(a.optString("key"))
            if (CloudKeyValidator.isStubKey(key)) return invalid()
            keys.setKey(id, key)
        } else return invalid()
        BrainGraph.onCloudKeyChanged(id)
        BrainGraph.invalidate()
        return ok().put("saved", keys.hasKey(id))
    }

    private fun routineList(): JSONObject = JSONObject().put("items", JSONArray().apply {
        routines.names().sorted().forEach { name ->
            val steps = routines.steps(name).orEmpty()
            put(JSONObject().put("name", name).put("mode", CommandBuilder.modeOf(steps).name)
                .put("body", CommandBuilder.bodyOf(steps)).put("steps", JSONArray(steps)))
        }
    }).put("templates", JSONArray(CommandBuilder.TEMPLATES))

    private fun routineAction(a: JSONObject): JSONObject {
        val action = a.optString("action")
        val old = a.optString("old")
        if (action == "delete") return ok().put("removed", routines.remove(old))
        if (action != "save") return invalid()
        val mode = runCatching { CommandBuilder.Mode.valueOf(a.optString("mode")) }.getOrNull() ?: return invalid()
        val reserved = com.jarvis.core.nlp.Routines.builtInNames.map(CommandBuilder::key).toSet() - setOf(CommandBuilder.key(old))
        return when (val result = CommandBuilder.build(a.optString("name"), mode, a.optString("body"), reserved)) {
            is CommandBuilder.Result.Error -> JSONObject().put("err", result.message)
            is CommandBuilder.Result.Ok -> {
                if (old.isNotBlank() && CommandBuilder.key(old) != result.trigger) routines.remove(old)
                routines.put(result.trigger, result.steps)
                ok().put("name", result.trigger)
            }
        }
    }

    private fun aliases(): JSONObject {
        val labels = LauncherTool(ctx).launcherApps().toMap()
        return JSONObject().put("items", JSONArray().apply {
            AppAliases(ctx).all().forEach { (alias, pkg) -> put(JSONObject().put("alias", alias).put("pkg", pkg).put("app", labels[pkg] ?: pkg)) }
        }).put("apps", JSONArray().apply {
            labels.entries.sortedBy { it.value.lowercase() }.forEach { (pkg, name) -> put(JSONObject().put("pkg", pkg).put("name", name)) }
        })
    }

    private fun aliasAction(a: JSONObject): JSONObject {
        val alias = a.optString("alias").trim()
        if (alias.isBlank()) return invalid()
        if (a.optString("action") == "delete") AppAliases(ctx).remove(alias)
        else if (a.optString("action") == "add") {
            val pkg = a.optString("pkg")
            if (LauncherTool(ctx).launcherApps().none { it.first == pkg }) return invalid()
            AppAliases(ctx).add(alias, pkg)
        } else return invalid()
        return ok()
    }

    private fun skills(): JSONObject = JSONObject().put("items", JSONArray().apply {
        AssistantCapabilities.ALL.forEach { c ->
            put(JSONObject().put("title", c.title).put("category", c.category).put("description", c.description)
                .put("example", c.examples.firstOrNull()?.removePrefix("джарвис, ").orEmpty())
                .put("risk", c.risk.name).put("needs", c.needs))
        }
    })

    private fun system() = JSONObject()
        .put("battery", Device.battery(ctx)).put("ram", Device.ramPercent(ctx)).put("cpu", Device.cpuPercent())
        .put("temperature", Device.temperature(ctx)).put("network", Device.network(ctx))
        .put("storage", Device.freeStorageGb()).put("voice", VoiceController.isRunning)
        .put("live", VoiceController.liveTalk)
        .put("latency", JSONArray(LatencyStatsStore.summaryLines()))

    private fun eventList() = JSONObject()
        .put("items", JSONArray().apply {
            events.rules().forEach { r ->
                put(
                    JSONObject()
                        .put("trigger", r.trigger.name)
                        .put("title", r.trigger.title)
                        .put("arg", r.arg)
                        .put("name", r.name)
                        .put("hasSteps", routines.steps(r.name) != null),
                )
            }
        })
        .put("triggers", JSONArray().apply {
            EventTrigger.entries.forEach { t ->
                put(JSONObject().put("id", t.name).put("title", t.title))
            }
        })

    private fun eventAction(a: JSONObject): JSONObject {
        val trigger = EventTrigger.entries.firstOrNull { it.name == a.optString("trigger") } ?: return invalid()
        val arg = a.optString("arg").trim()
        val rule = EventRule(trigger, arg)
        return when (a.optString("action")) {
            "delete" -> {
                events.remove(rule)
                ok()
            }
            "save" -> {
                val body = a.optString("body").trim()
                if (body.isNotBlank()) {
                    val built = CommandBuilder.build(rule.name, CommandBuilder.Mode.CHAIN, body, emptySet())
                    if (built is CommandBuilder.Result.Error) return JSONObject().put("err", built.message)
                    if (built is CommandBuilder.Result.Ok) routines.put(built.trigger, built.steps)
                } else if (routines.steps(rule.name) == null) {
                    return JSONObject().put("err", "Сначала укажи шаги или создай команду с именем «${rule.name}»")
                }
                events.put(rule)
                ok().put("name", rule.name)
            }
            else -> invalid()
        }
    }

    private fun scan(): JSONObject {
        val weather = runCatching {
            WeatherTool(ctx).execute(ToolCall(requestId = "scan", toolId = "weather", arguments = emptyMap())).message.orEmpty()
        }.getOrDefault("")
        val cal = runCatching {
            CalendarTool(ctx).execute(
                ToolCall(requestId = "scan", toolId = "calendar", arguments = mapOf("action" to "list", "day" to 0)),
            ).message.orEmpty()
        }.getOrDefault("")
        val notifs = runCatching {
            if (!JarvisNotificationListenerService.isConnected()) 0
            else JarvisNotificationListenerService.snapshot().count {
                it.packageName != ctx.packageName && it.title.isNotBlank()
            }
        }.getOrDefault(0)
        val temp = Regex("сейчас (-?\\d+)°").find(weather)?.groupValues?.get(1)
        return JSONObject()
            .put("weather", weather)
            .put("temp", temp ?: "")
            .put("calendar", cal)
            .put("calendarEmpty", cal.isBlank() || cal.contains("нет", ignoreCase = true))
            .put("notifs", notifs)
    }

    companion object {
        private val KEY_IDS = setOf("groq", "gemini", "gigachat", "custom", "custom_asr", "custom_tts", "safev")
        private val RESTART_KEYS = setOf("wake", "sensitivity")
        private val BRAIN_KEYS = setOf("asrMode", "asrUrl", "ttsMode", "ttsUrl", "persona", "router", "llm", "preferCloud", "cloudUrl")
    }
}
