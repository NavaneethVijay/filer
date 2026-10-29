package com.navaneeth.filer.data.worker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ThermalMetrics(
    val status: Int = PowerManager.THERMAL_STATUS_NONE,
    val statusLabel: String = "Normal",
    val headroom: Float = 0f,
    val batteryTempC: Float = 0f,
    val isThrottled: Boolean = false
)

/**
 * Paces model work by the phone's temperature and thermal headroom so indexing
 * never causes harsh thermal throttling or device discomfort.
 *
 * Listens reactively to [PowerManager.OnThermalStatusChangedListener] and samples
 * [PowerManager.getThermalHeadroom] (where 1.0 means severe throttling threshold)
 * as well as battery hardware temperature.
 */
class ThermalGovernor(private val context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)
    private var lastHeadroom = 0f
    private var lastSampleAt = 0L

    private val _thermalMetrics = MutableStateFlow(ThermalMetrics())
    val thermalMetrics: StateFlow<ThermalMetrics> = _thermalMetrics.asStateFlow()

    init {
        _thermalMetrics.value = sampleMetrics(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power?.addThermalStatusListener { status ->
                val isThrottled = status >= PowerManager.THERMAL_STATUS_SEVERE
                _thermalMetrics.value = sampleMetrics(isThrottled, status)
            }
        }
    }

    /**
     * Call before each model inference. Returns immediately when the phone is cool. With
     * [routineRests] off (the model runs on a Mac, so the phone only reads files) the short rests
     * when warm are skipped, but a severe/critical pause still applies.
     */
    suspend fun awaitCapacity(routineRests: Boolean = true): Boolean {
        var throttled = false
        while (status() >= PowerManager.THERMAL_STATUS_SEVERE) {
            throttled = true
            _thermalMetrics.value = sampleMetrics(isThrottled = true)
            delay(COOL_DOWN_POLL_MS)
        }

        if (!routineRests) {
            if (throttled) _thermalMetrics.value = sampleMetrics(isThrottled = false)
            return throttled
        }

        val headroom = headroom()
        val curStatus = status()
        val rest = when {
            curStatus >= PowerManager.THERMAL_STATUS_MODERATE || headroom >= 0.85f -> 2_500L
            curStatus >= PowerManager.THERMAL_STATUS_LIGHT || headroom >= 0.70f -> 400L
            else -> 0L
        }

        _thermalMetrics.value = sampleMetrics(isThrottled = throttled || rest >= 2_000L)
        if (rest > 0) delay(rest)
        return throttled
    }

    fun status(): Int = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE

    /** The platform rate-limits headroom queries, so sample at most once per interval. */
    fun headroom(): Float {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt >= HEADROOM_SAMPLE_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val value = power?.getThermalHeadroom(FORECAST_SECONDS) ?: Float.NaN
                if (!value.isNaN()) lastHeadroom = value
            }
            lastSampleAt = now
        }
        return lastHeadroom
    }

    fun sampleMetrics(isThrottled: Boolean = false, overrideStatus: Int? = null): ThermalMetrics {
        val currentStatus = overrideStatus ?: status()
        val currentHeadroom = headroom()
        val tempC = getBatteryTempC()
        val label = when (currentStatus) {
            PowerManager.THERMAL_STATUS_NONE -> if (currentHeadroom > 0.7f) "Warm" else "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
            PowerManager.THERMAL_STATUS_MODERATE -> "Hot"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Normal"
        }
        return ThermalMetrics(
            status = currentStatus,
            statusLabel = label,
            headroom = currentHeadroom,
            batteryTempC = tempC,
            isThrottled = isThrottled || currentStatus >= PowerManager.THERMAL_STATUS_MODERATE
        )
    }

    fun getBatteryTempC(): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10f
        } catch (_: Throwable) {
            0f
        }
    }

    companion object {
        const val FORECAST_SECONDS = 10
        const val HEADROOM_SAMPLE_MS = 2_000L
        const val COOL_DOWN_POLL_MS = 5_000L
    }
}
