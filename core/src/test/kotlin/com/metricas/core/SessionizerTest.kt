package com.metricas.core

import com.metricas.core.model.EventType.DEVICE_SHUTDOWN
import com.metricas.core.model.EventType.DEVICE_STARTUP
import com.metricas.core.model.EventType.KEYGUARD_HIDDEN
import com.metricas.core.model.EventType.KEYGUARD_SHOWN
import com.metricas.core.model.EventType.SCREEN_INTERACTIVE
import com.metricas.core.model.EventType.SCREEN_NON_INTERACTIVE
import com.metricas.core.model.UsageEvent
import com.metricas.core.session.EndReason
import com.metricas.core.session.SessionFlags
import com.metricas.core.session.Sessionizer
import com.metricas.core.session.StartReason
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionizerTest {
    private val t0 = Instant.parse("2026-11-02T10:00:00Z").toEpochMilli()
    private var seq = 0L

    /** [sec] segundos após t0. */
    private fun ev(sec: Long, type: Int) = UsageEvent(t0 + sec * 1000, type, seq++)

    private fun at(sec: Long) = t0 + sec * 1000

    private val locked = listOf(ev(0, SCREEN_NON_INTERACTIVE), ev(0, KEYGUARD_SHOWN))

    @Test
    fun desbloqueioSimples() {
        val r = Sessionizer().run(
            locked + listOf(
                ev(600, SCREEN_INTERACTIVE),
                ev(630, KEYGUARD_HIDDEN),
                ev(2400, SCREEN_NON_INTERACTIVE),
                ev(2400, KEYGUARD_SHOWN),
            ),
        )
        assertEquals(1, r.sessions.size)
        val s = r.sessions[0]
        assertEquals(at(630), s.startUtc)
        assertEquals(at(2400), s.endUtc)
        assertEquals(StartReason.UNLOCK, s.startReason)
        assertEquals(EndReason.SCREEN_OFF, s.endReason)
        assertEquals(1, r.screenOn.size)
        assertEquals(1800_000L, r.screenOn[0].durationMs)
    }

    @Test
    fun telaAcesaPorNotificacaoSemDesbloquearNaoConta() {
        val r = Sessionizer().run(
            locked + listOf(ev(300, SCREEN_INTERACTIVE), ev(312, SCREEN_NON_INTERACTIVE)),
        )
        assertTrue(r.sessions.isEmpty())
        assertEquals(12_000L, r.screenOn.single().durationMs)
    }

    @Test
    fun bloqueioComAtrasoReacendeSemDesbloquear() {
        val r = Sessionizer().run(
            locked + listOf(
                ev(600, SCREEN_INTERACTIVE),
                ev(600, KEYGUARD_HIDDEN),
                ev(1800, SCREEN_NON_INTERACTIVE),
                ev(1815, SCREEN_INTERACTIVE), // ainda dentro do atraso de bloqueio
                ev(2400, SCREEN_NON_INTERACTIVE),
                ev(2430, KEYGUARD_SHOWN),
            ),
        )
        assertEquals(2, r.sessions.size)
        assertEquals(1200_000L, r.sessions[0].durationMs)
        assertEquals(585_000L, r.sessions[1].durationMs)
        assertEquals(StartReason.SCREEN_ON_NO_KEYGUARD, r.sessions[1].startReason)
    }

    @Test
    fun aparelhoSemBloqueioDeTela() {
        val r = Sessionizer(Sessionizer.Config(noKeyguardHint = true)).run(
            listOf(
                ev(0, SCREEN_INTERACTIVE),
                ev(900, SCREEN_NON_INTERACTIVE),
                ev(1200, SCREEN_INTERACTIVE),
                ev(1500, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(listOf(900_000L, 300_000L), r.sessions.map { it.durationMs })
        assertTrue(r.sessions.all { it.startReason == StartReason.SCREEN_ON_NO_KEYGUARD })
    }

    @Test
    fun desligamentoFechaSessaoComFimEstimado() {
        val r = Sessionizer().run(
            locked + listOf(
                ev(600, SCREEN_INTERACTIVE),
                ev(600, KEYGUARD_HIDDEN),
                ev(3000, DEVICE_SHUTDOWN),
            ),
        )
        val s = r.sessions.single()
        assertEquals(EndReason.SHUTDOWN, s.endReason)
        assertTrue(SessionFlags.has(s.flags, SessionFlags.ESTIMATED_END))
        assertEquals(2400_000L, s.durationMs)
    }

    @Test
    fun aposBootDesbloqueioInfereTelaLigada() {
        // SCREEN_INTERACTIVE do boot pode vir com carimbo anterior ao DEVICE_STARTUP.
        val r = Sessionizer().run(
            listOf(
                ev(3594, SCREEN_INTERACTIVE),
                ev(3600, DEVICE_STARTUP),
                ev(3660, KEYGUARD_HIDDEN),
                ev(4200, SCREEN_NON_INTERACTIVE),
            ),
        )
        val s = r.sessions.single()
        assertEquals(at(3660), s.startUtc)
        assertEquals(at(4200), s.endUtc)
    }

    @Test
    fun sessaoAbertaSemDesligamentoEDescartadaNoBoot() {
        val r = Sessionizer().run(
            locked + listOf(
                ev(600, SCREEN_INTERACTIVE),
                ev(600, KEYGUARD_HIDDEN),
                ev(1200, DEVICE_STARTUP),
            ),
        )
        assertTrue(r.sessions.isEmpty())
        assertNull(r.openSession)
    }

    @Test
    fun eventosRepetidosSaoIdempotentes() {
        val r = Sessionizer().run(
            locked + listOf(
                ev(600, SCREEN_INTERACTIVE),
                ev(606, SCREEN_INTERACTIVE),
                ev(660, KEYGUARD_HIDDEN),
                ev(672, KEYGUARD_HIDDEN),
                ev(1200, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(540_000L, r.sessions.single().durationMs)
    }

    @Test
    fun sessaoEmAndamentoUsaAgora() {
        val r = Sessionizer().run(
            locked + listOf(ev(600, SCREEN_INTERACTIVE), ev(600, KEYGUARD_HIDDEN)),
            nowUtc = at(1500),
        )
        assertTrue(r.sessions.isEmpty())
        val open = assertNotNull(r.openSession)
        assertEquals(900_000L, open.durationMs)
        assertTrue(open.isOpen)
        assertEquals(at(600), r.openScreenOnStartUtc)
    }

    @Test
    fun sessaoLongaRecebeMarca() {
        val r = Sessionizer().run(
            locked + listOf(
                ev(60, SCREEN_INTERACTIVE),
                ev(60, KEYGUARD_HIDDEN),
                ev(60 + 4 * 3600, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertTrue(SessionFlags.has(r.sessions.single().flags, SessionFlags.LONG))
    }

    @Test
    fun cameraPelaTelaDeBloqueioNaoConta() {
        val r = Sessionizer().run(
            locked + listOf(ev(100, SCREEN_INTERACTIVE), ev(400, SCREEN_NON_INTERACTIVE)),
        )
        assertTrue(r.sessions.isEmpty())
    }
}
