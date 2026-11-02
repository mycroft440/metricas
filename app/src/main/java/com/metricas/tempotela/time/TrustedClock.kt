package com.metricas.tempotela.time

import android.content.Context
import com.metricas.core.model.TimeQuality
import com.metricas.core.time.TimeAnchor
import com.metricas.core.time.TimeMath
import com.metricas.core.time.TimeSource
import com.metricas.tempotela.platform.DeviceClock

/**
 * Única fonte de "agora" do app: hora real obtida pela internet, extrapolada pelo relógio
 * monotônico (`elapsedRealtime`), que não muda quando o usuário altera o relógio.
 *
 * Leitura síncrona (SharedPreferences em memória), segura na thread principal.
 */
class TrustedClock(private val context: Context) {
    private val prefs = context.getSharedPreferences("trusted_clock", Context.MODE_PRIVATE)

    @Volatile
    private var cached: TimeAnchor? = null

    data class Reading(
        val trueNowUtc: Long,
        val deviceWallMs: Long,
        val elapsedMs: Long,
        val bootCount: Int,
        /** relógio_do_aparelho − hora_real. Positivo = aparelho adiantado. */
        val deviceOffsetMs: Long,
        val errorMs: Long?,
        val quality: TimeQuality,
        val source: TimeSource?,
        val server: String?,
        val anchorAgeMs: Long?,
    )

    fun anchor(): TimeAnchor? {
        cached?.let { return it }
        if (!prefs.contains(KEY_TRUE)) return null
        val source = runCatching { TimeSource.valueOf(prefs.getString(KEY_SOURCE, null) ?: "") }.getOrNull()
            ?: return null
        val a = TimeAnchor(
            bootCount = prefs.getInt(KEY_BOOT, -1),
            elapsedMs = prefs.getLong(KEY_ELAPSED, 0),
            trueUtcMs = prefs.getLong(KEY_TRUE, 0),
            errorMs = prefs.getLong(KEY_ERROR, 0),
            source = source,
            server = prefs.getString(KEY_SERVER, "") ?: "",
        )
        cached = a
        return a
    }

    fun saveAnchor(anchor: TimeAnchor, deviceOffsetMs: Long) {
        cached = anchor
        prefs.edit()
            .putInt(KEY_BOOT, anchor.bootCount)
            .putLong(KEY_ELAPSED, anchor.elapsedMs)
            .putLong(KEY_TRUE, anchor.trueUtcMs)
            .putLong(KEY_ERROR, anchor.errorMs)
            .putString(KEY_SOURCE, anchor.source.name)
            .putString(KEY_SERVER, anchor.server)
            .putLong(KEY_LAST_OFFSET, deviceOffsetMs)
            .apply()
    }

    /** Âncora utilizável neste boot (o `elapsedRealtime` zera a cada reinício). */
    fun anchorForCurrentBoot(bootCount: Int, elapsedMs: Long): TimeAnchor? {
        val a = anchor() ?: return null
        if (a.bootCount != bootCount) return null
        if (elapsedMs < a.elapsedMs - 60_000) return null
        return a
    }

    fun read(): Reading {
        val clock = DeviceClock.read(context)
        val a = anchorForCurrentBoot(clock.bootCount, clock.elapsedMs)
        if (a != null) {
            val trueNow = TimeMath.trueAt(a, clock.elapsedMs)
            val error = TimeMath.errorAt(a, clock.elapsedMs)
            val offset = clock.wallMs - trueNow
            val quality = TimeMath.quality(error)
            if (quality == TimeQuality.VERIFICADA) rememberOffset(offset)
            return Reading(
                trueNowUtc = trueNow,
                deviceWallMs = clock.wallMs,
                elapsedMs = clock.elapsedMs,
                bootCount = clock.bootCount,
                deviceOffsetMs = offset,
                errorMs = error,
                quality = quality,
                source = a.source,
                server = a.server,
                anchorAgeMs = clock.elapsedMs - a.elapsedMs,
            )
        }
        if (prefs.contains(KEY_LAST_OFFSET)) {
            // Sem sincronização neste boot: supõe que o desvio do relógio não mudou.
            val offset = prefs.getLong(KEY_LAST_OFFSET, 0)
            return Reading(
                trueNowUtc = clock.wallMs - offset,
                deviceWallMs = clock.wallMs,
                elapsedMs = clock.elapsedMs,
                bootCount = clock.bootCount,
                deviceOffsetMs = offset,
                errorMs = null,
                quality = TimeQuality.INFERIDA,
                source = null,
                server = null,
                anchorAgeMs = null,
            )
        }
        return Reading(
            trueNowUtc = clock.wallMs,
            deviceWallMs = clock.wallMs,
            elapsedMs = clock.elapsedMs,
            bootCount = clock.bootCount,
            deviceOffsetMs = 0,
            errorMs = null,
            quality = TimeQuality.NAO_VERIFICADA,
            source = null,
            server = null,
            anchorAgeMs = null,
        )
    }

    fun now(): Long = read().trueNowUtc

    /** Precisa sincronizar: sem âncora neste boot ou âncora com mais de 6 h. */
    fun needsSync(): Boolean {
        val clock = DeviceClock.read(context)
        val a = anchorForCurrentBoot(clock.bootCount, clock.elapsedMs) ?: return true
        return clock.elapsedMs - a.elapsedMs > SYNC_MAX_AGE_MS
    }

    private var lastSavedOffset: Long? = null

    private fun rememberOffset(offset: Long) {
        val previous = lastSavedOffset
        if (previous != null && kotlin.math.abs(previous - offset) < 1_000) return
        lastSavedOffset = offset
        prefs.edit().putLong(KEY_LAST_OFFSET, offset).apply()
    }

    companion object {
        const val SYNC_MAX_AGE_MS = 6L * 60 * 60 * 1000
        private const val KEY_BOOT = "anchor_boot"
        private const val KEY_ELAPSED = "anchor_elapsed"
        private const val KEY_TRUE = "anchor_true"
        private const val KEY_ERROR = "anchor_error"
        private const val KEY_SOURCE = "anchor_source"
        private const val KEY_SERVER = "anchor_server"
        private const val KEY_LAST_OFFSET = "last_device_offset"
    }
}
