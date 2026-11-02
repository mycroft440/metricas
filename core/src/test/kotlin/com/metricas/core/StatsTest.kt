package com.metricas.core

import com.metricas.core.stats.DayValue
import com.metricas.core.stats.Stats
import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StatsTest {
    private val monday = LocalDate.of(2026, 11, 2)

    private fun values(vararg v: Long) = v.mapIndexed { i, x -> DayValue(monday.plusDays(i.toLong()), x) }

    @Test
    fun resumoBasico() {
        val s = assertNotNull(Stats.summarize(values(10, 20, 30, 40, 50)))
        assertEquals(5, s.n)
        assertEquals(150L, s.totalMs)
        assertEquals(30.0, s.meanMs, 1e-9)
        assertEquals(30.0, s.medianMs, 1e-9)
        assertEquals(sqrt(250.0), s.sdMs, 1e-9)
        assertEquals(20.0, s.p25Ms, 1e-9)
        assertEquals(40.0, s.p75Ms, 1e-9)
        assertEquals(10L, s.min.valueMs)
        assertEquals(50L, s.max.valueMs)
        val half = 2.776 * sqrt(250.0) / sqrt(5.0)
        assertEquals(30.0 - half, assertNotNull(s.ci95LowMs), 1e-9)
        assertEquals(30.0 + half, assertNotNull(s.ci95HighMs), 1e-9)
    }

    @Test
    fun intervaloDeConfiancaSoComAmostraSuficiente() {
        assertNull(assertNotNull(Stats.summarize(values(10, 20, 30))).ci95LowMs)
    }

    @Test
    fun percentilInterpolado() {
        assertEquals(2.5, Stats.percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.5), 1e-9)
        assertEquals(1.0, Stats.percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.0), 1e-9)
        assertEquals(4.0, Stats.percentile(listOf(1.0, 2.0, 3.0, 4.0), 1.0), 1e-9)
    }

    @Test
    fun mediaMovel() {
        val ma = Stats.movingAverage(values(1, 2, 3, 4, 5, 6, 7))
        assertNull(ma[2].second)
        assertEquals(2.5, assertNotNull(ma[3].second), 1e-9)
        assertEquals(4.0, assertNotNull(ma[6].second), 1e-9)
    }

    @Test
    fun tendenciaSemanal() {
        val v = (0 until 10).map { DayValue(monday.plusDays(it.toLong()), 100L + 10L * it) }
        assertEquals(70.0, assertNotNull(Stats.trendPerWeekMs(v)), 1e-9)
    }

    @Test
    fun variacaoEntrePeriodos() {
        val v = (0 until 14).map { DayValue(monday.plusDays(it.toLong()), if (it < 7) 100L else 150L) }
        assertEquals(0.5, assertNotNull(Stats.periodChange(v, monday.plusDays(13))), 1e-9)
    }

    @Test
    fun diasUteisEFimDeSemana() {
        val (weekday, weekend) = Stats.weekdayWeekend(values(10, 10, 10, 10, 10, 40, 60))
        assertEquals(5, assertNotNull(weekday).n)
        assertEquals(2, assertNotNull(weekend).n)
        assertEquals(50.0, weekend!!.meanMs, 1e-9)
    }

    @Test
    fun curvaAcumuladaTipica() {
        val d1 = LongArray(24).also { it[0] = 10; it[1] = 20 }
        val d2 = LongArray(24).also { it[0] = 30 }
        val cum = Stats.typicalCumulative(listOf(d1, d2))
        assertEquals(20.0, cum[0], 1e-9)
        assertEquals(30.0, cum[1], 1e-9)
        assertEquals(30.0, cum[23], 1e-9)
        assertEquals(25.0, Stats.typicalAt(cum, 1.5), 1e-9)
        assertEquals(10.0, Stats.typicalAt(cum, 0.5), 1e-9)
    }
}
