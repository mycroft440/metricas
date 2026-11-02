package com.metricas.tempotela.pipeline

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.metricas.core.coverage.Coverage
import com.metricas.core.day.DayAggregator
import com.metricas.core.day.DayResolver
import com.metricas.core.day.Invariants
import com.metricas.core.session.SessionFlags
import com.metricas.core.model.Interval
import com.metricas.core.model.TimeQuality
import com.metricas.core.model.UsageEvent
import com.metricas.core.session.Sessionizer
import com.metricas.tempotela.data.AppSettings
import com.metricas.tempotela.data.db.AppDatabase
import com.metricas.tempotela.data.db.DailySummaryEntity
import com.metricas.tempotela.data.db.HourlyBucketEntity
import com.metricas.tempotela.data.db.SessionEntity
import com.metricas.tempotela.data.db.ZoneHistoryEntity
import com.metricas.tempotela.platform.DeviceState
import com.metricas.tempotela.time.TrustedClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Estado "ao vivo" de hoje, usado pelo contador da tela inicial. */
data class LiveState(
    val today: LocalDate,
    val todayUnlockedMs: Long,
    val todayScreenOnMs: Long,
    val todayUnlocks: Int,
    val computedAtUtc: Long,
    val openSessionStartUtc: Long?,
    val noKeyguard: Boolean,
)

/** Recalcula sessões, totais diários, horas e cobertura a partir dos eventos brutos. */
class Recomputer(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: AppSettings,
    private val clock: TrustedClock,
) {
    private val mutex = Mutex()

    suspend fun resolver(): DayResolver = DayResolver(zoneFunction(), settings.dayCutoffHour)

    private suspend fun zoneFunction(): (Long) -> ZoneId {
        if (!settings.followDeviceZone) {
            val fixed = settings.referenceZone()
            return { fixed }
        }
        val history: List<ZoneHistoryEntity> = db.zoneHistoryDao().all()
        if (history.isEmpty()) {
            val current = ZoneId.systemDefault()
            return { current }
        }
        val starts = history.map { it.effectiveFromUtc }
        val zones = history.map { runCatching { ZoneId.of(it.zoneId) }.getOrDefault(ZoneId.systemDefault()) }
        return { ts ->
            var idx = 0
            for (i in starts.indices) if (starts[i] <= ts) idx = i
            zones[idx]
        }
    }

    suspend fun recompute(affectedFromUtc: Long?): LiveState? = mutex.withLock {
        withContext(Dispatchers.IO) { recomputeLocked(affectedFromUtc) }
    }

    private suspend fun recomputeLocked(affectedFromUtc: Long?): LiveState? {
        val now = clock.now()
        // Só conhecemos os eventos até a última leitura. Uma sessão ainda aberta é estendida
        // até esse ponto, nunca até "agora": se o recálculo rodar sem ingestão (ex.: após uma
        // sincronização de hora), o tempo com a tela já apagada não é contado.
        val knownUntil = minOf(now, settings.watermarkUtc ?: now)
        val resolver = resolver()
        val today = resolver.dayOf(now)
        val minEvent = db.rawEventDao().minTsUtc() ?: return null
        val firstDay = resolver.dayOf(minEvent)

        val full = settings.needsFullRecompute || settings.algoVersion != ALGO_VERSION
        var fromDay = if (full) firstDay else today.minusDays(1)
        if (!full && affectedFromUtc != null) {
            val affectedDay = resolver.dayOf(affectedFromUtc)
            if (affectedDay.isBefore(fromDay)) fromDay = affectedDay
        }
        if (fromDay.isBefore(firstDay)) fromDay = firstDay
        if (fromDay.isAfter(today)) fromDay = today

        val fromStart = resolver.dayStartUtc(fromDay)
        val warmupStart = if (fromDay == firstDay) Long.MIN_VALUE else fromStart - WARMUP_MS
        val events = db.rawEventDao().eventsFrom(warmupStart).map {
            UsageEvent(it.tsUtc, it.type, it.id, TimeQuality.parse(it.quality))
        }
        val noKeyguardHint = !DeviceState.isDeviceSecure(context)

        val result = Sessionizer(Sessionizer.Config(noKeyguardHint = noKeyguardHint)).run(events, knownUntil)
        val allSessions = result.sessions + listOfNotNull(result.openSession)
        val screenOn = result.screenOn +
            listOfNotNull(result.openScreenOnStartUtc?.let { Interval(it, maxOf(it, knownUntil)) })
        val sessionProblems = Invariants.sessionProblems(allSessions)

        val aggregates = DayAggregator(resolver).aggregate(allSessions, screenOn, fromDay, today)
        val merged = Coverage.merge(
            db.ingestRunDao().okRuns().map { Interval(it.observedStartUtc, it.observedEndUtc) },
        )

        val summaries = aggregates.map { a ->
            val cov = Coverage.evaluate(a.dayStartUtc, a.dayEndUtc, now, merged)
            val problems = Invariants.check(a) + sessionProblems
            if (problems.isNotEmpty()) {
                a.flags = a.flags or SessionFlags.INCONSISTENT
                Log.w(TAG, "Dia ${a.date} inconsistente: ${problems.joinToString()}")
            }
            DailySummaryEntity(
                date = a.date.toString(),
                zoneId = a.zoneId,
                dayStartUtc = a.dayStartUtc,
                dayLengthMs = a.dayLengthMs,
                unlockedMs = a.unlockedMs,
                screenOnMs = a.screenOnMs,
                unlocks = a.unlocks,
                sessions = a.sessions,
                longestSessionMs = a.longestSessionMs,
                medianSessionMs = a.medianSessionMs,
                firstUnlockUtc = a.firstUnlockUtc,
                lastLockUtc = a.lastLockUtc,
                coveredMs = cov.coveredMs,
                coverageRatio = cov.ratio,
                status = cov.status.name,
                timeQuality = a.quality.name,
                qualityFlags = a.flags,
                hasOpenSession = a.hasOpenSession,
                algoVersion = ALGO_VERSION,
                computedAtUtc = now,
            )
        }
        val hourly = aggregates.flatMap { a ->
            (0 until 24)
                .filter { a.hourlyMs[it] > 0 || a.hourlyUnlocks[it] > 0 }
                .map { HourlyBucketEntity(a.date.toString(), it, a.hourlyMs[it], a.hourlyUnlocks[it]) }
        }
        val sessionsToStore = result.sessions.filter { it.endUtc >= fromStart }.map {
            SessionEntity(
                startUtc = it.startUtc,
                endUtc = it.endUtc,
                durationMs = it.durationMs,
                startReason = it.startReason.name,
                endReason = it.endReason?.name ?: "",
                flags = it.flags,
                quality = it.quality.name,
                algoVersion = ALGO_VERSION,
            )
        }

        db.withTransaction {
            if (full) {
                db.sessionDao().deleteAll()
                db.dailySummaryDao().deleteAll()
                db.hourlyBucketDao().deleteAll()
            } else {
                db.sessionDao().deleteEndingFrom(fromStart, warmupStart)
                db.dailySummaryDao().deleteFrom(fromDay.toString())
                db.hourlyBucketDao().deleteFrom(fromDay.toString())
            }
            if (sessionsToStore.isNotEmpty()) db.sessionDao().insertAll(sessionsToStore)
            if (summaries.isNotEmpty()) db.dailySummaryDao().insertAll(summaries)
            if (hourly.isNotEmpty()) db.hourlyBucketDao().insertAll(hourly)
        }
        if (full) {
            settings.needsFullRecompute = false
            settings.algoVersion = ALGO_VERSION
        }

        val todayAgg = aggregates.lastOrNull { it.date == today }
        return LiveState(
            today = today,
            todayUnlockedMs = todayAgg?.unlockedMs ?: 0L,
            todayScreenOnMs = todayAgg?.screenOnMs ?: 0L,
            todayUnlocks = todayAgg?.unlocks ?: 0,
            computedAtUtc = knownUntil,
            openSessionStartUtc = result.openSession?.startUtc,
            noKeyguard = result.lastSegmentNoKeyguard,
        )
    }

    companion object {
        const val ALGO_VERSION = 2
        private const val TAG = "Recomputer"
        private const val WARMUP_MS = 36L * 60 * 60 * 1000
    }
}
