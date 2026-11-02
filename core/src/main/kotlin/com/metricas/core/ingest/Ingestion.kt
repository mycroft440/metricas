package com.metricas.core.ingest

import com.metricas.core.model.Interval
import kotlin.math.abs

/** Evento como vem do sistema: timestamp no relógio do aparelho. */
data class DeviceEvent(val tsDevice: Long, val type: Int)

/** Chave de um evento já gravado, para deduplicação. */
data class StoredEventKey(val tsDevice: Long, val tsUtc: Long, val type: Int, val epochId: Long)

object EventDedupe {
    /**
     * Mesma época de relógio: o timestamp do aparelho é estável → comparação exata.
     * Época diferente (reboot ou ajuste de relógio): o sistema pode ter deslocado o histórico →
     * comparação pela hora real com tolerância.
     */
    fun filterNew(
        candidates: List<DeviceEvent>,
        offsetMs: Long,
        currentEpoch: Long,
        existing: List<StoredEventKey>,
        fuzzyToleranceMs: Long = 1_500,
    ): List<DeviceEvent> {
        val exact = existing.filter { it.epochId == currentEpoch }.map { it.type to it.tsDevice }.toHashSet()
        val others = existing.filter { it.epochId != currentEpoch }.groupBy({ it.type }, { it.tsUtc })
        val seen = HashSet<Pair<Int, Long>>()
        return candidates.filter { c ->
            val key = c.type to c.tsDevice
            if (!seen.add(key)) return@filter false
            if (key in exact) return@filter false
            val utc = c.tsDevice - offsetMs
            val near = others[c.type]?.any { abs(it - utc) <= fuzzyToleranceMs } ?: false
            !near
        }
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
}
