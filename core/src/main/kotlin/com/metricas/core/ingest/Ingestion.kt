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
        historyShiftMs: Long = 0,
    ): List<DeviceEvent> {
        val exact = existing.filter { it.epochId == currentEpoch }.map { it.type to it.tsDevice }.toHashSet()
        val anyEpoch: Set<Pair<Int, Long>> =
            if (historyShiftMs != 0L) existing.map { it.type to it.tsDevice }.toHashSet() else emptySet()
        val byType = existing.groupBy({ it.type }, { it.tsUtc }).mapValues { it.value.sorted() }
        val seen = HashSet<Pair<Int, Long>>()
        return candidates.filter { c ->
            val key = c.type to c.tsDevice
            if (!seen.add(key)) return@filter false
            if (key in exact) return@filter false
            if (historyShiftMs != 0L && (c.type to c.tsDevice - historyShiftMs) in anyEpoch) return@filter false
            val utc = c.tsDevice - offsetMs
            val list = byType[c.type] ?: return@filter true
            !hasNear(list, utc, fuzzyToleranceMs)
        }
    }

    /**
     * Detecta se o Android deslocou o histórico desde a última leitura (ele faz isso a cada
     * ajuste de relógio > 2 s, inclusive no boot quando o relógio de hardware estava errado).
     *
     * Compara os eventos já guardados que deveriam reaparecer (a janela de sobreposição) com os
     * recém-lidos: o deslocamento é a diferença exata de carimbo mais frequente entre pares do
     * mesmo tipo. Retorna 0 se não houve deslocamento ou se não há evidência suficiente.
     */
    fun detectHistoryShift(
        candidates: List<DeviceEvent>,
        expectedInOverlap: List<StoredEventKey>,
        maxShiftMs: Long = 2L * 24 * 60 * 60 * 1000,
        /** Ajuste de relógio medido pelo app (aproximado); permite confirmar com 1 só evento. */
        hintShiftMs: Long? = null,
        hintToleranceMs: Long = 250,
    ): Long {
        if (expectedInOverlap.isEmpty() || candidates.isEmpty()) return 0
        if (expectedInOverlap.size == 1) {
            // Pouca evidência: só aceita se bater com o ajuste de relógio que o app mediu.
            val hint = hintShiftMs ?: return 0
            val e = expectedInOverlap[0]
            if (candidates.any { it.type == e.type && it.tsDevice == e.tsDevice }) return 0
            val match = candidates
                .filter { it.type == e.type && kotlin.math.abs(it.tsDevice - e.tsDevice - hint) <= hintToleranceMs }
                .minByOrNull { kotlin.math.abs(it.tsDevice - e.tsDevice - hint) }
            return match?.let { it.tsDevice - e.tsDevice } ?: 0
        }
        val byType = candidates.groupBy({ it.type }, { it.tsDevice }).mapValues { it.value.sorted() }
        val counts = HashMap<Long, Int>()
        for (e in expectedInOverlap) {
            val list = byType[e.type] ?: continue
            var i = lowerBound(list, e.tsDevice - maxShiftMs)
            while (i < list.size && list[i] <= e.tsDevice + maxShiftMs) {
                val diff = list[i] - e.tsDevice
                counts[diff] = (counts[diff] ?: 0) + 1
                i++
            }
        }
        val needed = maxOf(2, (expectedInOverlap.size + 1) / 2)
        if ((counts[0L] ?: 0) >= needed) return 0
        val best = counts.entries.maxWithOrNull(compareBy<Map.Entry<Long, Int>>({ it.value }, { -kotlin.math.abs(it.key) }))
            ?: return 0
        return if (best.value >= needed) best.key else 0
    }

    private fun lowerBound(sorted: List<Long>, value: Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
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
