package com.navaneeth.filer.data.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.navaneeth.filer.data.inference.EmbedderEngine
import com.navaneeth.filer.data.worker.ThermalGovernor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SystemResourceUsage(
    val cpuPercentage: Float = 0f,
    val appRamMb: Long = 0L,
    val heapRamMb: Long = 0L,
    val nativeRamMb: Long = 0L,
    val deviceAvailableRamMb: Long = 0L,
    val deviceTotalRamMb: Long = 0L,
    val batteryTempC: Float = 0f,
    val thermalStatusLabel: String = "Normal",
    val thermalStatusLevel: Int = 0,
    val thermalHeadroom: Float = 0f,
    val acceleratorName: String = "GPU",
    val isInferring: Boolean = false,
    val isThrottled: Boolean = false
)

/**
 * Monitors live device and app resource usage during indexing and search:
 * - Process CPU utilization calculated via [Process.getElapsedCpuTime]
 * - JVM Heap and Native / LiteRT model allocated RAM
 * - Real hardware battery temperature and Android OS thermal headroom
 * - Accelerator execution status (GPU vs CPU delegate and active inference)
 */
class SystemResourceMonitor(
    private val context: Context,
    private val thermalGovernor: ThermalGovernor,
    private val embedder: EmbedderEngine
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    private var lastCpuTime = Process.getElapsedCpuTime()
    private var lastSampleRealtime = SystemClock.elapsedRealtime()
    private var lastCpuPercent = 0f

    private val _usage = MutableStateFlow(SystemResourceUsage())
    val usage: StateFlow<SystemResourceUsage> = _usage.asStateFlow()

    init {
        _usage.value = sampleNow()
        scope.launch {
            while (isActive) {
                _usage.value = sampleNow()
                delay(1_200L) // Sample every 1.2s
            }
        }
    }

    fun sampleNow(): SystemResourceUsage {
        val now = SystemClock.elapsedRealtime()
        val cpuTime = Process.getElapsedCpuTime()
        val timeDelta = now - lastSampleRealtime
        val cpuDelta = cpuTime - lastCpuTime

        val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpuPercent = if (timeDelta > 200) {
            val calc = ((cpuDelta.toFloat() / (timeDelta * numCores)) * 100f).coerceIn(0f, 100f)
            lastCpuPercent = calc
            calc
        } else {
            lastCpuPercent
        }

        lastCpuTime = cpuTime
        lastSampleRealtime = now

        // Memory
        val runtime = Runtime.getRuntime()
        val heapBytes = runtime.totalMemory() - runtime.freeMemory()
        val nativeBytes = Debug.getNativeHeapAllocatedSize()
        val appRamMb = (heapBytes + nativeBytes) / (1024 * 1024)
        val heapMb = heapBytes / (1024 * 1024)
        val nativeMb = nativeBytes / (1024 * 1024)

        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)

        // Thermal
        val thermal = thermalGovernor.sampleMetrics()

        return SystemResourceUsage(
            cpuPercentage = cpuPercent,
            appRamMb = appRamMb,
            heapRamMb = heapMb,
            nativeRamMb = nativeMb,
            deviceAvailableRamMb = availMb,
            deviceTotalRamMb = totalMb,
            batteryTempC = thermal.batteryTempC,
            thermalStatusLabel = thermal.statusLabel,
            thermalStatusLevel = thermal.status,
            thermalHeadroom = thermal.headroom,
            acceleratorName = EmbedderEngine.activeDelegateName,
            isInferring = embedder.isInferring.value,
            isThrottled = thermal.isThrottled
        )
    }
}
