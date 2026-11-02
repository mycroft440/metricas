package com.metricas.tempotela.ui

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object Fmt {
    val locale: Locale = Locale.forLanguageTag("pt-BR")
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val hhmmss = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
    private val shortDate = DateTimeFormatter.ofPattern("EEE, dd/MM", locale)
    private val longDate = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", locale)
    private val dateTime = DateTimeFormatter.ofPattern("dd/MM HH:mm", locale)

    fun duration(ms: Long): String {
        val totalMin = ((ms + 30_000) / 60_000).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h ${m.toString().padStart(2, '0')} min" else "$m min"
    }

    fun duration(ms: Double?): String = if (ms == null || ms.isNaN()) "—" else duration(ms.roundToLong())

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 3600}:${((s % 3600) / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun time(utc: Long?, zone: ZoneId): String =
        utc?.let { hhmm.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "—"

    fun timeSec(utc: Long, zone: ZoneId): String = hhmmss.format(Instant.ofEpochMilli(utc).atZone(zone))

    fun dateTime(utc: Long?, zone: ZoneId): String =
        utc?.let { dateTime.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "—"

    fun date(date: LocalDate): String = shortDate.format(date)

    fun dateLong(date: LocalDate): String = longDate.format(date).replaceFirstChar { it.uppercase(locale) }

    fun dayOfWeek(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, locale)

    fun signedMs(ms: Long): String {
        val sign = if (ms >= 0) "+" else "−"
        val a = abs(ms)
        return when {
            a < 1_000 -> "$sign$a ms"
            a < 60_000 -> "$sign${"%.1f".format(locale, a / 1000.0)} s"
            else -> "$sign${duration(a)}"
        }
    }

    fun percent(ratio: Double?): String =
        if (ratio == null || ratio.isNaN()) "—" else "${if (ratio >= 0) "+" else "−"}${"%.0f".format(locale, abs(ratio) * 100)}%"

    fun ago(ms: Long?): String {
        if (ms == null) return "—"
        val min = ms / 60_000
        return when {
            min < 1 -> "agora há pouco"
            min < 60 -> "há $min min"
            min < 48 * 60 -> "há ${min / 60} h"
            else -> "há ${min / (24 * 60)} dias"
        }
    }

    fun status(status: String): String = when (status) {
        "COMPLETE" -> "Completo"
        "PARTIAL" -> "Parcial"
        "GAP" -> "Lacuna"
        else -> "Sem dados"
    }
}
