package com.lyb.mysecretary.device

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.Executors

/**
 * Chooses the whisper thread count from the current thermal state and tracks the peak state
 * during a job. SEVERE or worse aborts the job (see Pipeline).
 */
class ThermalGuard(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)

    fun current(): Int = power.currentThermalStatus

    /** 4 threads normally, 2 once the device reports MODERATE heat. */
    fun threadsFor(status: Int): Int = if (status >= PowerManager.THERMAL_STATUS_MODERATE) 2 else 4

    fun watch(onChange: (Int) -> Unit): AutoCloseable {
        val executor = Executors.newSingleThreadExecutor()
        val listener = PowerManager.OnThermalStatusChangedListener { onChange(it) }
        power.addThermalStatusListener(executor, listener)
        return AutoCloseable {
            power.removeThermalStatusListener(listener)
            executor.shutdown()
        }
    }

    companion object {
        fun label(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "정상"
            PowerManager.THERMAL_STATUS_LIGHT -> "약간 따뜻"
            PowerManager.THERMAL_STATUS_MODERATE -> "보통 발열"
            PowerManager.THERMAL_STATUS_SEVERE -> "심한 발열"
            PowerManager.THERMAL_STATUS_CRITICAL -> "위험"
            else -> "과열"
        }
    }
}
