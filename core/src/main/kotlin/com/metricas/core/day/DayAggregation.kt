package com.metricas.core.day

import com.metricas.core.model.Interval
import com.metricas.core.model.TimeQuality
import com.metricas.core.session.Session
import com.metricas.core.session.SessionFlags
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class DaySlice(val date: LocalDate, val startUtc: Long, val endUtc: Long)

data class HourSlice(val date: LocalDate, val hour: Int, val ms: Long)

/**
 * Define o "dia" a partir da hora real: corte configurável (padrão 00:00) no fuso indicado,
 * respeitando horário de verão (dias de 23 h/25 h).
 */
class DayResolver(
    private val zoneAt: (Long) -> ZoneId,
    val cutoffHour: Int = 0,
) {
    init {
        require(cutoffHour in 0..23) { "cutoffHour deve estar entre 0 e 23" }
    }

    fun zone(tsUtc: Long): ZoneId = zoneAt(tsUtc)

    fun zoneForDate(date: LocalDate): ZoneId =
        zoneAt(date.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant().toEpochMilli())

    fun dayStartUtc(date: LocalDate, zone: ZoneId = zoneForDate(date)): Long {
        val zdt = if (cutoffHour == 0) {
            date.atStartOfDay(zone)
        } else {
            ZonedDateTime.of(date, LocalTime.of(cutoffHour, 0), zone)
        }
        return zdt.toInstant().toEpochMilli()
    }

    fun dayOf(tsUtc: Long): LocalDate {
        val zone = zoneAt(tsUtc)
        var date = Instant.ofEpochMilli(tsUtc).atZone(zone).toLocalDate()
        if (tsUtc < dayStartUtc(date, zone)) {
            date = date.minusDays(1)
        } else if (tsUtc >= dayStartUtc(date.plusDays(1), zone)) {
            date = date.plusDays(1)
        }
        return date
    }

    fun bounds(date: LocalDate): Interval {
        val zone = zoneForDate(date)
        return Interval(dayStartUtc(date, zone), dayStartUtc(date.plusDays(1), zone))
    }

    fun hourOf(tsUtc: Long): Int = Instant.ofEpochMilli(tsUtc).atZone(zoneAt(tsUtc)).hour

    /** Corta um intervalo nas viradas de dia que ele atravessa. */
    fun split(startUtc: Long, endUtc: Long): List<DaySlice> {
        val out = ArrayList<DaySlice>()
        var s = startUtc
        var guard = 0
        while (s < endUtc && guard++ < 100_000) {
            val date = dayOf(s)
            val next = dayStartUtc(date.plusDays(1), zoneAt(s))
            val e = minOf(endUtc, next)
            if (e <= s) break
            out += DaySlice(date, s, e)
            s = e
        }
        return out
    }

    /** Corta uma fatia de dia em horas do relógio local. */
    fun splitHours(slice: DaySlice): List<HourSlice> {
        val out = ArrayList<HourSlice>()
        val zone = zoneAt(slice.startUtc)
        var s = slice.startUtc
        var guard = 0
        while (s < slice.endUtc && guard++ < 100_000) {
            val zdt = Instant.ofEpochMilli(s).atZone(zone)
            val next = zdt.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val e = minOf(slice.endUtc, next)
            if (e <= s) break
            out += HourSlice(slice.date, zdt.hour, e - s)
            s = e
        }
        return out
    }
}

/** Totais de um dia. */
class DayAggregate(
    val date: LocalDate,
    val zoneId: String,
    val dayStartUtc: Long,
    val dayEndUtc: Long,
) {
    var unlockedMs: Long = 0
    var screenOnMs: Long = 0
    var unlocks: Int = 0
    var longestSessionMs: Long = 0
    val sessionDurations: MutableList<Long> = ArrayList()
    var firstUnlockUtc: Long? = null
    var lastLockUtc: Long? = null
    val hourlyMs: LongArray = LongArray(24)
    val hourlyUnlocks: IntArray = IntArray(24)
    var quality: TimeQuality = TimeQuality.VERIFICADA
    var flags: Int = 0
    var hasOpenSession: Boolean = false

    val dayLengthMs: Long get() = dayEndUtc - dayStartUtc
    val sessions: Int get() = sessionDurations.size

    val medianSessionMs: Long
        get() {
            if (sessionDurations.isEmpty()) return 0
            val sorted = sessionDurations.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
}

class DayAggregator(private val resolver: DayResolver) {

    fun aggregate(
        sessions: List<Session>,
        screenOn: List<Interval>,
        fromDay: LocalDate,
        toDay: LocalDate,
    ): List<DayAggregate> {
        val map = LinkedHashMap<LocalDate, DayAggregate>()
        var d = fromDay
        while (!d.isAfter(toDay)) {
            val zone = resolver.zoneForDate(d)
            map[d] = DayAggregate(d, zone.id, resolver.dayStartUtc(d, zone), resolver.dayStartUtc(d.plusDays(1), zone))
            d = d.plusDays(1)
        }

        for (s in sessions) {
            if (s.endUtc <= s.startUtc) continue
            map[resolver.dayOf(s.startUtc)]?.let { a ->
                // Desbloqueio = passou pela tela de bloqueio (ou aparelho sem bloqueio).
                // Religar a tela dentro do atraso de bloqueio é sessão, não desbloqueio.
                if (s.startReason.isUnlock) {
                    a.unlocks++
                    a.hourlyUnlocks[resolver.hourOf(s.startUtc)]++
                }
                a.sessionDurations += s.durationMs
                a.longestSessionMs = maxOf(a.longestSessionMs, s.durationMs)
                a.firstUnlockUtc = minOf(a.firstUnlockUtc ?: Long.MAX_VALUE, s.startUtc)
                a.flags = a.flags or (s.flags and (SessionFlags.LONG or SessionFlags.ESTIMATED_END))
                if (s.isOpen) a.hasOpenSession = true
            }
            for (slice in resolver.split(s.startUtc, s.endUtc)) {
                val a = map[slice.date] ?: continue
                a.unlockedMs += slice.endUtc - slice.startUtc
                a.quality = TimeQuality.worst(a.quality, s.quality)
                for (h in resolver.splitHours(slice)) a.hourlyMs[h.hour] += h.ms
            }
            if (!s.isOpen) {
                map[resolver.dayOf(s.endUtc - 1)]?.let { a ->
                    a.lastLockUtc = maxOf(a.lastLockUtc ?: Long.MIN_VALUE, s.endUtc)
                }
            }
        }

        for (iv in screenOn) {
            for (slice in resolver.split(iv.startUtc, iv.endUtc)) {
                map[slice.date]?.let { it.screenOnMs += slice.endUtc - slice.startUtc }
            }
        }
        return map.values.toList()
    }
}
