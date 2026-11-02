package com.metricas.core.time

import com.metricas.core.model.TimeQuality

/** Os três relógios do aparelho lidos no mesmo instante. */
data class ClockSnapshot(val bootCount: Int, val elapsedMs: Long, val wallMs: Long)

/**
 * Âncora estimada (sem internet): liga o `elapsedRealtime` deste boot a uma hora real
 * presumida. Mesmo sem internet, mudanças no relógio do aparelho durante o boot não afetam
 * a hora do app, porque a hora passa a avançar pelo relógio monotônico.
 */
data class InferredAnchor(
    val bootCount: Int,
    val elapsedMs: Long,
    val trueUtcMs: Long,
    /** true = baseada num desvio já verificado antes; false = relógio do aparelho sem verificação. */
    val fromKnownOffset: Boolean,
)

data class ClockReading(
    val trueNowUtc: Long,
    val deviceWallMs: Long,
    val elapsedMs: Long,
    val bootCount: Int,
    /** relógio_do_aparelho − hora_real. Positivo = aparelho adiantado. */
    val deviceOffsetMs: Long,
    val errorMs: Long?,
    val quality: TimeQuality,
    val source: TimeSource?,
    val server: String?,
    val anchorAgeMs: Long?,
    val inferred: Boolean,
)

object ClockLogic {
    /** O `elapsedRealtime` zera a cada boot: âncoras só valem no mesmo boot. */
    private const val ELAPSED_TOLERANCE_MS = 60_000L

    fun usable(anchor: TimeAnchor?, now: ClockSnapshot): TimeAnchor? =
        anchor?.takeIf { it.bootCount == now.bootCount && now.elapsedMs >= it.elapsedMs - ELAPSED_TOLERANCE_MS }

    fun usable(anchor: InferredAnchor?, now: ClockSnapshot): InferredAnchor? =
        anchor?.takeIf { it.bootCount == now.bootCount && now.elapsedMs >= it.elapsedMs - ELAPSED_TOLERANCE_MS }

    data class Result(val reading: ClockReading, val newInferredAnchor: InferredAnchor?)

    /**
     * Ordem de preferência:
     * 1. âncora da internet deste boot;
     * 2. âncora estimada deste boot (criada na primeira leitura do boot);
     * 3. cria a âncora estimada a partir do último desvio verificado (ou do relógio do aparelho).
     */
    fun read(
        network: TimeAnchor?,
        inferred: InferredAnchor?,
        lastVerifiedOffsetMs: Long?,
        now: ClockSnapshot,
    ): Result {
        usable(network, now)?.let { a ->
            val trueNow = TimeMath.trueAt(a, now.elapsedMs)
            val error = TimeMath.errorAt(a, now.elapsedMs)
            return Result(
                ClockReading(
                    trueNowUtc = trueNow,
                    deviceWallMs = now.wallMs,
                    elapsedMs = now.elapsedMs,
                    bootCount = now.bootCount,
                    deviceOffsetMs = now.wallMs - trueNow,
                    errorMs = error,
                    quality = TimeMath.quality(error, a.corroborated),
                    source = a.source,
                    server = a.server,
                    anchorAgeMs = now.elapsedMs - a.elapsedMs,
                    inferred = false,
                ),
                null,
            )
        }

        var created: InferredAnchor? = null
        val anchor = usable(inferred, now) ?: InferredAnchor(
            bootCount = now.bootCount,
            elapsedMs = now.elapsedMs,
            trueUtcMs = now.wallMs - (lastVerifiedOffsetMs ?: 0L),
            fromKnownOffset = lastVerifiedOffsetMs != null,
        ).also { created = it }

        val trueNow = anchor.trueUtcMs + (now.elapsedMs - anchor.elapsedMs)
        return Result(
            ClockReading(
                trueNowUtc = trueNow,
                deviceWallMs = now.wallMs,
                elapsedMs = now.elapsedMs,
                bootCount = now.bootCount,
                deviceOffsetMs = now.wallMs - trueNow,
                errorMs = null,
                quality = if (anchor.fromKnownOffset) TimeQuality.INFERIDA else TimeQuality.NAO_VERIFICADA,
                source = null,
                server = null,
                anchorAgeMs = null,
                inferred = true,
            ),
            created,
        )
    }
}
