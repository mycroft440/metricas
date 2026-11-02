package com.metricas.core

import com.metricas.core.day.DayAggregator
import com.metricas.core.day.DayResolver
import com.metricas.core.ingest.DeviceEvent
import com.metricas.core.ingest.EventDedupe
import com.metricas.core.ingest.StoredEventKey
import com.metricas.core.model.EventType.KEYGUARD_HIDDEN
import com.metricas.core.model.EventType.KEYGUARD_SHOWN
import com.metricas.core.model.EventType.SCREEN_INTERACTIVE
import com.metricas.core.model.EventType.SCREEN_NON_INTERACTIVE
import com.metricas.core.model.Interval
import com.metricas.core.model.UsageEvent
import com.metricas.core.session.EndReason
import com.metricas.core.session.SessionFlags
import com.metricas.core.session.Sessionizer
import com.metricas.core.session.StartReason
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecondReviewTest {
    private val t = Instant.parse("2026-11-02T10:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private var seq = 0L

    private fun ev(sec: Long, type: Int) = UsageEvent(t + sec * 1000, type, seq++)

    @Test
    fun lacunaDeDadosEncerraSessaoEmVezDeContarDias() {
        // Desbloqueou às 10:00; os eventos seguintes expiraram (lacuna de 3 dias).
        val events = listOf(
            ev(0, SCREEN_NON_INTERACTIVE), ev(0, KEYGUARD_SHOWN),
            ev(60, SCREEN_INTERACTIVE), ev(60, KEYGUARD_HIDDEN),
            ev(3 * 24 * 3600 + 600, SCREEN_NON_INTERACTIVE),
        )
        val gap = Interval(t + 1_800_000, t + 3 * 24 * hour)
        val r = Sessionizer().run(events, gaps = listOf(gap))
        val s = r.sessions.single()
        assertEquals(t + 60_000, s.startUtc)
        assertEquals(gap.startUtc, s.endUtc)
        assertTrue(SessionFlags.has(s.flags, SessionFlags.ESTIMATED_END))
        assertEquals(EndReason.SHUTDOWN, s.endReason)
    }

    @Test
    fun religarDentroDoAtrasoDeBloqueioNaoEDesbloqueio() {
        val events = listOf(
            ev(0, SCREEN_NON_INTERACTIVE), ev(0, KEYGUARD_SHOWN),
            ev(600, SCREEN_INTERACTIVE), ev(610, KEYGUARD_HIDDEN),
            ev(1200, SCREEN_NON_INTERACTIVE),
            ev(1210, SCREEN_INTERACTIVE), // dentro do atraso de bloqueio
            ev(1500, SCREEN_NON_INTERACTIVE), ev(1530, KEYGUARD_SHOWN),
        )
        val r = Sessionizer().run(events)
        assertEquals(listOf(StartReason.UNLOCK, StartReason.SCREEN_ON_NO_KEYGUARD), r.sessions.map { it.startReason })
        val day = DayAggregator(DayResolver({ ZoneOffset.UTC })).aggregate(
            r.sessions, r.screenOn, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2),
        ).single()
        assertEquals(1, day.unlocks)
        assertEquals(2, day.sessions)
        assertEquals((590 + 290) * 1000L, day.unlockedMs)
    }

    @Test
    fun deslocamentoDoHistoricoNoBootEDetectado() {
        // Guardados antes do reboot; depois do boot o Android corrigiu o relógio em +5 s e
        // deslocou o histórico: os mesmos eventos voltam 5 s depois.
        val stored = listOf(
            StoredEventKey(1_000_000, 1_000_000, SCREEN_INTERACTIVE, 1),
            StoredEventKey(1_000_500, 1_000_500, KEYGUARD_HIDDEN, 1),
            StoredEventKey(1_400_000, 1_400_000, SCREEN_NON_INTERACTIVE, 1),
            StoredEventKey(1_400_001, 1_400_001, KEYGUARD_SHOWN, 1),
        )
        val shifted = stored.map { DeviceEvent(it.tsDevice + 5_000, it.type) }
        val fresh = listOf(DeviceEvent(2_000_000, SCREEN_INTERACTIVE))
        val candidates = shifted + fresh
        val shift = EventDedupe.detectHistoryShift(candidates, stored)
        assertEquals(5_000L, shift)
        val kept = EventDedupe.filterNew(candidates, offsetMs = 0, currentEpoch = 2, existing = stored, historyShiftMs = shift)
        assertEquals(fresh, kept)
    }

    @Test
    fun semDeslocamentoNaoInventaUm() {
        val stored = listOf(
            StoredEventKey(1_000_000, 1_000_000, SCREEN_INTERACTIVE, 1),
            StoredEventKey(1_400_000, 1_400_000, SCREEN_NON_INTERACTIVE, 1),
        )
        val candidates = stored.map { DeviceEvent(it.tsDevice, it.type) } +
            listOf(DeviceEvent(1_700_000, SCREEN_INTERACTIVE), DeviceEvent(1_900_000, SCREEN_NON_INTERACTIVE))
        assertEquals(0L, EventDedupe.detectHistoryShift(candidates, stored))
    }
}
