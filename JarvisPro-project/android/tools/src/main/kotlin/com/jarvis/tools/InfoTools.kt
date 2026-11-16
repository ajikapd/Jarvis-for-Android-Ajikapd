package com.jarvis.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.nlp.InfoFeeds
import com.jarvis.tools.net.SimpleHttp
import java.util.Locale

private const val NEWS_URL = "https://ria.ru/export/rss2/archive/index.xml"
private const val RATES_URL = "https://open.er-api.com/v6/latest/USD"
private const val NEWS_COUNT = 5
private const val NEWS_TTL_MS = 10 * 60_000L
private const val RATES_TTL_MS = 60 * 60_000L

private abstract class ReadTool(id: String, name: String, description: String, online: OnlineRequirement) : Tool {
    override val definition = ToolDefinition(
        id = id, name = name, description = description, parameters = emptyList(),
        riskLevel = RiskLevel.SAFE, onlineRequirement = online,
    )

    protected abstract fun read(): String?
    protected open fun read(call: ToolCall): String? = read()
    protected abstract val failure: String

    override fun execute(call: ToolCall): ToolResult {
        val text = runCatching { read(call) }.getOrNull()
        return if (text.isNullOrBlank()) {
            ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, failure)
        } else {
            ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, text)
        }
    }
}

/** Свежие заголовки: модель сама не знает новостей. */
fun newsTool(): Tool = object : ReadTool("news", "Новости", "Свежие новостные заголовки", OnlineRequirement.ONLINE_ALLOWED) {
    override val failure = "Новости сейчас недоступны, сэр."
    override fun read(): String? {
        val titles = InfoFeeds.headlines(SimpleHttp.get(NEWS_URL, NEWS_TTL_MS), NEWS_COUNT)
        return titles.takeIf { it.isNotEmpty() }?.joinToString(". ", prefix = "Главные новости, сэр. ", postfix = ".")
    }
}

/** Курсы доллара, евро и юаня в рублях. */
fun ratesTool(): Tool = object : ReadTool("rates", "Курсы валют", "Курс доллара, евро и юаня", OnlineRequirement.ONLINE_ALLOWED) {
    override val failure = "Курсы сейчас недоступны, сэр."
    override fun read(): String? = InfoFeeds.ratesReport(SimpleHttp.get(RATES_URL, RATES_TTL_MS))
    override fun read(call: ToolCall): String? {
        val code = call.arguments["code"]?.toString() ?: return read()
        return InfoFeeds.rateReport(SimpleHttp.get(RATES_URL, RATES_TTL_MS), code)
    }
}

/** Где находится телефон: адрес по последнему известному положению. */
fun locationTool(context: Context): Tool =
    object : ReadTool("location", "Местоположение", "Адрес, где сейчас находится телефон", OnlineRequirement.LOCAL_ONLY) {
        override val failure = "Не могу определить местоположение, сэр."

        override fun read(): String? {
            val location = lastKnown() ?: return null
            val address = runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale("ru")).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }.getOrNull()
            val place = address?.let { listOfNotNull(it.locality, it.thoroughfare, it.subThoroughfare) }
                ?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            return if (place != null) "Вы здесь, сэр: $place." else {
                "Координаты, сэр: %.4f, %.4f.".format(Locale.US, location.latitude, location.longitude)
            }
        }

        private fun lastKnown(): Location? {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) return null
            val manager = context.getSystemService(LocationManager::class.java) ?: return null
            return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        }
    }
