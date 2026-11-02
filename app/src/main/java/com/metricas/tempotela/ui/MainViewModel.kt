package com.metricas.tempotela.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metricas.core.day.DayResolver
import com.metricas.core.session.SessionFlags
import com.metricas.core.stats.DayValue
import com.metricas.core.stats.StatSummary
import com.metricas.core.stats.Stats
import com.metricas.tempotela.data.AppSettings
import com.metricas.tempotela.data.db.ClockEventEntity
import com.metricas.tempotela.data.db.DailySummaryEntity
import com.metricas.tempotela.data.db.IngestRunEntity
import com.metricas.tempotela.data.db.TimeSyncEntity
import com.metricas.tempotela.graph
import com.metricas.tempotela.pipeline.LiveState
import com.metricas.tempotela.platform.UsageAccess
import com.metricas.tempotela.time.SyncOutcome
import com.metricas.tempotela.time.TrustedClock
import com.metricas.tempotela.time.label
import com.metricas.tempotela.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

data class AveragesData(
    val periodDays: Int,
    val summary: StatSummary?,
    val weekday: StatSummary?,
    val weekend: StatSummary?,
    val byDayOfWeek: Map<DayOfWeek, StatSummary?>,
    val trendPerWeekMs: Double?,
    val weekChange: Double?,
    val unlocks: StatSummary?,
    val screenOn: StatSummary?,
    val sessionMeanMs: Double?,
    val sessionMedianMs: Double?,
    val sessionCount: Int,
    val excludedDays: Int,
)

data class QualityInfo(
    val lastSync: TimeSyncEntity?,
    val recentSyncs: List<TimeSyncEntity>,
    val lastIngest: IngestRunEntity?,
    val okIngests: Int,
    val eventCount: Int,
    val unverifiedEvents: Int,
    val epochCount: Int,
    val clockEvents: List<ClockEventEntity>,
    val longSessionDays: Int,
    val estimatedEndDays: Int,
    val firstEventUtc: Long?,
    val statusCounts: Map<String, Int>,
)

data class UiState(
    val hasUsageAccess: Boolean = false,
    val checkedAccess: Boolean = false,
    val busy: Boolean = false,
    val clock: TrustedClock.Reading? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val live: LiveState? = null,
    val days: List<DailySummaryEntity> = emptyList(),
    val todayHourly: LongArray = LongArray(24),
    val typicalCumulative: DoubleArray? = null,
    val typicalDays: Int = 0,
    val averages: Map<Int, AveragesData> = emptyMap(),
    val quality: QualityInfo? = null,
    val settings: AppSettings.Snapshot? = null,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val refreshMutex = Mutex()

    init {
        _state.update {
            it.copy(
                hasUsageAccess = UsageAccess.has(app),
                checkedAccess = true,
                clock = graph.clock.read(),
                settings = graph.settings.snapshot(),
                zone = currentZone(),
            )
        }
        viewModelScope.launch { load() }
    }

    private fun currentZone(): ZoneId =
        if (graph.settings.followDeviceZone) ZoneId.systemDefault() else graph.settings.referenceZone()

    /** Executa enquanto a tela está visível: relógio a cada 1 s, atualização a cada 60 s. */
    suspend fun runWhileVisible() {
        refresh(forceSync = false)
        var ticks = 0
        while (true) {
            delay(1_000)
            _state.update { it.copy(clock = graph.clock.read()) }
            ticks++
            val live = _state.value.live
            val zone = currentZone()
            val dayChanged = live != null &&
                DayResolver({ zone }, graph.settings.dayCutoffHour).dayOf(graph.clock.now()) != live.today
            if (ticks >= 60 || dayChanged) {
                ticks = 0
                refresh(forceSync = false)
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refresh(forceSync = false, announce = true) }
    }

    private suspend fun refresh(forceSync: Boolean, announce: Boolean = false) {
        if (!refreshMutex.tryLock()) return
        try {
            val has = UsageAccess.has(getApplication<Application>())
            _state.update { it.copy(hasUsageAccess = has, busy = true) }
            val result = graph.pipeline.refresh("app", forceSync)
            _state.update { s ->
                s.copy(
                    live = result.live ?: s.live,
                    message = if (announce) result.ingest.message else s.message,
                )
            }
            load()
        } catch (e: Exception) {
            _state.update { it.copy(message = "Erro ao atualizar: ${e.message}") }
        } finally {
            _state.update { it.copy(busy = false) }
            refreshMutex.unlock()
        }
    }

    fun syncTimeNow() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val outcome = runCatching { graph.pipeline.syncTime("manual") }.getOrNull()
            val msg = when (outcome) {
                is SyncOutcome.Success ->
                    "Hora sincronizada: ${outcome.sample.source.label} (erro ±${outcome.sample.errorMs} ms)"
                is SyncOutcome.Failure -> "Falha ao sincronizar: ${outcome.reason}"
                null -> "Falha ao sincronizar"
            }
            _state.update { it.copy(busy = false, message = msg, clock = graph.clock.read()) }
            load()
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun openUsageAccess() = UsageAccess.openSettings(getApplication<Application>())

    fun onResumeCheck() {
        val has = UsageAccess.has(getApplication<Application>())
        if (has != _state.value.hasUsageAccess) {
            _state.update { it.copy(hasUsageAccess = has) }
            if (has) viewModelScope.launch { refresh(forceSync = true) }
        }
    }

    // ---- Ajustes ----

    fun setCutoffHour(hour: Int) = changeAndRecompute { graph.settings.dayCutoffHour = hour.coerceIn(0, 6) }

    fun setFollowDeviceZone(follow: Boolean) = changeAndRecompute { graph.settings.followDeviceZone = follow }

    fun setReferenceZone(id: String): Boolean {
        val trimmed = id.trim()
        if (!AppSettings.isValidZone(trimmed)) {
            _state.update { it.copy(message = "Fuso inválido: $trimmed") }
            return false
        }
        changeAndRecompute { graph.settings.referenceZoneId = trimmed }
        return true
    }

    fun setIncludePartial(include: Boolean) {
        graph.settings.includePartialDays = include
        viewModelScope.launch { load() }
    }

    private fun changeAndRecompute(change: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            graph.pipeline.fullRecompute {
                change()
                graph.settings.needsFullRecompute = true
            }
            Scheduler.scheduleDailyClose(getApplication<Application>(), replace = true)
            _state.update { it.copy(busy = false, zone = currentZone()) }
            refresh(forceSync = false)
        }
    }

    // ---- Exportação ----

    fun exportDays(uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val days = graph.db.dailySummaryDao().all()
                getApplication<Application>().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                    w.write(
                        "data,fuso,status,desbloqueado_ms,desbloqueado_min,tela_ligada_ms,desbloqueios,sessoes," +
                            "maior_sessao_ms,mediana_sessao_ms,primeiro_desbloqueio_utc,ultimo_bloqueio_utc," +
                            "cobertura,qualidade_hora,marcas\n",
                    )
                    for (d in days) {
                        w.write(
                            listOf(
                                d.date, d.zoneId, d.status, d.unlockedMs, "%.1f".format(java.util.Locale.US, d.unlockedMs / 60_000.0),
                                d.screenOnMs, d.unlocks, d.sessions, d.longestSessionMs, d.medianSessionMs,
                                d.firstUnlockUtc?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "",
                                d.lastLockUtc?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "",
                                "%.4f".format(java.util.Locale.US, d.coverageRatio), d.timeQuality, d.qualityFlags,
                            ).joinToString(",") + "\n",
                        )
                    }
                } ?: error("não foi possível abrir o arquivo")
            }.isSuccess
        }
        _state.update { it.copy(message = if (ok) "Dias exportados" else "Falha ao exportar") }
    }

    fun exportSessions(uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val sessions = graph.db.sessionDao().all()
                getApplication<Application>().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                    w.write("inicio_utc,fim_utc,duracao_ms,motivo_inicio,motivo_fim,marcas,qualidade_hora\n")
                    for (s in sessions) {
                        w.write(
                            listOf(
                                java.time.Instant.ofEpochMilli(s.startUtc).toString(),
                                java.time.Instant.ofEpochMilli(s.endUtc).toString(),
                                s.durationMs, s.startReason, s.endReason, s.flags, s.quality,
                            ).joinToString(",") + "\n",
                        )
                    }
                } ?: error("não foi possível abrir o arquivo")
            }.isSuccess
        }
        _state.update { it.copy(message = if (ok) "Sessões exportadas" else "Falha ao exportar") }
    }

    // ---- Carga e estatísticas ----

    private suspend fun load(): Unit = withContext(Dispatchers.IO) {
        val db = graph.db
        val resolver = graph.recomputer.resolver()
        val reading = graph.clock.read()
        val today = resolver.dayOf(reading.trueNowUtc)
        val days = db.dailySummaryDao().all()
        val includePartial = graph.settings.includePartialDays

        val hourly = db.hourlyBucketDao().from(today.minusDays(60).toString())
        val hourlyByDay = hourly.groupBy { it.date }.mapValues { (_, list) ->
            LongArray(24).also { arr -> list.forEach { arr[it.hour] = it.unlockedMs } }
        }
        val completeRecent = days.filter {
            it.status == "COMPLETE" && LocalDate.parse(it.date).let { d -> d.isBefore(today) && !d.isBefore(today.minusDays(30)) }
        }
        val typicalArrays = completeRecent.map { hourlyByDay[it.date] ?: LongArray(24) }
        val typical = if (typicalArrays.isNotEmpty()) Stats.typicalCumulative(typicalArrays) else null

        val allSessions = db.sessionDao().between(Long.MIN_VALUE, resolver.dayStartUtc(today))
        val averages = listOf(7, 30, 90, 0).associateWith { period ->
            computeAverages(period, days, today, includePartial, allSessions.map { it.startUtc to it.durationMs }, resolver)
        }

        val syncs = db.timeSyncDao().recent(8)
        val quality = QualityInfo(
            lastSync = db.timeSyncDao().latestChosen(),
            recentSyncs = syncs,
            lastIngest = db.ingestRunDao().last(),
            okIngests = db.ingestRunDao().okCount(),
            eventCount = db.rawEventDao().count(),
            unverifiedEvents = db.rawEventDao().unverifiedCount(),
            epochCount = db.clockEpochDao().count(),
            clockEvents = db.clockEventDao().recent(10),
            longSessionDays = days.count { SessionFlags.has(it.qualityFlags, SessionFlags.LONG) },
            estimatedEndDays = days.count { SessionFlags.has(it.qualityFlags, SessionFlags.ESTIMATED_END) },
            firstEventUtc = db.rawEventDao().minTsUtc(),
            statusCounts = days.groupingBy { it.status }.eachCount(),
        )

        _state.update {
            it.copy(
                clock = reading,
                zone = currentZone(),
                days = days,
                todayHourly = hourlyByDay[today.toString()] ?: LongArray(24),
                typicalCumulative = typical,
                typicalDays = typicalArrays.size,
                averages = averages,
                quality = quality,
                settings = graph.settings.snapshot(),
            )
        }
    }

    private fun computeAverages(
        period: Int,
        days: List<DailySummaryEntity>,
        today: LocalDate,
        includePartial: Boolean,
        sessions: List<Pair<Long, Long>>,
        resolver: DayResolver,
    ): AveragesData {
        val from = if (period == 0) LocalDate.MIN else today.minusDays(period.toLong())
        val inPeriod = days.filter {
            val d = LocalDate.parse(it.date)
            d.isBefore(today) && !d.isBefore(from)
        }
        val eligible = inPeriod.filter { it.status == "COMPLETE" || (includePartial && it.status == "PARTIAL") }
        val values = eligible.map { DayValue(LocalDate.parse(it.date), it.unlockedMs) }
        val (weekday, weekend) = Stats.weekdayWeekend(values)
        val eligibleDates = eligible.map { it.date }.toHashSet()
        val sessionDurations = sessions
            .filter { (start, _) -> resolver.dayOf(start).toString() in eligibleDates }
            .map { it.second.toDouble() }
            .sorted()
        return AveragesData(
            periodDays = period,
            summary = Stats.summarize(values),
            weekday = weekday,
            weekend = weekend,
            byDayOfWeek = Stats.byDayOfWeek(values),
            trendPerWeekMs = Stats.trendPerWeekMs(values),
            weekChange = Stats.periodChange(values, today.minusDays(1)),
            unlocks = Stats.summarize(eligible.map { DayValue(LocalDate.parse(it.date), it.unlocks.toLong()) }),
            screenOn = Stats.summarize(eligible.map { DayValue(LocalDate.parse(it.date), it.screenOnMs) }),
            sessionMeanMs = if (sessionDurations.isEmpty()) null else sessionDurations.average(),
            sessionMedianMs = if (sessionDurations.isEmpty()) null else Stats.percentile(sessionDurations, 0.5),
            sessionCount = sessionDurations.size,
            excludedDays = inPeriod.size - eligible.size,
        )
    }

    /** Tempo desbloqueado de hoje, incluindo a sessão em andamento até agora. */
    fun liveUnlockedMs(s: UiState): Long {
        val live = s.live ?: return 0
        val now = s.clock?.trueNowUtc ?: return live.todayUnlockedMs
        val extra = if (live.openSessionStartUtc != null) (now - live.computedAtUtc).coerceAtLeast(0) else 0
        return live.todayUnlockedMs + extra
    }

    fun qualityLabel(s: UiState): String = s.clock?.quality?.label() ?: "-"
}
