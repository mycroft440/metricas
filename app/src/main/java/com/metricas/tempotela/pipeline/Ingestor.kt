package com.metricas.tempotela.pipeline

import android.content.Context
import com.metricas.core.ingest.DeviceEvent
import com.metricas.core.ingest.EventDedupe
import com.metricas.core.ingest.Observation
import com.metricas.core.ingest.StoredEventKey
import com.metricas.tempotela.data.AppSettings
import com.metricas.tempotela.data.db.AppDatabase
import com.metricas.tempotela.data.db.IngestRunEntity
import com.metricas.tempotela.data.db.PermissionLogEntity
import com.metricas.tempotela.data.db.RawEventEntity
import com.metricas.tempotela.platform.DeviceState
import com.metricas.tempotela.platform.UsageAccess
import com.metricas.tempotela.platform.UsageEventSource
import com.metricas.tempotela.time.ClockEpochTracker
import com.metricas.tempotela.time.TrustedClock
import com.metricas.tempotela.data.db.ClockEventEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Copia os eventos do sistema para o banco local antes que expirem (~10 dias),
 * convertendo cada carimbo para a hora real (Seções 3.4 e 6 do plano).
 */
class Ingestor(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: AppSettings,
    private val clock: TrustedClock,
    private val epochs: ClockEpochTracker,
    private val usage: UsageEventSource,
) {
    private val mutex = Mutex()

    data class Result(
        val ok: Boolean,
        val message: String,
        val eventsRead: Int = 0,
        val eventsNew: Int = 0,
        val affectedFromUtc: Long? = null,
    )

    suspend fun ingest(trigger: String): Result = mutex.withLock {
        withContext(Dispatchers.IO) { ingestLocked(trigger) }
    }

    private suspend fun ingestLocked(trigger: String): Result {
        val granted = UsageAccess.has(context)
        logPermission(granted)
        if (!granted) return Result(false, "Acesso ao uso não concedido")
        if (!DeviceState.isUserUnlocked(context)) return Result(false, "Aparelho ainda não desbloqueado após o boot")

        val epoch = epochs.check(trigger) { clock.now() }
        val reading = clock.read()
        val offset = reading.deviceOffsetMs
        val endDevice = reading.deviceWallMs
        val previousWatermark = settings.watermarkUtc

        var beginDevice = previousWatermark?.let { it + offset - OVERLAP_MS } ?: (endDevice - BACKFILL_MS)
        if (beginDevice >= endDevice || endDevice - beginDevice > BACKFILL_MS) beginDevice = endDevice - BACKFILL_MS

        var run = IngestRunEntity(
            triggerName = trigger,
            startedAtUtc = reading.trueNowUtc,
            elapsedMs = reading.elapsedMs,
            bootCount = reading.bootCount,
            epochId = epoch.epochId,
            deviceWallMs = reading.deviceWallMs,
            offsetMs = offset,
            offsetErrorMs = reading.errorMs ?: -1L,
            quality = reading.quality.name,
            queryBeginDevice = beginDevice,
            queryEndDevice = endDevice,
            observedStartUtc = beginDevice - offset,
            observedEndUtc = endDevice - offset,
            eventsRead = 0,
            eventsNew = 0,
            ok = false,
            error = null,
        )
        val runId = db.ingestRunDao().insert(run)
        run = run.copy(id = runId)

        return try {
            val raw = usage.query(beginDevice, endDevice)
            // Sem permissão o Android devolve lista vazia (não erro): não avança a marca d'água
            // nem marca o período como observado com "0 uso".
            if (!UsageAccess.has(context)) throw IllegalStateException("acesso ao uso revogado durante a leitura")
            val fresh: List<DeviceEvent> = if (raw.isEmpty()) {
                emptyList()
            } else {
                val minUtc = raw.minOf { it.tsDevice } - offset - SHIFT_SEARCH_MS
                val maxUtc = raw.maxOf { it.tsDevice } - offset + DEDUPE_MARGIN_MS
                val existing = db.rawEventDao().eventsBetween(minUtc, maxUtc)
                    .map { StoredEventKey(it.tsDevice, it.tsUtc, it.type, it.epochId) }
                // Eventos já guardados que deveriam voltar nesta leitura (sobreposição).
                val overlapEnd = previousWatermark ?: Long.MIN_VALUE
                val expected = existing.filter { it.tsUtc >= beginDevice - offset && it.tsUtc <= overlapEnd }
                val shift = EventDedupe.detectHistoryShift(raw, expected)
                if (shift != 0L) {
                    db.clockEventDao().insert(
                        ClockEventEntity(
                            detectedAtUtc = reading.trueNowUtc,
                            kind = "HISTORICO_DESLOCADO",
                            deltaMs = shift,
                            oldZone = null,
                            newZone = null,
                        ),
                    )
                }
                EventDedupe.filterNew(raw, offset, epoch.epochId, existing, historyShiftMs = shift)
            }
            val entities = fresh.map {
                RawEventEntity(
                    tsDevice = it.tsDevice,
                    tsUtc = it.tsDevice - offset,
                    offsetMs = offset,
                    offsetErrorMs = reading.errorMs ?: -1L,
                    quality = reading.quality.name,
                    epochId = epoch.epochId,
                    type = it.type,
                    ingestRunId = runId,
                )
            }
            val inserted = if (entities.isEmpty()) 0 else db.rawEventDao().insertAll(entities).count { it != -1L }
            val oldestUtc = raw.minOfOrNull { it.tsDevice }?.minus(offset)
            val observed = Observation.observedInterval(
                queryBeginUtc = beginDevice - offset,
                queryEndUtc = endDevice - offset,
                previousWatermarkUtc = previousWatermark,
                oldestEventUtc = oldestUtc,
            )
            db.ingestRunDao().update(
                run.copy(
                    observedStartUtc = observed.startUtc,
                    observedEndUtc = observed.endUtc,
                    eventsRead = raw.size,
                    eventsNew = inserted,
                    ok = true,
                ),
            )
            settings.watermarkUtc = observed.endUtc
            Result(
                ok = true,
                message = "$inserted eventos novos",
                eventsRead = raw.size,
                eventsNew = inserted,
                affectedFromUtc = entities.minOfOrNull { it.tsUtc },
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                db.ingestRunDao().update(run.copy(ok = false, error = "cancelada"))
            }
            throw e
        } catch (e: Exception) {
            db.ingestRunDao().update(run.copy(ok = false, error = e.javaClass.simpleName + ": " + e.message))
            Result(false, "Falha ao ler eventos: ${e.message}")
        }
    }

    private suspend fun logPermission(granted: Boolean) {
        if (settings.lastPermissionGranted == granted) return
        settings.lastPermissionGranted = granted
        db.permissionLogDao().insert(PermissionLogEntity(tsUtc = clock.now(), granted = granted))
    }

    private companion object {
        const val OVERLAP_MS = 30L * 60 * 1000
        const val BACKFILL_MS = 11L * 24 * 60 * 60 * 1000
        const val DEDUPE_MARGIN_MS = 10_000L

        /** Janela para procurar eventos já guardados que o Android possa ter deslocado. */
        const val SHIFT_SEARCH_MS = 2L * 24 * 60 * 60 * 1000
    }
}
