package com.metricas.tempotela.time

import android.content.Context
import com.metricas.core.model.TimeQuality
import com.metricas.core.time.TimeAnchor
import com.metricas.core.time.TimeMath
import com.metricas.core.time.TimeSample
import com.metricas.tempotela.data.db.AppDatabase
import com.metricas.tempotela.data.db.ClockEpochEntity
import com.metricas.tempotela.data.db.ClockEventEntity
import com.metricas.tempotela.data.db.TimeSyncEntity
import com.metricas.tempotela.data.db.ZoneHistoryEntity
import android.content.SharedPreferences
import com.metricas.tempotela.platform.DeviceEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.abs

/**
 * Detecta "épocas de relógio": períodos em que a relação entre o relógio do aparelho e o
 * `elapsedRealtime` é constante. Muda no boot ou quando o relógio é ajustado (> 2 s).
 */
class ClockEpochTracker(
    private val prefs: SharedPreferences,
    private val db: AppDatabase,
    private val env: DeviceEnvironment,
) {
    constructor(context: Context, db: AppDatabase, env: DeviceEnvironment) :
        this(context.getSharedPreferences("clock_epoch", Context.MODE_PRIVATE), db, env)

    private val mutex = Mutex()

    data class Check(
        val epochId: Long,
        val bootCount: Int,
        val changed: Boolean,
        val reason: String?,
        val deltaMs: Long,
    )

    suspend fun check(trigger: String, nowTrueUtc: () -> Long): Check = mutex.withLock {
        val clock = env.clock()
        val prevBoot = prefs.getInt(KEY_BOOT, Int.MIN_VALUE)
        val prevElapsed = prefs.getLong(KEY_ELAPSED, 0)
        val prevWall = prefs.getLong(KEY_WALL, 0)
        val currentEpoch = prefs.getLong(KEY_EPOCH, 0)

        var reason: String? = null
        var delta = 0L
        if (currentEpoch == 0L || prevBoot != clock.bootCount || clock.elapsedMs < prevElapsed) {
            reason = "BOOT"
        } else {
            delta = TimeMath.clockJumpMs(prevElapsed, prevWall, clock.elapsedMs, clock.wallMs)
            if (TimeMath.isClockJump(delta, clock.elapsedMs - prevElapsed)) reason = "AJUSTE_DE_RELOGIO"
        }

        var epochId = currentEpoch
        if (reason != null) {
            epochId = db.clockEpochDao().insert(
                ClockEpochEntity(
                    bootCount = clock.bootCount,
                    startElapsedMs = clock.elapsedMs,
                    startDeviceWallMs = clock.wallMs,
                    reason = reason,
                    jumpDeltaMs = delta,
                    triggerName = trigger,
                ),
            )
            if (reason == "AJUSTE_DE_RELOGIO") {
                db.clockEventDao().insert(
                    ClockEventEntity(
                        detectedAtUtc = nowTrueUtc(),
                        kind = "AJUSTE_DE_RELOGIO",
                        deltaMs = delta,
                        oldZone = null,
                        newZone = null,
                    ),
                )
            }
        }
        prefs.edit()
            .putInt(KEY_BOOT, clock.bootCount)
            .putLong(KEY_ELAPSED, clock.elapsedMs)
            .putLong(KEY_WALL, clock.wallMs)
            .putLong(KEY_EPOCH, epochId)
            .apply()
        Check(epochId, clock.bootCount, reason != null, reason, delta)
    }

    private companion object {
        const val KEY_BOOT = "boot"
        const val KEY_ELAPSED = "elapsed"
        const val KEY_WALL = "wall"
        const val KEY_EPOCH = "epoch"
    }
}

sealed class SyncOutcome {
    data class Success(val sample: TimeSample, val disagreementMs: Long, val samples: Int) : SyncOutcome()
    data class Failure(val reason: String) : SyncOutcome()
}

/** Consulta as fontes de hora em cascata, escolhe a melhor e grava a âncora. */
class TimeSyncer(
    private val env: DeviceEnvironment,
    private val db: AppDatabase,
    private val clock: TrustedClock,
    private val epochs: ClockEpochTracker,
    private val sources: TimeSources,
) {
    private val mutex = Mutex()

    suspend fun sync(trigger: String): SyncOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val epoch = epochs.check(trigger) { clock.now() }
            if (!env.hasInternet()) {
                return@withContext SyncOutcome.Failure("sem conexão com a internet")
            }
            val samples = ArrayList<TimeSample>()
            val errors = ArrayList<String>()

            fun attempt(name: String, block: () -> TimeSample?) {
                try {
                    val sample = block()
                    if (sample != null) samples += sample else errors += "$name: indisponível"
                } catch (e: Exception) {
                    errors += "$name: ${e.javaClass.simpleName}"
                }
            }

            // Fontes locais/baratas primeiro; depois rede. Sempre tenta ter ao menos uma
            // fonte autenticada (TrustedTime ou HTTPS) para validar NTP, que não é autenticado.
            attempt("TrustedTime") { sources.trustedTime() }
            attempt("Relógio de rede") { sources.networkClock() }
            var ntpTried = false
            if (samples.none { it.errorMs <= 200 }) {
                ntpTried = true
                attempt("NTP") { sources.sntp() }
            }
            if (samples.none { it.source.authenticated }) attempt("HTTPS") { sources.httpsDate() }
            if (samples.size < 2 && !ntpTried) attempt("NTP") { sources.sntp() }

            val choice = TimeMath.choose(samples)
            if (choice == null) {
                val reason = errors.joinToString("; ").ifEmpty { "nenhuma fonte respondeu" }
                val c = env.clock()
                db.timeSyncDao().insert(
                    TimeSyncEntity(
                        triggerName = trigger,
                        elapsedMs = c.elapsedMs,
                        bootCount = c.bootCount,
                        epochId = epoch.epochId,
                        deviceWallMs = c.wallMs,
                        trueUtcMs = 0,
                        deviceOffsetMs = 0,
                        errorMs = -1,
                        source = "-",
                        server = "-",
                        rttMs = null,
                        disagreementMs = null,
                        chosen = false,
                        ok = false,
                        error = reason,
                    ),
                )
                return@withContext SyncOutcome.Failure(reason)
            }

            for (s in samples) {
                db.timeSyncDao().insert(
                    TimeSyncEntity(
                        triggerName = trigger,
                        elapsedMs = s.elapsedMs,
                        bootCount = epoch.bootCount,
                        epochId = epoch.epochId,
                        deviceWallMs = s.deviceWallMs,
                        trueUtcMs = s.trueUtcMs,
                        deviceOffsetMs = s.deviceOffsetMs,
                        errorMs = s.errorMs,
                        source = s.source.name,
                        server = s.server,
                        rttMs = s.rttMs,
                        disagreementMs = if (s === choice.best) choice.disagreementMs else abs(s.elapsedToUtcMs - choice.best.elapsedToUtcMs),
                        chosen = s === choice.best,
                        ok = TimeMath.isPlausible(s) && choice.rejected.none { it === s },
                        error = when {
                            !TimeMath.isPlausible(s) -> "descartada: hora implausível"
                            choice.rejected.any { it === s } -> "descartada: discorda das outras fontes"
                            else -> null
                        },
                    ),
                )
            }

            val best = choice.best
            clock.saveAnchor(
                TimeAnchor(
                    bootCount = epoch.bootCount,
                    elapsedMs = best.elapsedMs,
                    trueUtcMs = best.trueUtcMs,
                    errorMs = best.errorMs,
                    source = best.source,
                    server = best.server,
                    corroborated = choice.corroborated,
                ),
                best.deviceOffsetMs,
            )
            SyncOutcome.Success(best, choice.disagreementMs, samples.size)
        }
    }
}

/**
 * Correção retroativa (Seção 3.5): quando chega a primeira sincronização de um boot, as
 * ingestões desse boot feitas sem hora verificada são reconvertidas.
 */
class TimeCorrector(
    private val db: AppDatabase,
    private val clock: TrustedClock,
    private val settings: com.metricas.tempotela.data.AppSettings,
) {

    /** @return menor hora real afetada, ou null se nada mudou. */
    suspend fun correctAfterSync(): Long? = withContext(Dispatchers.IO) {
        val anchor = clock.anchor() ?: return@withContext null
        val runs = db.ingestRunDao().unverifiedRunsForBoot(anchor.bootCount)
        var minAffected: Long? = null
        for (run in runs) {
            val trueAtRun = TimeMath.trueAt(anchor, run.elapsedMs)
            val error = TimeMath.errorAt(anchor, run.elapsedMs)
            val quality = TimeMath.quality(error, anchor.corroborated)
            val newOffset = run.deviceWallMs - trueAtRun
            val shift = run.offsetMs - newOffset
            if (shift == 0L && quality.name == run.quality) continue
            val before = db.rawEventDao().minTsForRun(run.id)
            db.rawEventDao().reoffsetRun(run.id, newOffset, error, quality.name)
            db.ingestRunDao().update(
                run.copy(
                    offsetMs = newOffset,
                    offsetErrorMs = error,
                    quality = quality.name,
                    observedStartUtc = run.observedStartUtc + shift,
                    observedEndUtc = run.observedEndUtc + shift,
                ),
            )
            val after = db.rawEventDao().minTsForRun(run.id)
            listOfNotNull(before, after).minOrNull()?.let { m ->
                minAffected = minOf(minAffected ?: Long.MAX_VALUE, m)
            }
        }
        if (minAffected != null) {
            // A marca d'água (fim da última leitura, em hora real) acompanha a correção.
            db.ingestRunDao().lastOk()?.let { settings.watermarkUtc = it.observedEndUtc }
        }
        minAffected
    }
}

/** Registra o fuso do aparelho ao longo do tempo (usado no modo "seguir o aparelho"). */
class ZoneRecorder(private val db: AppDatabase, private val clock: TrustedClock) {
    suspend fun record(kindIfChanged: String = "MUDANCA_DE_FUSO"): Boolean = withContext(Dispatchers.IO) {
        val current = ZoneId.systemDefault().id
        val latest = db.zoneHistoryDao().latest()
        if (latest?.zoneId == current) return@withContext false
        val now = clock.now()
        db.zoneHistoryDao().insert(ZoneHistoryEntity(effectiveFromUtc = if (latest == null) 0L else now, zoneId = current))
        if (latest != null) {
            db.clockEventDao().insert(
                ClockEventEntity(
                    detectedAtUtc = now,
                    kind = kindIfChanged,
                    deltaMs = null,
                    oldZone = latest.zoneId,
                    newZone = current,
                ),
            )
        }
        true
    }
}

fun TimeQuality.label(): String = when (this) {
    TimeQuality.VERIFICADA -> "verificada"
    TimeQuality.INFERIDA -> "inferida"
    TimeQuality.NAO_VERIFICADA -> "não verificada"
}
