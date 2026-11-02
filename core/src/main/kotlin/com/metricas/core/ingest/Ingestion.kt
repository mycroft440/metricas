package com.metricas.core.ingest

import com.metricas.core.model.Interval
import kotlin.math.abs

/** Evento como vem do sistema: timestamp no relógio do aparelho. */
data class DeviceEvent(val tsDevice: Long, val type: Int)

/** Chave de um evento já gravado, para deduplicação. */
data class StoredEventKey(val tsDevice: Long, val tsUtc: Long, val type: Int, val epochId: Long)

object EventDedupe {
    /**
     * Um evento é repetido quando:
     * - na mesma época de relógio, o carimbo do aparelho é idêntico; ou
     * - em qualquer época, existe um evento do mesmo tipo a até [fuzzyToleranceMs] na hora real.
     *
     * A segunda regra cobre os deslocamentos que o próprio Android aplica ao histórico quando o
     * relógio é corrigido (inclusive correções pequenas de NTP, de poucos segundos, que não
     * chegam a abrir uma nova época), evitando contar o mesmo evento duas vezes.
     */
    fun filterNew(
        candidates: List<DeviceEvent>,
        offsetMs: Long,
        currentEpoch: Long,
        existing: List<StoredEventKey>,
        fuzzyToleranceMs: Long = 1_500,
    ): List<DeviceEvent> {
        val exact = existing.filter { it.epochId == currentEpoch }.map { it.type to it.tsDevice }.toHashSet()
        val byType = existing.groupBy({ it.type }, { it.tsUtc }).mapValues { it.value.sorted() }
        val seen = HashSet<Pair<Int, Long>>()
        return candidates.filter { c ->
            val key = c.type to c.tsDevice
            if (!seen.add(key)) return@filter false
            if (key in exact) return@filter false
            val utc = c.tsDevice - offsetMs
            val list = byType[c.type] ?: return@filter true
            !hasNear(list, utc, fuzzyToleranceMs)
        }
    }

    private fun hasNear(sorted: List<Long>, value: Long, tolerance: Long): Boolean {
        var idx = sorted.binarySearch(value)
        if (idx >= 0) return true
        idx = -idx - 1
        if (idx < sorted.size && abs(sorted[idx] - value) <= tolerance) return true
        if (idx > 0 && abs(sorted[idx - 1] - value) <= tolerance) return true
        return false
    }
}

object Observation {
    /** Sem ingestão por mais que isso, parte do histórico do sistema pode ter expirado. */
    const val SAFE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Intervalo de tempo realmente observado por uma ingestão, em hora real.
     * Na primeira ingestão (backfill) ou após uma lacuna longa, a cobertura começa no
     * evento mais antigo que o sistema ainda tinha.
     */
    fun observedInterval(
        queryBeginUtc: Long,
        queryEndUtc: Long,
        previousWatermarkUtc: Long?,
        oldestEventUtc: Long?,
    ): Interval {
        val firstOrLongGap = previousWatermarkUtc == null || queryEndUtc - previousWatermarkUtc > SAFE_RETENTION_MS
        val start = if (firstOrLongGap) maxOf(queryBeginUtc, oldestEventUtc ?: queryEndUtc) else queryBeginUtc
        return Interval(minOf(start, queryEndUtc), queryEndUtc)
    }

    /** Maior intervalo entre o fim de uma leitura e o início da seguinte (risco de perda). */
    fun largestGapMs(intervals: List<Interval>): Long {
        val sorted = intervals.sortedBy { it.startUtc }
        var maxEnd = Long.MIN_VALUE
        var largest = 0L
        for (iv in sorted) {
            if (maxEnd != Long.MIN_VALUE && iv.startUtc > maxEnd) largest = maxOf(largest, iv.startUtc - maxEnd)
            maxEnd = maxOf(maxEnd, iv.endUtc)
        }
        return largest
    }
}
