package com.jarvis.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Точка с временем и точностью: из неё выбирается самая подходящая. */
internal data class Fix(val lat: Double, val lon: Double, val timeMs: Long, val accuracyM: Float)

internal object FixChoice {
    /** Свежая точка (до 3 часов) с лучшей точностью; при равной точности — новее. */
    fun best(fixes: List<Fix>, nowMs: Long, maxAgeMs: Long = 3 * 60 * 60_000L): Fix? =
        fixes.filter { nowMs - it.timeMs in 0..maxAgeMs }
            .sortedWith(compareBy<Fix> { it.accuracyM }.thenByDescending { it.timeMs })
            .firstOrNull()

    /** Название места для ответа: город, иначе район, иначе область. */
    fun placeName(locality: String?, subAdmin: String?, admin: String?): String? =
        listOf(locality, subAdmin, admin).firstOrNull { !it.isNullOrBlank() }?.trim()
}

/**
 * Где сейчас телефон и как называется это место.
 * Последняя найденная точка и её название запоминаются: без сети геолокации погода всё равно знает город.
 */
internal class DeviceLocation(private val context: Context) {
    data class Spot(val name: String?, val lat: Double, val lon: Double)

    private val prefs get() = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else providers(lm).isNotEmpty()
    }

    fun current(): Spot? {
        if (!hasPermission()) return saved()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return saved()
        val now = System.currentTimeMillis()
        val known = providers(lm).mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.toFix()
        }
        val fix = FixChoice.best(known, now) ?: fresh(lm)?.toFix() ?: return saved()
        val name = nameFor(fix.lat, fix.lon)
        save(fix, name)
        return Spot(name, fix.lat, fix.lon)
    }

    private fun providers(lm: LocationManager): List<String> {
        val all = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val order = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return order.filter { it in all }
    }

    /** Свежая точка за несколько секунд (Android 11+). */
    private fun fresh(lm: LocationManager): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = providers(lm).firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return null
        val latch = CountDownLatch(1)
        var result: Location? = null
        val cancel = CancellationSignal()
        val executor = Executors.newSingleThreadExecutor()
        runCatching {
            lm.getCurrentLocation(provider, cancel, executor) { loc ->
                result = loc
                latch.countDown()
            }
            if (!latch.await(FRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) cancel.cancel()
        }
        executor.shutdown()
        return result
    }

    @Suppress("DEPRECATION")
    private fun nameFor(lat: Double, lon: Double): String? {
        val key = "%.2f,%.2f".format(Locale.ROOT, lat, lon)
        prefs.getString("geo_name_$key", null)?.let { return it }
        if (!Geocoder.isPresent()) return null
        val address = runCatching { Geocoder(context, Locale("ru")).getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
            ?: return null
        val name = FixChoice.placeName(address.locality, address.subAdminArea, address.adminArea) ?: return null
        prefs.edit().putString("geo_name_$key", name).apply()
        return name
    }

    private fun save(fix: Fix, name: String?) {
        prefs.edit()
            .putString("geo_last_lat", fix.lat.toString())
            .putString("geo_last_lon", fix.lon.toString())
            .putString("geo_last_name", name)
            .apply()
    }

    private fun saved(): Spot? {
        val lat = prefs.getString("geo_last_lat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("geo_last_lon", null)?.toDoubleOrNull() ?: return null
        return Spot(prefs.getString("geo_last_name", null), lat, lon)
    }

    private fun Location.toFix() = Fix(latitude, longitude, time, if (hasAccuracy()) accuracy else 5_000f)

    private companion object {
        const val FRESH_TIMEOUT_MS = 5_000L
    }
}
