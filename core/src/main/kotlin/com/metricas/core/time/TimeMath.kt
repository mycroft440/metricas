package com.metricas.core.time

import com.metricas.core.model.TimeQuality
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class TimeSource(val label: String) {
    TRUSTED_TIME("Google TrustedTime"),
    NETWORK_CLOCK("Hora de rede do Android"),
    SNTP("NTP"),
    HTTPS_DATE("Cabeçalho HTTPS Date"),
}

/**
 * Uma medição da hora real.
 *
 * [elapsedMs] é o relógio monotônico (`SystemClock.elapsedRealtime`) no instante em que
 * [trueUtcMs] (hora da internet) e [deviceWallMs] (relógio do aparelho) valiam o indicado.
 */
data class TimeSample(
    val source: TimeSource,
    val server: String,
    val elapsedMs: Long,
    val trueUtcMs: Long,
    val deviceWallMs: Long,
    val errorMs: Long,
    val rttMs: Long? = null,
) {
    /** hora_real − elapsedRealtime: constante durante um boot (a menos da deriva do cristal). */
    val elapsedToUtcMs: Long get() = trueUtcMs - elapsedMs

    /** relógio_do_aparelho − hora_real. Positivo = aparelho adiantado. */
    val deviceOffsetMs: Long get() = deviceWallMs - trueUtcMs
}

/** Âncora monotônica: liga o `elapsedRealtime` de um boot à hora real. */
data class TimeAnchor(
    val bootCount: Int,
    val elapsedMs: Long,
    val trueUtcMs: Long,
    val errorMs: Long,
    val source: TimeSource,
    val server: String,
)

object TimeMath {
    /** Deriva conservadora do oscilador do aparelho. */
    const val DRIFT_PPM = 50L
    const val VERIFIED_MAX_ERROR_MS = 2_000L
    const val JUMP_THRESHOLD_MS = 2_000L

    fun trueAt(anchor: TimeAnchor, elapsedMs: Long): Long = anchor.trueUtcMs + (elapsedMs - anchor.elapsedMs)

    fun errorAt(anchor: TimeAnchor, elapsedMs: Long): Long =
        anchor.errorMs + abs(elapsedMs - anchor.elapsedMs) * DRIFT_PPM / 1_000_000

    fun quality(errorMs: Long): TimeQuality =
        if (errorMs <= VERIFIED_MAX_ERROR_MS) TimeQuality.VERIFICADA else TimeQuality.INFERIDA

    /** Salto do relógio do aparelho entre duas leituras do mesmo boot. */
    fun clockJumpMs(prevElapsed: Long, prevWall: Long, nowElapsed: Long, nowWall: Long): Long =
        (nowWall - prevWall) - (nowElapsed - prevElapsed)

    /** Tolera a deriva natural (100 ppm do intervalo) para não confundir deriva com ajuste. */
    fun isClockJump(deltaMs: Long, elapsedSpanMs: Long): Boolean =
        abs(deltaMs) > JUMP_THRESHOLD_MS + abs(elapsedSpanMs) / 10_000

    data class Choice(val best: TimeSample, val disagreementMs: Long)

    /** Escolhe a medição de menor erro e mede a maior divergência entre as fontes. */
    fun choose(samples: List<TimeSample>): Choice? {
        if (samples.isEmpty()) return null
        val best = samples.minWith(compareBy<TimeSample>({ it.errorMs }, { it.source.ordinal }))
        val disagreement = samples.maxOf { abs(it.elapsedToUtcMs - best.elapsedToUtcMs) }
        return Choice(best, disagreement)
    }
}

object HttpDateMath {
    /**
     * O cabeçalho `Date` tem resolução de 1 s (truncado): a hora real no servidor está em
     * [D, D + 1000). Supõe que foi gerado no meio da ida e volta.
     *
     * @return hora real no instante [t3ElapsedMs] e o erro estimado.
     */
    fun estimate(dateHeaderMs: Long, t0ElapsedMs: Long, t3ElapsedMs: Long): Pair<Long, Long> {
        val rtt = (t3ElapsedMs - t0ElapsedMs).coerceAtLeast(0)
        val trueAtT3 = dateHeaderMs + 500 + rtt / 2
        val error = 500 + rtt / 2
        return trueAtT3 to error
    }

    fun parseRfc1123(value: String): Long? = try {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
