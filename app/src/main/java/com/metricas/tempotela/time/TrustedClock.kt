package com.metricas.tempotela.time

import android.content.Context
import com.metricas.core.model.TimeQuality
import com.metricas.core.time.ClockLogic
import com.metricas.core.time.ClockReading
import com.metricas.core.time.ClockSnapshot
import com.metricas.core.time.InferredAnchor
import com.metricas.core.time.TimeAnchor
import com.metricas.core.time.TimeSource
import com.metricas.tempotela.platform.DeviceClock

/**
 * Única fonte de "agora" do app: hora real obtida pela internet, extrapolada pelo relógio
 * monotônico (`elapsedRealtime`), que não muda quando o usuário altera o relógio.
 *
 * Sem internet neste boot, usa uma âncora estimada (último desvio verificado), também
 * presa ao relógio monotônico: alterar o relógio do aparelho não muda a hora do app.
 *
 * Leitura síncrona (SharedPreferences em memória), segura na thread principal.
 */
class TrustedClock(private val context: Context) {
    private val prefs = context.getSharedPreferences("trusted_clock", Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var cachedNetwork: TimeAnchor? = null

    @Volatile
    private var cachedInferred: InferredAnchor? = null

    @Volatile
    private var lastSavedOffset: Long? = null

    fun anchor(): TimeAnchor? {
        cachedNetwork?.let { return it }
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
            corroborated = prefs.getBoolean(KEY_CORROBORATED, true),
        )
        cachedNetwork = a
        return a
    }

    private fun inferredAnchor(): InferredAnchor? {
        cachedInferred?.let { return it }
        if (!prefs.contains(KEY_INF_TRUE)) return null
        val a = InferredAnchor(
            bootCount = prefs.getInt(KEY_INF_BOOT, -1),
            elapsedMs = prefs.getLong(KEY_INF_ELAPSED, 0),
            trueUtcMs = prefs.getLong(KEY_INF_TRUE, 0),
            fromKnownOffset = prefs.getBoolean(KEY_INF_KNOWN, false),
        )
        cachedInferred = a
        return a
    }

    private fun lastVerifiedOffset(): Long? =
        if (prefs.contains(KEY_LAST_OFFSET)) prefs.getLong(KEY_LAST_OFFSET, 0) else null

    fun saveAnchor(anchor: TimeAnchor, deviceOffsetMs: Long) {
        synchronized(lock) {
            cachedNetwork = anchor
            val editor = prefs.edit()
                .putInt(KEY_BOOT, anchor.bootCount)
                .putLong(KEY_ELAPSED, anchor.elapsedMs)
                .putLong(KEY_TRUE, anchor.trueUtcMs)
                .putLong(KEY_ERROR, anchor.errorMs)
                .putString(KEY_SOURCE, anchor.source.name)
                .putString(KEY_SERVER, anchor.server)
                .putBoolean(KEY_CORROBORATED, anchor.corroborated)
            if (anchor.corroborated) editor.putLong(KEY_LAST_OFFSET, deviceOffsetMs)
            editor.apply()
        }
    }

    /** Âncora da internet utilizável neste boot. */
    fun anchorForCurrentBoot(): TimeAnchor? = ClockLogic.usable(anchor(), snapshot())

    fun snapshot(): ClockSnapshot {
        val c = DeviceClock.read(context)
        return ClockSnapshot(c.bootCount, c.elapsedMs, c.wallMs)
    }

    fun read(): ClockReading = synchronized(lock) {
        val result = ClockLogic.read(anchor(), inferredAnchor(), lastVerifiedOffset(), snapshot())
        result.newInferredAnchor?.let { a ->
            cachedInferred = a
            prefs.edit()
                .putInt(KEY_INF_BOOT, a.bootCount)
                .putLong(KEY_INF_ELAPSED, a.elapsedMs)
                .putLong(KEY_INF_TRUE, a.trueUtcMs)
                .putBoolean(KEY_INF_KNOWN, a.fromKnownOffset)
                .apply()
        }
        val reading = result.reading
        if (!reading.inferred && reading.quality == TimeQuality.VERIFICADA) rememberOffset(reading.deviceOffsetMs)
        reading
    }

    fun now(): Long = read().trueNowUtc

    /** Precisa sincronizar: sem âncora da internet neste boot ou âncora com mais de 6 h. */
    fun needsSync(): Boolean {
        val now = snapshot()
        val a = ClockLogic.usable(anchor(), now) ?: return true
        return now.elapsedMs - a.elapsedMs > SYNC_MAX_AGE_MS || !a.corroborated
    }

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
        private const val KEY_CORROBORATED = "anchor_corroborated"
        private const val KEY_LAST_OFFSET = "last_device_offset"
        private const val KEY_INF_BOOT = "inferred_boot"
        private const val KEY_INF_ELAPSED = "inferred_elapsed"
        private const val KEY_INF_TRUE = "inferred_true"
        private const val KEY_INF_KNOWN = "inferred_known_offset"
    }
}
