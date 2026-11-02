package com.metricas.tempotela.pipeline

import com.metricas.tempotela.time.SyncOutcome
import com.metricas.tempotela.time.TimeCorrector
import com.metricas.tempotela.time.TimeSyncer
import com.metricas.tempotela.time.TrustedClock
import com.metricas.tempotela.time.ZoneRecorder

/** Orquestra: hora pela internet → ingestão → correção retroativa → recálculo. */
class Pipeline(
    private val clock: TrustedClock,
    private val syncer: TimeSyncer,
    private val corrector: TimeCorrector,
    private val ingestor: Ingestor,
    private val recomputer: Recomputer,
    private val zones: ZoneRecorder,
) {
    suspend fun syncTime(trigger: String): SyncOutcome {
        val outcome = syncer.sync(trigger)
        if (outcome is SyncOutcome.Success) {
            val affected = corrector.correctAfterSync()
            if (affected != null) recomputer.recompute(affected)
        }
        return outcome
    }

    data class RefreshResult(val live: LiveState?, val ingest: Ingestor.Result, val sync: SyncOutcome?)

    suspend fun refresh(trigger: String, forceSync: Boolean = false): RefreshResult {
        zones.record()
        val sync = if (forceSync || clock.needsSync()) runCatching { syncTime(trigger) }.getOrNull() else null
        val ingest = ingestor.ingest(trigger)
        val live = recomputer.recompute(ingest.affectedFromUtc)
        return RefreshResult(live, ingest, sync)
    }

    suspend fun fullRecompute(settingsChanged: () -> Unit): LiveState? {
        settingsChanged()
        return recomputer.recompute(null)
    }
}
