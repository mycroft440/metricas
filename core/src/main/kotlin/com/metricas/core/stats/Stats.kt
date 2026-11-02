package com.metricas.core.stats

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.sqrt

data class DayValue(val date: LocalDate, val valueMs: Long)

data class StatSummary(
    val n: Int,
    val totalMs: Long,
    val meanMs: Double,
    val medianMs: Double,
    val sdMs: Double,
    val p25Ms: Double,
    val p75Ms: Double,
    val min: DayValue,
    val max: DayValue,
    val ci95LowMs: Double?,
    val ci95HighMs: Double?,
)

/** Estatísticas sobre totais diários (Seção 10 do plano). */
object Stats {

    /** Percentil com interpolação linear (tipo 7). [sorted] precisa estar ordenada. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        require(p in 0.0..1.0)
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val h = (sorted.size - 1) * p
        val lo = floor(h).toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
    }

    fun mean(values: List<Double>): Double = if (values.isEmpty()) Double.NaN else values.sum() / values.size

    /** Desvio-padrão amostral (n − 1). */
    fun sampleSd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    /** Valor crítico t de Student bicaudal para 95 %. */
    fun tCritical95(df: Int): Double {
        require(df >= 1)
        val table = doubleArrayOf(
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
            2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042,
        )
        return when {
            df <= 30 -> table[df - 1]
            df <= 40 -> 2.021
            df <= 60 -> 2.000
            df <= 120 -> 1.980
            else -> 1.960
        }
    }

    fun summarize(values: List<DayValue>, minNForCi: Int = 5): StatSummary? {
        if (values.isEmpty()) return null
        val doubles = values.map { it.valueMs.toDouble() }
        val sorted = doubles.sorted()
        val n = values.size
        val m = mean(doubles)
        val sd = sampleSd(doubles)
        val ci = if (n >= minNForCi) tCritical95(n - 1) * sd / sqrt(n.toDouble()) else null
        return StatSummary(
            n = n,
            totalMs = values.sumOf { it.valueMs },
            meanMs = m,
            medianMs = percentile(sorted, 0.5),
            sdMs = sd,
            p25Ms = percentile(sorted, 0.25),
            p75Ms = percentile(sorted, 0.75),
            min = values.minWith(compareBy<DayValue>({ it.valueMs }, { it.date })),
            max = values.maxWith(compareBy<DayValue>({ it.valueMs }, { it.date })),
            ci95LowMs = ci?.let { (m - it).coerceAtLeast(0.0) },
            ci95HighMs = ci?.let { m + it },
        )
    }

    /** Média móvel por data: considera os dias em (d − window, d], exige [minPoints] dias. */
    fun movingAverage(values: List<DayValue>, window: Int = 7, minPoints: Int = 4): List<Pair<LocalDate, Double?>> {
        val sorted = values.sortedBy { it.date }
        return sorted.map { v ->
            val from = v.date.minusDays(window.toLong() - 1)
            val inWindow = sorted.filter { !it.date.isBefore(from) && !it.date.isAfter(v.date) }
            v.date to if (inWindow.size >= minPoints) inWindow.map { it.valueMs.toDouble() }.average() else null
        }
    }

    fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    fun weekdayWeekend(values: List<DayValue>): Pair<StatSummary?, StatSummary?> =
        summarize(values.filter { !isWeekend(it.date) }) to summarize(values.filter { isWeekend(it.date) })

    fun byDayOfWeek(values: List<DayValue>): Map<DayOfWeek, StatSummary?> =
        DayOfWeek.values().associateWith { dow -> summarize(values.filter { it.date.dayOfWeek == dow }) }

    /** Inclinação da regressão linear, em ms/dia por semana. Requer ao menos [minPoints] dias. */
    fun trendPerWeekMs(values: List<DayValue>, minPoints: Int = 7): Double? {
        if (values.size < minPoints) return null
        val xs = values.map { it.date.toEpochDay().toDouble() }
        val ys = values.map { it.valueMs.toDouble() }
        val mx = mean(xs)
        val my = mean(ys)
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        if (den == 0.0) return null
        return num / den * 7.0
    }

    /** Variação relativa da média de (fim − dias, fim] em relação ao período anterior de mesmo tamanho. */
    fun periodChange(values: List<DayValue>, end: LocalDate, days: Int = 7, minPoints: Int = 3): Double? {
        val currentFrom = end.minusDays(days.toLong() - 1)
        val previousFrom = currentFrom.minusDays(days.toLong())
        val current = values.filter { !it.date.isBefore(currentFrom) && !it.date.isAfter(end) }
        val previous = values.filter { !it.date.isBefore(previousFrom) && it.date.isBefore(currentFrom) }
        if (current.size < minPoints || previous.size < minPoints) return null
        val mc = current.map { it.valueMs.toDouble() }.average()
        val mp = previous.map { it.valueMs.toDouble() }.average()
        if (mp == 0.0) return null
        return (mc - mp) / mp
    }

    /**
     * Curva acumulada típica: posição h = média, entre os dias, do total acumulado até o fim da hora h.
     */
    fun typicalCumulative(hourlyPerDay: List<LongArray>): DoubleArray {
        val out = DoubleArray(24)
        if (hourlyPerDay.isEmpty()) return out
        for (day in hourlyPerDay) {
            var acc = 0L
            for (h in 0 until 24) {
                acc += day.getOrElse(h) { 0L }
                out[h] += acc.toDouble()
            }
        }
        for (h in 0 until 24) out[h] /= hourlyPerDay.size
        return out
    }

    /** Valor típico acumulado num instante fracionário do dia (ex.: 14,5 = 14:30). */
    fun typicalAt(cumulative: DoubleArray, hourOfDay: Double): Double {
        if (cumulative.isEmpty()) return 0.0
        val h = hourOfDay.coerceIn(0.0, 24.0)
        val whole = floor(h).toInt()
        if (whole >= 24) return cumulative[23]
        val before = if (whole == 0) 0.0 else cumulative[whole - 1]
        val frac = h - whole
        return before + (cumulative[whole] - before) * frac
    }
}
