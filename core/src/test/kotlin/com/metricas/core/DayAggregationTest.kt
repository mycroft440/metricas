package com.metricas.core

import com.metricas.core.day.DayAggregator
import com.metricas.core.day.DayResolver
import com.metricas.core.day.DaySlice
import com.metricas.core.model.TimeQuality
import com.metricas.core.session.EndReason
import com.metricas.core.session.Session
import com.metricas.core.session.StartReason
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DayAggregationTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private val ny = ZoneId.of("America/New_York")
    private val hour = 3_600_000L

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun session(start: String, end: String) =
        Session(ms(start), ms(end), StartReason.UNLOCK, EndReason.SCREEN_OFF, 0, TimeQuality.VERIFICADA)

    @Test
    fun sessaoCruzandoMeiaNoiteEDividida() {
        val r = DayResolver({ sp })
        val slices = r.split(ms("2026-11-02T23:50:00-03:00"), ms("2026-11-03T00:20:00-03:00"))
        assertEquals(2, slices.size)
        assertEquals(LocalDate.of(2026, 11, 2), slices[0].date)
        assertEquals(10 * 60_000L, slices[0].endUtc - slices[0].startUtc)
        assertEquals(LocalDate.of(2026, 11, 3), slices[1].date)
        assertEquals(20 * 60_000L, slices[1].endUtc - slices[1].startUtc)
    }

    @Test
    fun horarioDeVeraoGeraDiasDe23e25Horas() {
        val r = DayResolver({ ny })
        assertEquals(23 * hour, r.bounds(LocalDate.of(2024, 3, 10)).durationMs)
        assertEquals(25 * hour, r.bounds(LocalDate.of(2024, 11, 3)).durationMs)
        assertEquals(24 * hour, r.bounds(LocalDate.of(2024, 6, 1)).durationMs)
    }

    @Test
    fun horarioDeVeraoBrasileiroComecandoAMeiaNoite() {
        val r = DayResolver({ sp })
        // 04/11/2018: 00:00 não existiu (pulou para 01:00).
        assertEquals(ms("2018-11-04T01:00:00-02:00"), r.bounds(LocalDate.of(2018, 11, 4)).startUtc)
        assertEquals(23 * hour, r.bounds(LocalDate.of(2018, 11, 4)).durationMs)
        assertEquals(25 * hour, r.bounds(LocalDate.of(2019, 2, 16)).durationMs)
    }

    @Test
    fun corteDoDiaConfiguravel() {
        val r = DayResolver({ sp }, cutoffHour = 4)
        assertEquals(LocalDate.of(2026, 11, 2), r.dayOf(ms("2026-11-03T02:00:00-03:00")))
        assertEquals(LocalDate.of(2026, 11, 3), r.dayOf(ms("2026-11-03T04:00:00-03:00")))
    }

    @Test
    fun divisaoPorHora() {
        val r = DayResolver({ sp })
        val hours = r.splitHours(
            DaySlice(LocalDate.of(2026, 11, 2), ms("2026-11-02T13:50:00-03:00"), ms("2026-11-02T15:10:00-03:00")),
        )
        assertEquals(listOf(13, 14, 15), hours.map { it.hour })
        assertEquals(listOf(10 * 60_000L, 60 * 60_000L, 10 * 60_000L), hours.map { it.ms })
    }

    @Test
    fun agregacaoDiaria() {
        val r = DayResolver({ sp })
        val sessions = listOf(
            session("2026-11-02T08:00:00-03:00", "2026-11-02T08:30:00-03:00"),
            session("2026-11-02T23:50:00-03:00", "2026-11-03T00:20:00-03:00"),
            session("2026-11-03T09:00:00-03:00", "2026-11-03T09:05:00-03:00"),
        )
        val days = DayAggregator(r).aggregate(
            sessions,
            emptyList(),
            LocalDate.of(2026, 11, 2),
            LocalDate.of(2026, 11, 3),
        )
        val d2 = days[0]
        val d3 = days[1]
        assertEquals(40 * 60_000L, d2.unlockedMs)
        assertEquals(2, d2.unlocks)
        assertEquals(ms("2026-11-02T08:00:00-03:00"), d2.firstUnlockUtc)
        assertEquals(ms("2026-11-02T08:30:00-03:00"), d2.lastLockUtc)
        assertEquals(30 * 60_000L, d2.hourlyMs[8])
        assertEquals(10 * 60_000L, d2.hourlyMs[23])
        assertEquals(25 * 60_000L, d3.unlockedMs)
        assertEquals(1, d3.unlocks)
        assertEquals(ms("2026-11-03T09:05:00-03:00"), d3.lastLockUtc)
        assertEquals(20 * 60_000L, d3.hourlyMs[0])
    }

    @Test
    fun relogioDoAparelhoAtrasadoPertoDaMeiaNoite() {
        // Aparelho marca 23:58 do dia 2, mas a hora real é 00:03 do dia 3 (5 min atrasado).
        val tsDevice = ms("2026-11-02T23:58:00-03:00")
        val deviceOffset = -5 * 60_000L // relógio_do_aparelho − hora_real
        val tsUtc = tsDevice - deviceOffset
        assertEquals(LocalDate.of(2026, 11, 3), DayResolver({ sp }).dayOf(tsUtc))
    }
}
