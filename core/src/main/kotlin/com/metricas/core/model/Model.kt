package com.metricas.core.model

/**
 * Tipos de evento do `UsageEvents.Event` do Android usados no cálculo.
 * Os valores são os mesmos das constantes públicas da plataforma.
 */
object EventType {
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val DEVICE_SHUTDOWN = 26
    const val DEVICE_STARTUP = 27

    val TRACKED: IntArray = intArrayOf(
        SCREEN_INTERACTIVE,
        SCREEN_NON_INTERACTIVE,
        KEYGUARD_SHOWN,
        KEYGUARD_HIDDEN,
        DEVICE_SHUTDOWN,
        DEVICE_STARTUP,
    )

    fun isTracked(type: Int): Boolean = TRACKED.contains(type)

    fun label(type: Int): String = when (type) {
        SCREEN_INTERACTIVE -> "Tela ligada"
        SCREEN_NON_INTERACTIVE -> "Tela desligada"
        KEYGUARD_SHOWN -> "Bloqueado"
        KEYGUARD_HIDDEN -> "Desbloqueado"
        DEVICE_SHUTDOWN -> "Desligamento"
        DEVICE_STARTUP -> "Inicialização"
        else -> "Evento $type"
    }
}

/** Qualidade da hora real atribuída a um evento (Seção 3.5 do plano). */
enum class TimeQuality {
    VERIFICADA,
    INFERIDA,
    NAO_VERIFICADA;

    companion object {
        fun worst(a: TimeQuality, b: TimeQuality): TimeQuality = if (a.ordinal >= b.ordinal) a else b

        fun parse(value: String?): TimeQuality = entries.firstOrNull { it.name == value } ?: NAO_VERIFICADA
    }
}

/** Evento já convertido para a hora real (UTC obtida pela internet). */
data class UsageEvent(
    val tsUtc: Long,
    val type: Int,
    val seq: Long = 0,
    val quality: TimeQuality = TimeQuality.VERIFICADA,
)

data class Interval(val startUtc: Long, val endUtc: Long) {
    val durationMs: Long get() = endUtc - startUtc
}
