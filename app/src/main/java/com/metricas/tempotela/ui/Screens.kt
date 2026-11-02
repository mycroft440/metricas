package com.metricas.tempotela.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metricas.core.session.SessionFlags
import com.metricas.core.stats.Stats
import com.metricas.core.stats.StatSummary
import com.metricas.core.stats.DayValue
import com.metricas.core.model.TimeQuality
import com.metricas.tempotela.BuildConfig
import com.metricas.tempotela.data.db.DailySummaryEntity
import com.metricas.tempotela.time.label
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

private val screenPadding = PaddingValues(16.dp)

// ---------------------------------------------------------------- Onboarding

@Composable
fun OnboardingScreen(onGrant: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("Tempo de Tela", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Este app mede quanto tempo por dia o telefone fica desbloqueado e em uso, guarda o " +
                    "histórico por data e calcula médias confiáveis.",
                style = MaterialTheme.typography.bodyLarge,
            )
            SectionCard("O que é coletado") {
                Text("• Momentos em que a tela liga/desliga e em que o aparelho é bloqueado/desbloqueado.")
                Text("• Eventos de desligar/ligar o aparelho.")
                Text("• Nada sobre o conteúdo que você vê, mensagens ou apps específicos.")
            }
            SectionCard("Privacidade") {
                Text("• Os dados ficam somente neste aparelho.")
                Text("• A internet é usada apenas para obter a data e a hora oficiais (NTP.br, Google).")
                Text("• Nenhum dado de uso é enviado para fora do aparelho.")
            }
            SectionCard("Permissão necessária") {
                Text(
                    "O Android exige que você ative o \"Acesso ao uso\" para este app nas Configurações. " +
                        "Toque no botão abaixo, encontre \"Tempo de Tela\" e ative a opção.",
                )
            }
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                Text("Conceder acesso ao uso")
            }
        }
    }
}

// ---------------------------------------------------------------- Hoje

@Composable
fun TodayScreen(state: UiState, vm: MainViewModel) {
    val zone = state.zone
    val clock = state.clock
    val now = clock?.trueNowUtc ?: System.currentTimeMillis()
    val live = state.live
    val liveMs = vm.liveUnlockedMs(state)
    val todayRow = state.days.lastOrNull { live != null && it.date == live.today.toString() }

    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val alerts = state.quality?.alerts.orEmpty()
        if (alerts.isNotEmpty()) {
            item { AlertsCard(alerts) }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        live?.today?.let { Fmt.dateLong(it) } ?: "Hoje",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Tempo de tela desbloqueada", style = MaterialTheme.typography.titleMedium)
                    Text(Fmt.clock(liveMs), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                    val open = live?.openSessionStartUtc
                    if (open != null) {
                        Text(
                            "Sessão atual: ${Fmt.clock(now - open)} (desde ${Fmt.time(open, zone)})",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (live == null) {
                        Text(
                            "Coletando os primeiros dados… Toque em Atualizar na aba Qualidade se demorar.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Desbloqueios", (live?.todayUnlocks ?: 0).toString(), Modifier.weight(1f))
                StatTile("Tela ligada", Fmt.duration(live?.todayScreenOnMs ?: 0L), Modifier.weight(1f))
                StatTile("1º desbloqueio", Fmt.time(todayRow?.firstUnlockUtc, zone), Modifier.weight(1f))
            }
        }
        item { TypicalComparison(state, liveMs, now) }
        item { OfficialClockCard(state) }
        item {
            SectionCard("Como medimos") {
                Text(
                    "Conta o tempo em que a tela está ligada E o aparelho está desbloqueado. Tela acesa por " +
                        "notificação, tela sempre ativa (AOD) e uso pela tela de bloqueio não contam. " +
                        "Os eventos vêm do próprio Android e todas as datas e horas são convertidas para a " +
                        "hora oficial obtida pela internet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TypicalComparison(state: UiState, liveMs: Long, now: Long) {
    val typical = state.typicalCumulative
    if (typical == null) {
        SectionCard("Comparação com um dia típico") {
            Text(
                "Disponível após o primeiro dia completo de dados.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }
    val zdt = Instant.ofEpochMilli(now).atZone(state.zone)
    val hourFraction = zdt.hour + zdt.minute / 60.0 + zdt.second / 3600.0
    val typicalNow = Stats.typicalAt(typical, hourFraction)
    val typicalDay = typical[23]
    val diff = liveMs - typicalNow
    SectionCard("Comparação com um dia típico") {
        KeyValue("Até agora (${Fmt.time(now, state.zone)}) você costuma ter", Fmt.duration(typicalNow))
        KeyValue("Hoje até agora", Fmt.duration(liveMs), emphasize = true)
        KeyValue(
            "Diferença",
            (if (diff >= 0) "+" else "−") + Fmt.duration(abs(diff)),
        )
        KeyValue("Total de um dia típico", Fmt.duration(typicalDay))
        Text(
            "Base: ${state.typicalDays} dias completos dos últimos 30.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OfficialClockCard(state: UiState) {
    val c = state.clock ?: return
    SectionCard("Hora oficial (internet)") {
        Text(Fmt.timeSec(c.trueNowUtc, state.zone), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        KeyValue(
            "Fonte",
            c.source?.label ?: if (c.quality == TimeQuality.INFERIDA) "estimada (último desvio conhecido)" else "relógio do aparelho (sem verificação)",
        )
        c.errorMs?.let { KeyValue("Erro estimado", "±$it ms") }
        KeyValue("Qualidade", c.quality.label())
        KeyValue("Fuso usado", state.zone.id)
        if (abs(c.deviceOffsetMs) > 60_000) {
            Text(
                "O relógio do aparelho está ${Fmt.duration(abs(c.deviceOffsetMs))} " +
                    (if (c.deviceOffsetMs > 0) "adiantado" else "atrasado") +
                    " — o app usa a hora oficial da internet.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            KeyValue("Desvio do relógio do aparelho", Fmt.signedMs(c.deviceOffsetMs))
        }
        if (c.quality == TimeQuality.NAO_VERIFICADA) {
            Text(
                "Ainda sem hora da internet: conecte-se para verificar. Os horários serão corrigidos automaticamente.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---------------------------------------------------------------- Histórico

@Composable
fun HistoryScreen(state: UiState) {
    val days = state.days
    val last30 = days.takeLast(30)
    val maxMs = (days.maxOfOrNull { it.unlockedMs } ?: 0L).coerceAtLeast(1L)
    val movingAverage = remember(days) {
        val complete = days.filter { it.status == "COMPLETE" }.map { DayValue(LocalDate.parse(it.date), it.unlockedMs) }
        val byDate = Stats.movingAverage(complete).toMap()
        last30.map { byDate[LocalDate.parse(it.date)] }
    }

    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard("Últimos ${last30.size} dias") {
                if (last30.isEmpty()) {
                    Text("Sem dados ainda.")
                } else {
                    DailyBarChart(
                        values = last30.map { it.unlockedMs },
                        complete = last30.map { it.status == "COMPLETE" },
                        movingAverage = movingAverage,
                    )
                    Legend(
                        listOf(
                            MaterialTheme.colorScheme.primary to "Dia completo",
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) to "Parcial",
                            MaterialTheme.colorScheme.tertiary to "Média móvel 7 dias",
                        ),
                    )
                }
            }
        }
        items(days.reversed(), key = { it.date }) { day ->
            DayRow(day, maxMs, state.zone)
        }
    }
}

@Composable
private fun DayRow(day: DailySummaryEntity, maxMs: Long, zone: ZoneId) {
    var expanded by rememberSaveable(day.date) { mutableStateOf(false) }
    val dayZone = runCatching { ZoneId.of(day.zoneId) }.getOrDefault(zone)
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.date(LocalDate.parse(day.date)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusChip(day.status)
                Spacer(Modifier.width(8.dp))
                Text(Fmt.duration(day.unlockedMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            ProportionBar(day.unlockedMs.toFloat() / maxMs)
            Text(
                "${day.unlocks} desbloqueios · 1º às ${Fmt.time(day.firstUnlockUtc, dayZone)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                KeyValue("Tela ligada (total)", Fmt.duration(day.screenOnMs))
                KeyValue("Sessões", day.sessions.toString())
                KeyValue("Maior sessão", Fmt.duration(day.longestSessionMs))
                KeyValue("Sessão mediana", Fmt.duration(day.medianSessionMs))
                KeyValue("Último bloqueio", Fmt.time(day.lastLockUtc, dayZone))
                KeyValue("Cobertura dos dados", "${"%.1f".format(Fmt.locale, day.coverageRatio * 100)}%")
                KeyValue("Duração do dia", Fmt.duration(day.dayLengthMs))
                KeyValue("Qualidade da hora", TimeQuality.parse(day.timeQuality).label())
                if (SessionFlags.has(day.qualityFlags, SessionFlags.LONG)) {
                    Text("Contém sessão longa (> 3 h).", style = MaterialTheme.typography.bodySmall)
                }
                if (SessionFlags.has(day.qualityFlags, SessionFlags.ESTIMATED_END)) {
                    Text("Contém sessão encerrada por desligamento (fim estimado).", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Médias

@Composable
fun AveragesScreen(state: UiState) {
    var period by rememberSaveable { mutableIntStateOf(30) }
    val data = state.averages[period]
    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((value, label) in listOf(7 to "7 dias", 30 to "30 dias", 90 to "90 dias", 0 to "Tudo")) {
                    if (value == period) {
                        Button(onClick = { period = value }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(onClick = { period = value }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                            Text(label)
                        }
                    }
                }
            }
        }
        val summary = data?.summary
        if (data == null || summary == null) {
            item {
                SectionCard("Sem dias suficientes") {
                    Text(
                        "As médias usam apenas dias completos (sem lacunas). O dia de hoje e o primeiro dia de " +
                            "coleta não entram. Volte amanhã.",
                    )
                    if (data != null && data.excludedDays > 0) {
                        Text("${data.excludedDays} dia(s) parcial(is) excluído(s) neste período.")
                    }
                }
            }
            return@LazyColumn
        }
        item {
            SectionCard("Média diária") {
                Text(Fmt.duration(summary.meanMs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    "Base: ${summary.n} dia(s) completo(s)" +
                        if (data.excludedDays > 0) " · ${data.excludedDays} excluído(s)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val low = summary.ci95LowMs
                val high = summary.ci95HighMs
                if (low != null && high != null) {
                    KeyValue("Intervalo de confiança 95%", "${Fmt.duration(low)} – ${Fmt.duration(high)}")
                }
                KeyValue("Mediana", Fmt.duration(summary.medianMs))
                KeyValue("Desvio-padrão", Fmt.duration(summary.sdMs))
                KeyValue("Faixa típica (P25–P75)", "${Fmt.duration(summary.p25Ms)} – ${Fmt.duration(summary.p75Ms)}")
                KeyValue("Menor dia", "${Fmt.duration(summary.min.valueMs)} (${Fmt.date(summary.min.date)})")
                KeyValue("Maior dia", "${Fmt.duration(summary.max.valueMs)} (${Fmt.date(summary.max.date)})")
                KeyValue("Total no período", Fmt.duration(summary.totalMs))
            }
        }
        item {
            SectionCard("Tendência") {
                KeyValue("Semana atual × anterior", Fmt.percent(data.weekChange))
                val trend = data.trendPerWeekMs
                KeyValue(
                    "Tendência (por semana)",
                    if (trend == null) "—" else (if (trend >= 0) "+" else "−") + Fmt.duration(abs(trend)) + "/dia",
                )
            }
        }
        item {
            SectionCard("Dias úteis × fim de semana") {
                SummaryLine("Dias úteis", data.weekday)
                SummaryLine("Fim de semana", data.weekend)
            }
        }
        item {
            SectionCard("Por dia da semana") {
                val maxMean = data.byDayOfWeek.values.maxOfOrNull { it?.meanMs ?: 0.0 }?.coerceAtLeast(1.0) ?: 1.0
                for (dow in DayOfWeek.values()) {
                    val s = data.byDayOfWeek[dow]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Fmt.dayOfWeek(dow), modifier = Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
                        ProportionBar(((s?.meanMs ?: 0.0) / maxMean).toFloat(), modifier = Modifier.weight(1f))
                        Text(
                            if (s == null) "—" else "${Fmt.duration(s.meanMs)} (n=${s.n})",
                            modifier = Modifier.width(110.dp).padding(start = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            SectionCard("Hábitos") {
                data.unlocks?.let { KeyValue("Desbloqueios por dia (média)", "%.1f".format(Fmt.locale, it.meanMs)) }
                data.screenOn?.let { KeyValue("Tela ligada por dia (média)", Fmt.duration(it.meanMs)) }
                KeyValue("Duração média da sessão", Fmt.duration(data.sessionMeanMs))
                KeyValue("Duração mediana da sessão", Fmt.duration(data.sessionMedianMs))
                KeyValue("Sessões consideradas", data.sessionCount.toString())
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, s: StatSummary?) {
    KeyValue(label, if (s == null) "—" else "${Fmt.duration(s.meanMs)} (mediana ${Fmt.duration(s.medianMs)}, n=${s.n})")
}

// ---------------------------------------------------------------- Qualidade

@Composable
fun QualityScreen(state: UiState, vm: MainViewModel) {
    val q = state.quality
    val zone = state.zone
    val c = state.clock
    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refreshNow() }, enabled = !state.busy) { Text("Atualizar agora") }
                OutlinedButton(onClick = { vm.syncTimeNow() }, enabled = !state.busy) { Text("Sincronizar hora") }
            }
        }
        item {
            SectionCard("Hora pela internet") {
                if (c != null) {
                    KeyValue("Hora oficial", Fmt.timeSec(c.trueNowUtc, zone), emphasize = true)
                    KeyValue("Qualidade", c.quality.label())
                    KeyValue("Desvio do relógio do aparelho", Fmt.signedMs(c.deviceOffsetMs))
                    c.anchorAgeMs?.let { KeyValue("Última sincronização", Fmt.ago(it)) }
                }
                val s = q?.lastSync
                if (s != null) {
                    KeyValue("Fonte", sourceLabel(s.source))
                    KeyValue("Servidor", s.server)
                    KeyValue("Erro estimado", "±${s.errorMs} ms")
                    s.rttMs?.let { KeyValue("Ida e volta", "$it ms") }
                    s.disagreementMs?.let { KeyValue("Divergência entre fontes", "$it ms") }
                } else {
                    Text("Nenhuma sincronização bem-sucedida ainda.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (q != null) {
            item {
                SectionCard("Coleta de eventos") {
                    val last = q.lastIngest
                    KeyValue("Última leitura", if (last == null) "—" else Fmt.dateTime(last.startedAtUtc, zone))
                    if (last != null) {
                        KeyValue("Resultado", if (last.ok) "ok · ${last.eventsNew} novos de ${last.eventsRead}" else (last.error ?: "falhou"))
                    }
                    KeyValue("Leituras bem-sucedidas", q.okIngests.toString())
                    KeyValue("Eventos guardados", q.eventCount.toString())
                    KeyValue("Eventos sem hora verificada", q.unverifiedEvents.toString())
                    KeyValue("Primeiro evento", Fmt.dateTime(q.firstEventUtc, zone))
                    state.live?.let {
                        KeyValue("Aparelho sem tela de bloqueio", if (it.noKeyguard) "sim" else "não")
                    }
                }
            }
            item {
                SectionCard("Diagnóstico") {
                    for (alert in q.alerts) {
                        Text("• $alert", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (q.alerts.isEmpty()) {
                        Text("Nenhum problema detectado.", style = MaterialTheme.typography.bodySmall)
                    }
                    KeyValue("Maior intervalo sem coleta (30 dias)", Fmt.duration(q.largestIngestGapMs30d))
                    KeyValue("Dias com inconsistência", q.inconsistentDays.toString())
                    Text("Eventos registrados nos últimos 7 dias:", style = MaterialTheme.typography.bodySmall)
                    for (type in com.metricas.core.model.EventType.TRACKED) {
                        KeyValue(com.metricas.core.model.EventType.label(type), (q.eventTypeCounts7d[type] ?: 0).toString())
                    }
                }
            }
            item {
                SectionCard("Dias") {
                    for (status in listOf("COMPLETE", "PARTIAL", "GAP", "NO_DATA")) {
                        KeyValue(Fmt.status(status), (q.statusCounts[status] ?: 0).toString())
                    }
                    KeyValue("Dias com sessão longa (> 3 h)", q.longSessionDays.toString())
                    KeyValue("Dias com desligamento (fim estimado)", q.estimatedEndDays.toString())
                }
            }
            item {
                SectionCard("Relógio e fuso") {
                    KeyValue("Épocas de relógio (boots/ajustes)", q.epochCount.toString())
                    if (q.clockEvents.isEmpty()) {
                        Text("Nenhuma alteração de relógio ou fuso registrada.", style = MaterialTheme.typography.bodySmall)
                    }
                    for (e in q.clockEvents) {
                        val detail = when {
                            e.deltaMs != null -> Fmt.signedMs(e.deltaMs)
                            e.newZone != null -> "${e.oldZone} → ${e.newZone}"
                            else -> ""
                        }
                        KeyValue("${Fmt.dateTime(e.detectedAtUtc, zone)} · ${e.kind.replace('_', ' ').lowercase()}", detail)
                    }
                }
            }
            item {
                SectionCard("Sincronizações recentes") {
                    if (q.recentSyncs.isEmpty()) Text("—")
                    for (s in q.recentSyncs) {
                        val desc = if (s.ok) "${sourceLabel(s.source)} · ±${s.errorMs} ms" else "falha: ${s.error ?: "?"}"
                        KeyValue((if (s.chosen) "✓ " else "") + desc, if (s.ok) Fmt.signedMs(s.deviceOffsetMs) else "")
                    }
                }
            }
        }
    }
}

private fun sourceLabel(name: String): String =
    runCatching { com.metricas.core.time.TimeSource.valueOf(name).label }.getOrDefault(name)

// ---------------------------------------------------------------- Ajustes

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    val settings = state.settings ?: return
    var zoneText by remember(settings.referenceZoneId) { mutableStateOf(settings.referenceZoneId) }
    val exportDays = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportDays(uri)
    }
    val exportSessions = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportSessions(uri)
    }

    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Início do dia") {
                Text(
                    "Hora em que um dia termina e o próximo começa. Padrão 00:00. Use 03:00 ou 04:00 se " +
                        "costuma usar o telefone depois da meia-noite.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.setCutoffHour(settings.dayCutoffHour - 1) }, enabled = settings.dayCutoffHour > 0) { Text("−") }
                    Text(
                        "%02d:00".format(settings.dayCutoffHour),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    OutlinedButton(onClick = { vm.setCutoffHour(settings.dayCutoffHour + 1) }, enabled = settings.dayCutoffHour < 6) { Text("+") }
                }
            }
        }
        item {
            SectionCard("Fuso horário de referência") {
                Text(
                    "Define em que data cada uso é contado. Fixo por padrão: mudar o fuso do aparelho não " +
                        "altera os dias.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = zoneText,
                    onValueChange = { zoneText = it },
                    label = { Text("Fuso (ex.: America/Sao_Paulo)") },
                    singleLine = true,
                    enabled = !settings.followDeviceZone,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.setReferenceZone(zoneText) }, enabled = !settings.followDeviceZone) { Text("Aplicar") }
                    TextButton(
                        onClick = {
                            zoneText = ZoneId.systemDefault().id
                            vm.setReferenceZone(zoneText)
                        },
                        enabled = !settings.followDeviceZone,
                    ) { Text("Usar fuso do aparelho") }
                }
                SwitchRow(
                    "Seguir o fuso do aparelho (viagens)",
                    settings.followDeviceZone,
                ) { vm.setFollowDeviceZone(it) }
            }
        }
        item {
            SectionCard("Médias") {
                SwitchRow("Incluir dias parciais nas médias", settings.includePartialDays) { vm.setIncludePartial(it) }
            }
        }
        item {
            val q = state.quality
            SectionCard("Proteções da coleta") {
                Text(
                    "O Android guarda os eventos por ~10 dias. Para não perder dados se você ficar dias sem " +
                        "abrir o app, deixe a coleta em segundo plano livre de restrições.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (q != null) {
                    KeyValue("Pausar app se não usado", if (q.unusedAppRestrictions) "ativado (recomendado desativar)" else "desativado")
                    KeyValue("Otimização de bateria", if (q.batteryOptimized) "ativada" else "sem restrição")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.openUnusedAppSettings() }) { Text("Pausa por inatividade") }
                    OutlinedButton(onClick = { vm.openBatterySettings() }) { Text("Bateria") }
                }
            }
        }
        item {
            SectionCard("Exportar dados") {
                Text(
                    "O histórico com mais de 10 dias existe só neste app. Exporte para guardar uma cópia.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportDays.launch("tempo-de-tela-dias.csv") }) { Text("Dias (CSV)") }
                    OutlinedButton(onClick = { exportSessions.launch("tempo-de-tela-sessoes.csv") }) { Text("Sessões (CSV)") }
                }
            }
        }
        item {
            SectionCard("Permissão") {
                KeyValue("Acesso ao uso", if (state.hasUsageAccess) "concedido" else "negado")
                TextButton(onClick = { vm.openUsageAccess() }) { Text("Abrir configurações de acesso ao uso") }
            }
        }
        item {
            SectionCard("Sobre") {
                KeyValue("Versão", BuildConfig.VERSION_NAME)
                Text(
                    "Fontes de hora: Google TrustedTime, hora de rede do Android, NTP.br (Hora Legal " +
                        "Brasileira) e cabeçalho HTTPS Date.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AlertsCard(alerts: List<String>) {
    Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Atenção", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            for (alert in alerts) Text("• $alert", style = MaterialTheme.typography.bodySmall)
        }
    }
}
