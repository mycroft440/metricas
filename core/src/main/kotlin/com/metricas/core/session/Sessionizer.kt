package com.metricas.core.session

import com.metricas.core.model.EventType
import com.metricas.core.model.Interval
import com.metricas.core.model.TimeQuality
import com.metricas.core.model.UsageEvent

enum class StartReason { UNLOCK, SCREEN_ON_NO_KEYGUARD }

enum class EndReason { SCREEN_OFF, KEYGUARD_SHOWN, SHUTDOWN }

object SessionFlags {
    const val LONG = 1
    const val ESTIMATED_END = 2
    const val OPEN = 4

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
)

/**
 * Máquina de estados que transforma os eventos do sistema em sessões de uso desbloqueado.
 *
 * desbloqueado = (tela interativa) E (keyguard oculto)
 */
class Sessionizer(private val config: Config = Config()) {

    data class Config(
        /** Aparelho sem tela de bloqueio: o sistema nunca emite eventos de keyguard. */
        val noKeyguard: Boolean = false,
        val longSessionMs: Long = 3L * 60 * 60 * 1000,
    )

    fun run(events: List<UsageEvent>, nowUtc: Long? = null): SessionizerResult {
        val sorted = events.sortedWith(compareBy<UsageEvent>({ it.tsUtc }, { it.seq }))
        val sessions = ArrayList<Session>()
        val screenOn = ArrayList<Interval>()

        var interactive: Boolean? = null
        var keyguardShown: Boolean? = if (config.noKeyguard) false else null
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
                EventType.DEVICE_SHUTDOWN -> {
                    // O timestamp é a última gravação do banco do sistema: fim estimado.
                    closeSession(e.tsUtc, EndReason.SHUTDOWN, e.quality, SessionFlags.ESTIMATED_END)
                    closeScreenOn(e.tsUtc)
                    interactive = null
                    keyguardShown = if (config.noKeyguard) false else null
                    continue
                }
                EventType.DEVICE_STARTUP -> {
                    // Algo aberto sem DEVICE_SHUTDOWN tem fim desconhecido: é descartado.
                    openStart = null
                    screenOnStart = null
                    interactive = null
                    keyguardShown = !config.noKeyguard
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
                EventType.KEYGUARD_SHOWN -> if (!config.noKeyguard) keyguardShown = true
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
                openReason = if (e.type == EventType.KEYGUARD_HIDDEN) StartReason.UNLOCK else StartReason.SCREEN_ON_NO_KEYGUARD
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
        return SessionizerResult(sessions, screenOn, open, screenOnStart)
    }
}
