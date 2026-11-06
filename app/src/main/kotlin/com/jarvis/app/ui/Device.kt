package com.jarvis.app.ui

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Настоящие показатели телефона для экранов. */
object Device {
    fun time(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }

    fun battery(context: Context): Int =
        context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0

    fun temperature(context: Context): Int =
        (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10

    fun ramPercent(context: Context): Int {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return if (info.totalMem > 0) (100 - info.availMem * 100 / info.totalMem).toInt() else 0
    }

    fun network(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "Нет"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Нет сети"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Моб. сеть"
            else -> "Подключено"
        }
    }

    fun freeStorageGb(): Long = runCatching {
        StatFs(Environment.getDataDirectory().path).availableBytes / 1_000_000_000
    }.getOrDefault(0L)

    /** Загрузка процессора по текущей частоте ядер: системный счётчик приложениям закрыт. */
    fun cpuPercent(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        var sum = 0.0
        var seen = 0
        for (i in 0 until cores) {
            fun read(name: String) = runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/$name").readText().trim().toLong() }.getOrNull()
            val cur = read("scaling_cur_freq") ?: continue
            val max = read("cpuinfo_max_freq") ?: continue
            if (max > 0) { sum += cur.toDouble() / max; seen++ }
        }
        return if (seen == 0) 0 else (sum / seen * 100).toInt()
    }

    fun vitals(context: Context): String =
        "Заряд ${battery(context)}%\nПамять ${ramPercent(context)}%\nСеть ${network(context)}"

    fun nextAlarm(context: Context): Long? = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
}
