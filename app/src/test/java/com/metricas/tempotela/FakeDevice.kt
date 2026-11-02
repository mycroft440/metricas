package com.metricas.tempotela

import com.metricas.core.ingest.DeviceEvent
import com.metricas.core.model.EventType.DEVICE_SHUTDOWN
import com.metricas.core.model.EventType.DEVICE_STARTUP
import com.metricas.core.model.EventType.KEYGUARD_HIDDEN
import com.metricas.core.model.EventType.KEYGUARD_SHOWN
import com.metricas.core.model.EventType.SCREEN_INTERACTIVE
import com.metricas.core.model.EventType.SCREEN_NON_INTERACTIVE
import com.metricas.core.time.ClockSnapshot
import com.metricas.core.time.TimeSample
import com.metricas.core.time.TimeSource
import com.metricas.tempotela.platform.DeviceEnvironment
import com.metricas.tempotela.platform.EventSource
import com.metricas.tempotela.time.TimeSources

/**
 * Aparelho simulado que reproduz o comportamento do Android relevante para as métricas:
 * relógio do aparelho independente da hora real, reboots, retenção de ~10 dias e o
 * deslocamento de todo o histórico quando o relógio é alterado (UsageStatsDatabase.onTimeChanged).
 */
class FakeDevice(startTrueUtc: Long) : DeviceEnvironment, EventSource, TimeSources {
    var trueUtc = startTrueUtc
    var elapsed = 60_000L
    var bootCount = 1
    var deviceOffset = 0L
    var usageAccess = true
    var internet = true
    var secure = true
    var trustedTimeAvailable = true
    var httpsAvailable = true
    var spoofedNtpMs: Long? = null
    var retentionMs = 10L * DAY
    val history = ArrayList<DeviceEvent>()

    fun wall(): Long = trueUtc + deviceOffset

    override fun hasUsageAccess() = usageAccess
    override fun isUserUnlocked() = true
    override fun isDeviceSecure() = secure
    override fun hasInternet() = internet
    override fun clock() = ClockSnapshot(bootCount, elapsed, wall())

    fun advance(ms: Long) {
        trueUtc += ms
        elapsed += ms
    }

    fun emit(type: Int) {
        history += DeviceEvent(wall(), type)
    }

    fun lock() {
        emit(SCREEN_NON_INTERACTIVE)
        emit(KEYGUARD_SHOWN)
    }

    fun unlock() {
        emit(SCREEN_INTERACTIVE)
        emit(KEYGUARD_HIDDEN)
    }

    fun use(ms: Long) {
        unlock()
        advance(ms)
        lock()
    }

    /** Relógio alterado (usuário ou rede): o Android desloca todo o histórico guardado. */
    fun changeClock(deltaMs: Long) {
        deviceOffset += deltaMs
        for (i in history.indices) history[i] = history[i].copy(tsDevice = history[i].tsDevice + deltaMs)
    }

    /** Desliga por [offMs] e liga de novo; [rtcErrorMs] = erro do relógio de hardware no boot. */
    fun reboot(offMs: Long, rtcErrorMs: Long = 0) {
        val shutdownAt = wall()
        trueUtc += offMs
        bootCount++
        elapsed = 5_000
        deviceOffset += rtcErrorMs
        history += DeviceEvent(shutdownAt, DEVICE_SHUTDOWN)
        history += DeviceEvent(wall(), DEVICE_STARTUP)
    }

    override fun query(beginDevice: Long, endDevice: Long): List<DeviceEvent> {
        val oldest = wall() - retentionMs
        return history.filter { it.tsDevice >= beginDevice && it.tsDevice < endDevice && it.tsDevice >= oldest }
    }

    override fun trustedTime(): TimeSample? =
        if (internet && trustedTimeAvailable) TimeSample(TimeSource.TRUSTED_TIME, "fake", elapsed, trueUtc, wall(), 20) else null

    override fun networkClock(): TimeSample? = null

    override fun sntp(): TimeSample? {
        val spoof = spoofedNtpMs ?: return null
        return if (internet) TimeSample(TimeSource.SNTP, "ntp-falso", elapsed, trueUtc + spoof, wall(), 5) else null
    }

    override fun httpsDate(): TimeSample? =
        if (internet && httpsAvailable) TimeSample(TimeSource.HTTPS_DATE, "https", elapsed, trueUtc, wall(), 600) else null

    companion object {
        const val MIN = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }
}
