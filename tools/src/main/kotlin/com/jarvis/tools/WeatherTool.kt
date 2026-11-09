package com.jarvis.tools

import android.content.Context
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.tools.net.SimpleHttp
import java.net.URLEncoder
import java.util.Locale

/** Город для погоды, когда геолокация недоступна (настройка «Присутствие → Погода»). */
const val HOME_CITY_PREF = "weather_home_city"

/**
 * Погода через Open-Meteo (без ключа). Кэш ~12 минут.
 */
class WeatherTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "weather",
        name = "Погода",
        description = "Текущая погода и краткий прогноз по городу или координатам телефона",
        parameters = listOf(
            ToolParameter("city", "Город (опционально)", ParameterType.STRING, required = false),
            ToolParameter("day", "0=сегодня, 1=завтра, 2=послезавтра", ParameterType.INTEGER, required = false),
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.ONLINE_REQUIRED,
    )

    override fun execute(call: ToolCall): ToolResult {
        return try {
            val cityRaw = call.arguments["city"]?.toString()?.trim().orEmpty()
            // Без города в запросе — где сейчас телефон. Москвы по умолчанию больше нет.
            val home = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
                .getString(HOME_CITY_PREF, null)?.trim().orEmpty()
            val place = when {
                cityRaw.isNotBlank() -> geocode(cityRaw)
                else -> locateDevice() ?: home.takeIf { it.isNotBlank() }?.let { geocode(it) }
            }
            if (place == null) {
                val where = DeviceLocation(context)
                val why = when {
                    cityRaw.isNotBlank() -> "Не нашёл город «$cityRaw». Скажите иначе, например: «погода в Казани»."
                    !where.hasPermission() ->
                        "Не знаю, где вы. Разрешите Джарвису доступ к местоположению или назовите город: «погода в Казани»."
                    !where.isEnabled() ->
                        "Геолокация на телефоне выключена. Включите её или укажите город в настройках «Присутствие → Погода»."
                    else -> "Не удалось определить место. Назовите город или укажите его в настройках «Присутствие → Погода»."
                }
                return ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, why)
            }
            val url =
                "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
                    "&daily=temperature_2m_max,temperature_2m_min,weather_code&forecast_days=7&timezone=auto"
            val json = SimpleHttp.getJson(url, ttlMs = 12 * 60_000L)
            val dayOffset = (call.arguments["day"] as? Number)?.toInt()
                ?: call.arguments["day"]?.toString()?.toIntOrNull()
                ?: 0
            val dayIdx = dayOffset.coerceIn(0, 6)
            if (call.arguments["week"] == true) {
                val daily = json.optJSONObject("daily")
                val highs = daily?.optJSONArray("temperature_2m_max")
                val lows = daily?.optJSONArray("temperature_2m_min")
                val codes = daily?.optJSONArray("weather_code")
                if (highs != null && lows != null && codes != null) {
                    val range = 0 until minOf(highs.length(), lows.length(), codes.length())
                    val reply = weekSummary(
                        place.name, range.map { lows.optDouble(it) }, range.map { highs.optDouble(it) },
                        range.map { codes.optInt(it) }, java.time.LocalDate.now(),
                    )
                    return ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, reply, mapOf("city" to place.name))
                }
            }
            val current = json.getJSONObject("current")
            val daily = json.optJSONObject("daily")
            if (dayIdx > 0 && daily != null) {
                val tmax = daily.optJSONArray("temperature_2m_max")?.optDouble(dayIdx)
                val tmin = daily.optJSONArray("temperature_2m_min")?.optDouble(dayIdx)
                val code = daily.optJSONArray("weather_code")?.optInt(dayIdx) ?: 0
                val label = when (dayIdx) {
                    1 -> "завтра"
                    2 -> "послезавтра"
                    0 -> "сегодня"
                    else -> com.jarvis.core.reminder.ReminderSpeech.weekdayPhrase(java.time.LocalDate.now().plusDays(dayIdx.toLong()))
                }
                val desc = weatherCodeRu(code)
                val reply = if (tmax != null && tmin != null) {
                    String.format(
                        Locale.US,
                        "%s, %s: от %.0f° до %.0f°. %s.",
                        place.name, label, tmin, tmax, desc,
                    )
                } else {
                    "${place.name}, $label: $desc."
                }
                return ToolResult(
                    call.requestId, definition.id, ToolResultStatus.SUCCESS, reply,
                    mapOf("city" to place.name, "day" to dayIdx, "code" to code),
                )
            }
            val temp = current.getDouble("temperature_2m")
            val feels = current.optDouble("apparent_temperature", temp)
            val wind = current.optDouble("wind_speed_10m", 0.0)
            val humidity = current.optInt("relative_humidity_2m", -1)
            val code = current.optInt("weather_code", 0)
            val desc = weatherCodeRu(code)
            val tmax = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)
            val tmin = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)
            val dayPart = if (tmax != null && tmin != null) {
                String.format(Locale.US, " Сегодня от %.0f° до %.0f°.", tmin, tmax)
            } else ""
            val humPart = if (humidity >= 0) " Влажность $humidity%." else ""
            val reply = String.format(
                Locale.US,
                "%s, сейчас %.0f°, ощущается как %.0f°. %s. Ветер %.0f м/с.%s%s",
                place.name, temp, feels, desc, wind, humPart, dayPart,
            )
            ToolResult(
                call.requestId, definition.id, ToolResultStatus.SUCCESS, reply,
                mapOf("city" to place.name, "temp" to temp, "code" to code),
            )
        } catch (t: Throwable) {
            ToolResult(
                call.requestId, definition.id,
                if (t.message?.contains("Unable to resolve", true) == true ||
                    t.message?.contains("Failed to connect", true) == true
                ) ToolResultStatus.OFFLINE else ToolResultStatus.FAILED,
                "Не получил погоду: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private data class Place(val name: String, val lat: Double, val lon: Double)

    private fun geocode(city: String): Place? {
        val candidates = cityQueryVariants(city)
        for (candidate in candidates) {
            val q = URLEncoder.encode(candidate, Charsets.UTF_8.name())
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=ru&format=json"
            val json = runCatching { SimpleHttp.getJson(url, ttlMs = 24 * 60 * 60_000L) }.getOrNull()
                ?: continue
            val results = json.optJSONArray("results") ?: continue
            if (results.length() == 0) continue
            val first = results.getJSONObject(0)
            return Place(
                name = first.optString("name", candidate),
                lat = first.getDouble("latitude"),
                lon = first.getDouble("longitude"),
            )
        }
        return null
    }

    /** Падежи/алиасы → варианты запроса к geocoder. */
    private fun cityQueryVariants(raw: String): List<String> {
        val c = raw.lowercase().trim()
        if (c.isBlank()) return emptyList()
        val known = mapOf(
            "москве" to "Москва", "москвы" to "Москва", "москву" to "Москва", "москва" to "Москва",
            "питере" to "Санкт-Петербург", "питера" to "Санкт-Петербург",
            "петербурге" to "Санкт-Петербург", "петербург" to "Санкт-Петербург", "спб" to "Санкт-Петербург",
            "казани" to "Казань", "казань" to "Казань",
            "новосибирске" to "Новосибирск", "новосибирск" to "Новосибирск",
            "екб" to "Екатеринбург", "екатеринбурге" to "Екатеринбург", "екатеринбург" to "Екатеринбург",
            "сочи" to "Сочи",
            "минске" to "Минск", "минск" to "Минск",
        )
        val primary = known[c] ?: raw.trim().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString()
        }
        val stemmed = when {
            c.endsWith("е") && c.length > 4 -> c.dropLast(1).replaceFirstChar { it.uppercase() }
            c.endsWith("и") && c.length > 4 -> c.dropLast(1).replaceFirstChar { it.uppercase() }
            else -> null
        }
        return listOfNotNull(primary, stemmed, raw.trim()).distinct()
    }

    private fun locateDevice(): Place? {
        val spot = DeviceLocation(context).current() ?: return null
        return Place(spot.name ?: "Рядом с вами", spot.lat, spot.lon)
    }

    private fun weatherCodeRu(code: Int): String = when (code) {
        0 -> "Ясно"
        1, 2 -> "Малооблачно"
        3 -> "Пасмурно"
        45, 48 -> "Туман"
        51, 53, 55 -> "Морось"
        61, 63, 65 -> "Дождь"
        66, 67 -> "Ледяной дождь"
        71, 73, 75, 77 -> "Снег"
        80, 81, 82 -> "Ливень"
        85, 86 -> "Снегопад"
        95 -> "Гроза"
        96, 99 -> "Гроза с градом"
        else -> "Переменная облачность"
    }
}

/** Неделя голосом: размах температур и дни с осадками, без перечисления всех семи дней. */
internal fun weekSummary(place: String, lows: List<Double>, highs: List<Double>, codes: List<Int>, today: java.time.LocalDate): String {
    val wet = codes.indices.filter { codes[it] >= FIRST_PRECIPITATION_CODE }
        .map {
            when (it) {
                0 -> "сегодня"
                1 -> "завтра"
                else -> com.jarvis.core.reminder.ReminderSpeech.weekdayPhrase(today.plusDays(it.toLong()))
            }
        }
    val rain = if (wet.isEmpty()) "Без осадков" else "Осадки ${wet.joinToString(", ")}"
    return String.format(Locale.US, "%s на неделю: от %.0f° до %.0f°. %s, сэр.", place, lows.min(), highs.max(), rain)
}

private const val FIRST_PRECIPITATION_CODE = 51
