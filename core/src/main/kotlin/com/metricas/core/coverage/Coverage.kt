package com.metricas.core.coverage

import com.metricas.core.model.Interval

enum class DayStatus { COMPLETE, PARTIAL, GAP, NO_DATA }

data class CoverageResult(val status: DayStatus, val coveredMs: Long, val ratio: Double)

/** Diferencia "usou 0 min" de "não sabemos" (Seção 9 do plano). */
object Coverage {

    fun merge(intervals: List<Interval>, joinGapMs: Long = 1_000): List<Interval> {
        val sorted = intervals.filter { it.endUtc > it.startUtc }.sortedBy { it.startUtc }
        val out = ArrayList<Interval>()
        for (iv in sorted) {
            val last = out.lastOrNull()
            if (last != null && iv.startUtc <= last.endUtc + joinGapMs) {
                out[out.size - 1] = Interval(last.startUtc, maxOf(last.endUtc, iv.endUtc))
            } else {
                out += iv
            }
        }
        return out
    }

    fun coveredMs(merged: List<Interval>, startUtc: Long, endUtc: Long): Long {
        if (endUtc <= startUtc) return 0
        var total = 0L
        for (iv in merged) {
            val s = maxOf(startUtc, iv.startUtc)
            val e = minOf(endUtc, iv.endUtc)
            if (e > s) total += e - s
        }
        return total
    }

    fun evaluate(
        dayStartUtc: Long,
        dayEndUtc: Long,
        nowUtc: Long,
        merged: List<Interval>,
        toleranceMs: Long = 60_000,
    ): CoverageResult {
        val dayLength = (dayEndUtc - dayStartUtc).coerceAtLeast(1)
        val covered = coveredMs(merged, dayStartUtc, minOf(dayEndUtc, nowUtc))
        val ratio = covered.toDouble() / dayLength
        if (merged.isEmpty()) return CoverageResult(DayStatus.NO_DATA, 0, 0.0)
        val firstObserved = merged.first().startUtc
        val status = when {
            dayEndUtc > nowUtc -> if (covered > 0) DayStatus.PARTIAL else DayStatus.NO_DATA
            covered >= dayLength - toleranceMs -> DayStatus.COMPLETE
            covered == 0L && dayStartUtc >= firstObserved -> DayStatus.GAP
            covered == 0L -> DayStatus.NO_DATA
            else -> DayStatus.PARTIAL
        }
        return CoverageResult(status, covered, ratio)
    }
}
