package com.metricas.core

import com.metricas.core.coverage.Coverage
import com.metricas.core.coverage.DayStatus
import com.metricas.core.ingest.DeviceEvent
import com.metricas.core.ingest.EventDedupe
import com.metricas.core.ingest.Observation
import com.metricas.core.ingest.StoredEventKey
import com.metricas.core.model.Interval
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageAndIngestionTest {
    private val day = 86_400_000L
    private val hour = 3_600_000L

    @Test
    fun mesclaIntervalos() {
        // Intervalos separados por até 1 s são unidos; lacunas maiores são preservadas.
        val merged = Coverage.merge(
            listOf(Interval(10_000, 20_000), Interval(0, 12_000), Interval(30_000, 40_000), Interval(40_500, 50_000)),
        )
        assertEquals(listOf(Interval(0, 20_000), Interval(30_000, 50_000)), merged)
    }

    @Test
    fun statusDosDias() {
        val d0 = 10 * day
        val merged = Coverage.merge(listOf(Interval(d0 + 12 * hour, d0 + 2 * day), Interval(d0 + 3 * day, d0 + 5 * day + 6 * hour)))
        val now = d0 + 5 * day + 6 * hour
        assertEquals(DayStatus.NO_DATA, Coverage.evaluate(d0 - day, d0, now, merged).status)
        assertEquals(DayStatus.PARTIAL, Coverage.evaluate(d0, d0 + day, now, merged).status)
        assertEquals(DayStatus.COMPLETE, Coverage.evaluate(d0 + day, d0 + 2 * day, now, merged).status)
        assertEquals(DayStatus.GAP, Coverage.evaluate(d0 + 2 * day, d0 + 3 * day, now, merged).status)
        assertEquals(DayStatus.COMPLETE, Coverage.evaluate(d0 + 4 * day, d0 + 5 * day, now, merged).status)
        val today = Coverage.evaluate(d0 + 5 * day, d0 + 6 * day, now, merged)
        assertEquals(DayStatus.PARTIAL, today.status)
        assertEquals(6 * hour, today.coveredMs)
    }

    @Test
    fun dedupeNaMesmaEpocaEExato() {
        val existing = listOf(StoredEventKey(tsDevice = 1_000, tsUtc = 1_000, type = 15, epochId = 7))
        val fresh = EventDedupe.filterNew(
            listOf(DeviceEvent(1_000, 15), DeviceEvent(1_500, 16), DeviceEvent(1_500, 16), DeviceEvent(1_000, 16)),
            offsetMs = 0,
            currentEpoch = 7,
            existing = existing,
        )
        assertEquals(listOf(DeviceEvent(1_500, 16), DeviceEvent(1_000, 16)), fresh)
    }

    @Test
    fun dedupeAposAjusteDeRelogioUsaHoraReal() {
        // Relógio adiantado 1 h: o sistema deslocou o histórico, o mesmo evento volta com outro carimbo.
        val existing = listOf(StoredEventKey(tsDevice = 50_000, tsUtc = 50_000, type = 18, epochId = 1))
        val fresh = EventDedupe.filterNew(
            listOf(DeviceEvent(50_000 + hour, 18), DeviceEvent(90_000 + hour, 18)),
            offsetMs = hour,
            currentEpoch = 2,
            existing = existing,
        )
        assertEquals(listOf(DeviceEvent(90_000 + hour, 18)), fresh)
    }

    @Test
    fun observacaoNaPrimeiraIngestaoComecaNoEventoMaisAntigo() {
        val end = 20 * day
        assertEquals(
            Interval(12 * day, end),
            Observation.observedInterval(end - 11 * day, end, null, 12 * day),
        )
    }

    @Test
    fun observacaoNormalUsaJanelaInteira() {
        val end = 20 * day
        val begin = end - 2 * hour - 30 * 60_000
        assertEquals(Interval(begin, end), Observation.observedInterval(begin, end, end - 2 * hour, end - hour))
    }

    @Test
    fun observacaoAposLacunaLonga() {
        val end = 20 * day
        val prev = end - 8 * day
        assertEquals(
            Interval(end - 6 * day, end),
            Observation.observedInterval(prev - 30 * 60_000, end, prev, end - 6 * day),
        )
    }
}
