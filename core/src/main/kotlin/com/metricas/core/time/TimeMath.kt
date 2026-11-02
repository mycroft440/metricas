package com.metricas.core.time

import com.metricas.core.model.TimeQuality
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Fontes de hora. [authenticated] = o caminho até o servidor é protegido contra adulteração
 * (TLS / Play services). NTP e a hora de rede do sistema trafegam sem autenticação.
 */
enum class TimeSource(val label: String, val authenticated: Boolean) {
    TRUSTED_TIME("Google TrustedTime", true),
    NETWORK_CLOCK("Hora de rede do Android", false),
    SNTP("NTP", false),
    HTTPS_DATE("Cabeçalho HTTPS Date", true),
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
    /** Confirmada por fonte autenticada ou por duas fontes independentes que concordam. */
    val corroborated: Boolean = true,
)

object TimeMath {
    /** Deriva conservadora do oscilador do aparelho. */
    const val DRIFT_PPM = 50L
    const val VERIFIED_MAX_ERROR_MS = 2_000L
    const val JUMP_THRESHOLD_MS = 2_000L

    /** Concordância mínima exigida entre duas fontes. */
    const val AGREEMENT_MIN_MS = 2_000L

    /** Limites de plausibilidade: rejeita respostas absurdas (servidor quebrado ou falsificado). */
    const val MIN_PLAUSIBLE_UTC = 1_735_689_600_000L // 2025-01-01
    const val MAX_PLAUSIBLE_UTC = 4_102_444_800_000L // 2100-01-01

    fun trueAt(anchor: TimeAnchor, elapsedMs: Long): Long = anchor.trueUtcMs + (elapsedMs - anchor.elapsedMs)

    fun errorAt(anchor: TimeAnchor, elapsedMs: Long): Long =
        anchor.errorMs + abs(elapsedMs - anchor.elapsedMs) * DRIFT_PPM / 1_000_000

    fun quality(errorMs: Long, corroborated: Boolean = true): TimeQuality =
        if (corroborated && errorMs <= VERIFIED_MAX_ERROR_MS) TimeQuality.VERIFICADA else TimeQuality.INFERIDA

    /** Salto do relógio do aparelho entre duas leituras do mesmo boot. */
    fun clockJumpMs(prevElapsed: Long, prevWall: Long, nowElapsed: Long, nowWall: Long): Long =
        (nowWall - prevWall) - (nowElapsed - prevElapsed)

    /** Tolera a deriva natural (100 ppm do intervalo) para não confundir deriva com ajuste. */
    fun isClockJump(deltaMs: Long, elapsedSpanMs: Long): Boolean =
        abs(deltaMs) > JUMP_THRESHOLD_MS + abs(elapsedSpanMs) / 10_000

    fun isPlausible(sample: TimeSample): Boolean =
        sample.trueUtcMs in MIN_PLAUSIBLE_UTC..MAX_PLAUSIBLE_UTC && sample.errorMs >= 0

    fun agree(a: TimeSample, b: TimeSample): Boolean =
        abs(a.elapsedToUtcMs - b.elapsedToUtcMs) <= maxOf(AGREEMENT_MIN_MS, a.errorMs + b.errorMs)

    data class Choice(
        val best: TimeSample,
        val disagreementMs: Long,
        /** Medições descartadas por discordarem do consenso. */
        val rejected: List<TimeSample>,
        val corroborated: Boolean,
    )

    /**
     * Escolha por consenso (não só "menor erro"), para que uma única fonte falsificada ou
     * quebrada — por exemplo um NTP adulterado na rede — não defina a hora do app:
     *
     * 1. descarta respostas implausíveis;
     * 2. se há fonte autenticada (TrustedTime/HTTPS), ela é a referência: só valem as
     *    medições que concordam com ela;
     * 3. sem fonte autenticada, vale o maior grupo de fontes que concordam entre si;
     * 4. dentro do grupo vencedor, usa a de menor erro.
     */
    fun choose(samples: List<TimeSample>): Choice? {
        val valid = samples.filter { isPlausible(it) }
        if (valid.isEmpty()) return null
        val byError = compareBy<TimeSample>({ it.errorMs }, { it.source.ordinal })

        val trusted = valid.filter { it.source.authenticated }
        val group: List<TimeSample> = if (trusted.isNotEmpty()) {
            val reference = trusted.minWith(byError)
            valid.filter { it === reference || agree(it, reference) }
        } else {
            valid.map { c -> valid.filter { it === c || agree(it, c) } }
                .maxWith(compareBy<List<TimeSample>>({ it.size }, { -it.minOf { s -> s.errorMs } }))
        }
        val best = group.minWith(byError)
        val rejected = valid.filter { s -> group.none { it === s } }
        val disagreement = valid.maxOf { abs(it.elapsedToUtcMs - best.elapsedToUtcMs) }
        val corroborated = best.source.authenticated || group.map { it.source to it.server }.toSet().size >= 2
        return Choice(best, disagreement, rejected, corroborated)
    }
}

object HttpDateMath {
    /**
     * O cabeçalho `Date` tem resolução de 1 s (truncado): a hora real no servidor está em
     * [D, D + 1000). Supõe que foi gerado no meio da ida e volta. Se a resposta veio de um
     * cache intermediário, o cabeçalho `Age` (segundos) indica há quanto tempo foi gerada.
     *
     * @return hora real no instante [t3ElapsedMs] e o erro estimado.
     */
    fun estimate(dateHeaderMs: Long, t0ElapsedMs: Long, t3ElapsedMs: Long, ageSeconds: Long = 0): Pair<Long, Long> {
        val rtt = (t3ElapsedMs - t0ElapsedMs).coerceAtLeast(0)
        val age = ageSeconds.coerceAtLeast(0)
        val trueAtT3 = dateHeaderMs + age * 1000 + 500 + rtt / 2
        val error = 500 + rtt / 2 + if (age > 0) 1_000 else 0
        return trueAtT3 to error
    }

    fun parseRfc1123(value: String): Long? = try {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
