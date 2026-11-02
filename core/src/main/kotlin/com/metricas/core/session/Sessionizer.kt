package com.metricas.core.session

import com.metricas.core.model.EventType
import com.metricas.core.model.Interval
import com.metricas.core.model.TimeQuality
import com.metricas.core.model.UsageEvent

enum class StartReason(val isUnlock: Boolean) {
    /** Passou pela tela de bloqueio. */
    UNLOCK(true),

    /** Tela religada antes do bloqueio entrar (atraso de bloqueio): continua o uso, não é desbloqueio. */
    SCREEN_ON_NO_KEYGUARD(false),

    /** Aparelho sem tela de bloqueio: cada vez que a tela liga conta como desbloqueio. */
    NO_LOCK_SCREEN(true),
}

enum class EndReason { SCREEN_OFF, KEYGUARD_SHOWN, SHUTDOWN }

object SessionFlags {
    const val LONG = 1
    const val ESTIMATED_END = 2
    const val OPEN = 4

    /** Marca de dia: os totais violaram uma checagem de consistência (ver Invariants). */
    const val INCONSISTENT = 8

    fun has(flags: Int, flag: Int): Boolean = (flags and flag) != 0
}

/** Intervalo contínuo de tela interativa + desbloqueada, em hora real (UTC). */
data class Session(
    val startUtc: Long,
    val endUtc: Long,
    val startReason: StartReason,
    val endReason: EndReason?,
    val flags: Int,
    val quality: TimeQuality,
) {
    val durationMs: Long get() = endUtc - startUtc
    val isOpen: Boolean get() = SessionFlags.has(flags, SessionFlags.OPEN)
}

data class SessionizerResult(
    val sessions: List<Session>,
    val screenOn: List<Interval>,
    val openSession: Session?,
    val openScreenOnStartUtc: Long?,
    /** O trecho mais recente (desde o último boot) foi tratado como aparelho sem tela de bloqueio. */
    val lastSegmentNoKeyguard: Boolean = false,
)

/**
 * Máquina de estados que transforma os eventos do sistema em sessões de uso desbloqueado.
 *
 * desbloqueado = (tela interativa) E (keyguard oculto)
 */
class Sessionizer(private val config: Config = Config()) {

    data class Config(
        /**
         * Indício de que o aparelho não tem tela de bloqueio (ex.: `isDeviceSecure == false`).
         * A decisão final é feita por trecho entre boots: sem nenhum evento de keyguard no
         * trecho e com o indício (ou com várias ligações de tela), o trecho é tratado como
         * "sem bloqueio". Assim, trocar o tipo de bloqueio no meio do histórico não zera dias.
         */
        val noKeyguardHint: Boolean = false,
        val longSessionMs: Long = 3L * 60 * 60 * 1000,
        /** Ligações de tela sem nenhum evento de keyguard para concluir "sem bloqueio". */
        val minScreenOnsForNoKeyguard: Int = 3,
    )

    /** Decide, para cada trecho entre DEVICE_STARTUPs, se o aparelho estava sem bloqueio. */
    private fun segmentNoKeyguard(sorted: List<UsageEvent>): List<Boolean> {
        val result = ArrayList<Boolean>()
        var keyguardEvents = 0
        var screenOns = 0
        fun close() {
            result += keyguardEvents == 0 &&
                (config.noKeyguardHint || screenOns >= config.minScreenOnsForNoKeyguard)
        }
        for (e in sorted) {
            when (e.type) {
                EventType.DEVICE_STARTUP -> {
                    close()
                    keyguardEvents = 0
                    screenOns = 0
                }
                EventType.KEYGUARD_SHOWN, EventType.KEYGUARD_HIDDEN -> keyguardEvents++
                EventType.SCREEN_INTERACTIVE -> screenOns++
            }
        }
        close()
        return result
    }

    /**
     * @param gaps períodos sem dados (histórico expirado antes de ser copiado). No início de
     * cada lacuna a sessão aberta é encerrada (fim estimado) e o estado volta a "desconhecido",
     * para que um desbloqueio antes da lacuna não seja contado como uso durante dias.
     */
    fun run(events: List<UsageEvent>, nowUtc: Long? = null, gaps: List<Interval> = emptyList()): SessionizerResult {
        val sorted = mergeGaps(events.sortedWith(compareBy<UsageEvent>({ it.tsUtc }, { it.seq })), gaps)
        val sessions = ArrayList<Session>()
        val screenOn = ArrayList<Interval>()
        val noKeyguardBySegment = segmentNoKeyguard(sorted)
        var segment = 0
        var noKeyguard = noKeyguardBySegment[0]

        var interactive: Boolean? = null
        var keyguardShown: Boolean? = if (noKeyguard) false else null
        var openStart: Long? = null
        var openReason = StartReason.UNLOCK
        var openQuality = TimeQuality.VERIFICADA
        var screenOnStart: Long? = null

        fun unlocked(): Boolean = interactive == true && keyguardShown == false

        fun closeSession(ts: Long, reason: EndReason, quality: TimeQuality, extraFlags: Int) {
            val start = openStart ?: return
            openStart = null
            if (ts <= start) return
            var flags = extraFlags
            if (ts - start > config.longSessionMs) flags = flags or SessionFlags.LONG
            sessions += Session(start, ts, openReason, reason, flags, TimeQuality.worst(openQuality, quality))
        }

        fun closeScreenOn(ts: Long) {
            val start = screenOnStart ?: return
            screenOnStart = null
            if (ts > start) screenOn += Interval(start, ts)
        }

        for (e in sorted) {
            when (e.type) {
                GAP_START -> {
                    closeSession(e.tsUtc, EndReason.SHUTDOWN, e.quality, SessionFlags.ESTIMATED_END)
                    closeScreenOn(e.tsUtc)
                    interactive = null
                    keyguardShown = if (noKeyguard) false else null
                    continue
                }
                EventType.DEVICE_SHUTDOWN -> {
                    // O timestamp é a última gravação do banco do sistema: fim estimado.
                    closeSession(e.tsUtc, EndReason.SHUTDOWN, e.quality, SessionFlags.ESTIMATED_END)
                    closeScreenOn(e.tsUtc)
                    interactive = null
                    keyguardShown = if (noKeyguard) false else null
                    continue
                }
                EventType.DEVICE_STARTUP -> {
                    // Algo aberto sem DEVICE_SHUTDOWN tem fim desconhecido: é descartado.
                    segment++
                    noKeyguard = noKeyguardBySegment.getOrElse(segment) { noKeyguard }
                    openStart = null
                    screenOnStart = null
                    interactive = null
                    keyguardShown = !noKeyguard
                    continue
                }
            }

            val before = unlocked()
            when (e.type) {
                EventType.SCREEN_INTERACTIVE -> {
                    interactive = true
                    if (screenOnStart == null) screenOnStart = e.tsUtc
                }
                EventType.SCREEN_NON_INTERACTIVE -> {
                    interactive = false
                    closeScreenOn(e.tsUtc)
                }
                EventType.KEYGUARD_SHOWN -> if (!noKeyguard) keyguardShown = true
                EventType.KEYGUARD_HIDDEN -> {
                    keyguardShown = false
                    // Só é possível desbloquear com a tela ligada.
                    if (interactive == null) {
                        interactive = true
                        if (screenOnStart == null) screenOnStart = e.tsUtc
                    }
                }
                else -> continue
            }
            val after = unlocked()

            if (!before && after) {
                openStart = e.tsUtc
                openReason = when {
                    e.type == EventType.KEYGUARD_HIDDEN -> StartReason.UNLOCK
                    noKeyguard -> StartReason.NO_LOCK_SCREEN
                    else -> StartReason.SCREEN_ON_NO_KEYGUARD
                }
                openQuality = e.quality
            } else if (before && !after) {
                val reason = if (e.type == EventType.SCREEN_NON_INTERACTIVE) EndReason.SCREEN_OFF else EndReason.KEYGUARD_SHOWN
                closeSession(e.tsUtc, reason, e.quality, 0)
            }
        }

        val open = openStart?.let { start ->
            val end = maxOf(start, nowUtc ?: start)
            var flags = SessionFlags.OPEN
            if (end - start > config.longSessionMs) flags = flags or SessionFlags.LONG
            Session(start, end, openReason, null, flags, openQuality)
        }
        return SessionizerResult(sessions, screenOn, open, screenOnStart, noKeyguard)
    }

    /** Insere marcadores de início de lacuna na sequência ordenada (depois dos eventos do mesmo instante). */
    private fun mergeGaps(sorted: List<UsageEvent>, gaps: List<Interval>): List<UsageEvent> {
        if (gaps.isEmpty()) return sorted
        val markers = gaps.filter { it.endUtc > it.startUtc }.map { UsageEvent(it.startUtc, GAP_START, Long.MAX_VALUE) }
        return (sorted + markers).sortedWith(compareBy<UsageEvent>({ it.tsUtc }, { it.seq }))
    }

    companion object {
        /** Tipo interno (não existe no Android) para marcar o início de uma lacuna de dados. */
        const val GAP_START = -1
    }
}
