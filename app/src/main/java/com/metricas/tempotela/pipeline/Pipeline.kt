package com.metricas.tempotela.pipeline

import com.metricas.tempotela.time.SyncOutcome
import com.metricas.tempotela.time.TimeCorrector
import com.metricas.tempotela.time.TimeSyncer
import com.metricas.tempotela.time.TrustedClock
import com.metricas.tempotela.time.ZoneRecorder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orquestra: hora pela internet → ingestão → correção retroativa → recálculo.
 *
 * Um único mutex serializa tudo (tela, workers e receivers), para que uma sincronização com
 * correção retroativa nunca rode no meio de uma ingestão e deixe eventos sem correção.
 */
class Pipeline(
    private val clock: TrustedClock,
    private val syncer: TimeSyncer,
    private val corrector: TimeCorrector,
    private val ingestor: Ingestor,
    private val recomputer: Recomputer,
    private val zones: ZoneRecorder,
) {
    private val mutex = Mutex()

    suspend fun syncTime(trigger: String): SyncOutcome = mutex.withLock { syncLocked(trigger) }

    private suspend fun syncLocked(trigger: String): SyncOutcome {
        val outcome = syncer.sync(trigger)
        if (outcome is SyncOutcome.Success) {
            val affected = corrector.correctAfterSync()
            if (affected != null) recomputer.recompute(affected)
        }
        return outcome
    }

    data class RefreshResult(val live: LiveState?, val ingest: Ingestor.Result, val sync: SyncOutcome?)

    suspend fun refresh(trigger: String, forceSync: Boolean = false): RefreshResult = mutex.withLock {
        zones.record()
        val sync = if (forceSync || clock.needsSync()) runCatching { syncLocked(trigger) }.getOrNull() else null
        val ingest = ingestor.ingest(trigger)
        // Se esta ingestão foi feita sem hora verificada mas já existe âncora da internet
        // neste boot, corrige na hora (não espera a próxima sincronização).
        val corrected = if (ingest.ok && clock.anchorForCurrentBoot() != null) corrector.correctAfterSync() else null
        val affected = listOfNotNull(ingest.affectedFromUtc, corrected).minOrNull()
        val live = recomputer.recompute(affected)
        RefreshResult(live, ingest, sync)
    }

    suspend fun fullRecompute(settingsChanged: () -> Unit): LiveState? = mutex.withLock {
        settingsChanged()
        recomputer.recompute(null)
    }
}
