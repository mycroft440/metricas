package com.metricas.core.day

import com.metricas.core.session.Session

/**
 * Checagens de consistência dos totais calculados. Se alguma falha, o dia é marcado
 * (SessionFlags.INCONSISTENT) e aparece na tela de Qualidade, em vez de mostrar um número
 * errado silenciosamente.
 */
object Invariants {
    const val TOLERANCE_MS = 1_000L

    fun check(day: DayAggregate, toleranceMs: Long = TOLERANCE_MS): List<String> {
        val problems = ArrayList<String>()
        if (day.unlockedMs < 0) problems += "tempo desbloqueado negativo"
        if (day.screenOnMs < 0) problems += "tempo de tela negativo"
        if (day.unlockedMs > day.dayLengthMs + toleranceMs) problems += "desbloqueado maior que o dia"
        if (day.screenOnMs > day.dayLengthMs + toleranceMs) problems += "tela ligada maior que o dia"
        if (day.unlockedMs > day.screenOnMs + toleranceMs) problems += "desbloqueado maior que tela ligada"
        val hourlySum = day.hourlyMs.sum()
        if (kotlin.math.abs(hourlySum - day.unlockedMs) > toleranceMs) problems += "soma por hora difere do total"
        if (day.hourlyUnlocks.sum() != day.unlocks) problems += "desbloqueios por hora diferem do total"
        return problems
    }

    /** Sessões nunca podem se sobrepor nem ter duração negativa. */
    fun sessionProblems(sessions: List<Session>): List<String> {
        val problems = ArrayList<String>()
        val sorted = sessions.sortedBy { it.startUtc }
        for (i in sorted.indices) {
            val s = sorted[i]
            if (s.endUtc < s.startUtc) problems += "sessão com duração negativa"
            if (i > 0 && s.startUtc < sorted[i - 1].endUtc) problems += "sessões sobrepostas"
        }
        return problems.distinct()
    }
}
