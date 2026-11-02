package com.metricas.core

import com.metricas.core.day.DayAggregate
import com.metricas.core.day.Invariants
import com.metricas.core.ingest.DeviceEvent
import com.metricas.core.ingest.EventDedupe
import com.metricas.core.ingest.Observation
import com.metricas.core.ingest.StoredEventKey
import com.metricas.core.model.EventType.DEVICE_SHUTDOWN
import com.metricas.core.model.EventType.DEVICE_STARTUP
import com.metricas.core.model.EventType.KEYGUARD_HIDDEN
import com.metricas.core.model.EventType.KEYGUARD_SHOWN
import com.metricas.core.model.EventType.SCREEN_INTERACTIVE
import com.metricas.core.model.EventType.SCREEN_NON_INTERACTIVE
import com.metricas.core.model.Interval
import com.metricas.core.model.TimeQuality
import com.metricas.core.model.UsageEvent
import com.metricas.core.session.EndReason
import com.metricas.core.session.Session
import com.metricas.core.session.Sessionizer
import com.metricas.core.session.StartReason
import com.metricas.core.time.ClockLogic
import com.metricas.core.time.ClockSnapshot
import com.metricas.core.time.HttpDateMath
import com.metricas.core.time.SntpMath
import com.metricas.core.time.TimeAnchor
import com.metricas.core.time.TimeMath
import com.metricas.core.time.TimeSample
import com.metricas.core.time.TimeSource
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardeningTest {
    private val t = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private var seq = 0L

    private fun ev(sec: Long, type: Int) = UsageEvent(t + sec * 1000, type, seq++)

    // ---------------- Hora: consenso entre fontes ----------------

    private fun sample(source: TimeSource, server: String, elapsed: Long, trueUtc: Long, error: Long) =
        TimeSample(source, server, elapsed, trueUtc, trueUtc + 60_000, error)

    @Test
    fun ntpFalsificadoNaoVenceFonteAutenticada() {
        val spoofed = sample(TimeSource.SNTP, "ntp-falso", 1_000, t + hour, 5)
        val https = sample(TimeSource.HTTPS_DATE, "www.google.com", 1_000, t, 600)
        val choice = assertNotNull(TimeMath.choose(listOf(spoofed, https)))
        assertEquals(TimeSource.HTTPS_DATE, choice.best.source)
        assertEquals(listOf(spoofed), choice.rejected)
        assertTrue(choice.corroborated)
    }

    @Test
    fun semFonteAutenticadaValeAMaioria() {
        val a = sample(TimeSource.SNTP, "a.st1.ntp.br", 1_000, t, 20)
        val b = sample(TimeSource.SNTP, "time.google.com", 1_000, t + 10, 30)
        val wrong = sample(TimeSource.NETWORK_CLOCK, "Android", 1_000, t + hour, 1_000)
        val choice = assertNotNull(TimeMath.choose(listOf(wrong, a, b)))
        assertEquals("a.st1.ntp.br", choice.best.server)
        assertEquals(listOf(wrong), choice.rejected)
        assertTrue(choice.corroborated)
    }

    @Test
    fun fonteUnicaNaoAutenticadaNaoEVerificada() {
        val only = sample(TimeSource.SNTP, "a.st1.ntp.br", 1_000, t, 20)
        val choice = assertNotNull(TimeMath.choose(listOf(only)))
        assertFalse(choice.corroborated)
        assertEquals(TimeQuality.INFERIDA, TimeMath.quality(choice.best.errorMs, choice.corroborated))
    }

    @Test
    fun respostaImplausivelEDescartada() {
        val year2000 = sample(TimeSource.SNTP, "quebrado", 1_000, 946_684_800_000, 10)
        assertNull(TimeMath.choose(listOf(year2000)))
    }

    // ---------------- Relógio estimado sem internet ----------------

    @Test
    fun ancoraDaInternetTemPrioridade() {
        val anchor = TimeAnchor(5, 10_000, t, 20, TimeSource.TRUSTED_TIME, "google")
        val r = ClockLogic.read(anchor, null, null, ClockSnapshot(5, 70_000, t + 60_000 + 300_000)).reading
        assertEquals(t + 60_000, r.trueNowUtc)
        assertEquals(300_000L, r.deviceOffsetMs)
        assertEquals(TimeQuality.VERIFICADA, r.quality)
        assertFalse(r.inferred)
    }

    @Test
    fun semInternetMudarORelogioNaoAlteraAHoraDoApp() {
        // Boot novo, sem internet, último desvio conhecido: aparelho 2 min adiantado.
        val first = ClockLogic.read(null, null, 120_000, ClockSnapshot(6, 5_000, t + 120_000))
        val inferred = assertNotNull(first.newInferredAnchor)
        assertEquals(t, first.reading.trueNowUtc)
        assertEquals(TimeQuality.INFERIDA, first.reading.quality)

        // 10 s depois o usuário adianta o relógio do aparelho em 1 dia.
        val later = ClockLogic.read(null, inferred, 120_000, ClockSnapshot(6, 15_000, t + 120_000 + 10_000 + 24 * hour))
        assertNull(later.newInferredAnchor)
        assertEquals(t + 10_000, later.reading.trueNowUtc)
        assertEquals(120_000L + 24 * hour, later.reading.deviceOffsetMs)
    }

    @Test
    fun ancoraDeOutroBootNaoEUsada() {
        val anchor = TimeAnchor(5, 10_000, t, 20, TimeSource.TRUSTED_TIME, "google")
        val r = ClockLogic.read(anchor, null, null, ClockSnapshot(6, 20_000, t))
        assertTrue(r.reading.inferred)
        assertEquals(TimeQuality.NAO_VERIFICADA, r.reading.quality)
    }

    // ---------------- NTP e HTTP ----------------

    @Test
    fun ntpRejeitaServidorComIncertezaAlta() {
        val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val resp = ByteArray(48)
        resp[0] = 0x24
        resp[1] = 2
        SntpMath.writeShortFormatMs(resp, 4, 40)
        SntpMath.writeShortFormatMs(resp, 8, 1_500)
        System.arraycopy(nonce, 0, resp, 24, 8)
        SntpMath.writeTimestampMs(resp, 32, t)
        SntpMath.writeTimestampMs(resp, 40, t + 1)
        val parsed = SntpMath.parse(resp)
        assertTrue(kotlin.math.abs(parsed.rootDispersionMs - 1_500) <= 1)
        assertNotNull(SntpMath.validate(parsed, nonce))

        SntpMath.writeShortFormatMs(resp, 8, 10)
        val ok = SntpMath.parse(resp)
        assertNull(SntpMath.validate(ok, nonce))
        val m = SntpMath.measure(0, t, t + 1, 30)
        assertTrue(SntpMath.totalErrorMs(m, ok) >= m.errorMs + 20)

        // Carimbo de envio anterior ao de recebimento.
        SntpMath.writeTimestampMs(resp, 40, t - 5_000)
        assertNotNull(SntpMath.validate(SntpMath.parse(resp), nonce))
    }

    @Test
    fun httpDateConsideraCacheIntermediario() {
        val (trueAtT3, err) = HttpDateMath.estimate(t, 100, 300, ageSeconds = 30)
        assertEquals(t + 30_000 + 600, trueAtT3)
        assertEquals(1_600L, err)
    }

    // ---------------- Sessões: bloqueio por trecho ----------------

    @Test
    fun trechoSemEventosDeBloqueioEDetectadoAutomaticamente() {
        val events = listOf(
            ev(0, SCREEN_INTERACTIVE), ev(60, SCREEN_NON_INTERACTIVE),
            ev(120, SCREEN_INTERACTIVE), ev(180, SCREEN_NON_INTERACTIVE),
            ev(240, SCREEN_INTERACTIVE), ev(300, SCREEN_NON_INTERACTIVE),
        )
        val r = Sessionizer().run(events)
        assertEquals(3, r.sessions.size)
        assertTrue(r.lastSegmentNoKeyguard)
    }

    @Test
    fun trocaDeTipoDeBloqueioEntreBootsNaoZeraDias() {
        val events = listOf(
            // Boot 1: sem bloqueio (sem eventos de keyguard).
            ev(0, SCREEN_INTERACTIVE), ev(600, SCREEN_NON_INTERACTIVE),
            ev(700, SCREEN_INTERACTIVE), ev(800, SCREEN_NON_INTERACTIVE),
            ev(900, SCREEN_INTERACTIVE), ev(1000, SCREEN_NON_INTERACTIVE),
            ev(1100, DEVICE_SHUTDOWN),
            // Boot 2: com PIN.
            ev(1200, DEVICE_STARTUP), ev(1201, KEYGUARD_SHOWN),
            ev(1300, SCREEN_INTERACTIVE), ev(1310, KEYGUARD_HIDDEN), ev(1400, SCREEN_NON_INTERACTIVE),
            ev(1400, KEYGUARD_SHOWN),
        )
        val r = Sessionizer().run(events)
        assertEquals(listOf(600_000L, 100_000L, 100_000L, 90_000L), r.sessions.map { it.durationMs })
        assertFalse(r.lastSegmentNoKeyguard)
    }

    // ---------------- Deduplicação ----------------

    @Test
    fun correcaoPequenaDeNtpNaoDuplicaEventos() {
        // O sistema deslocou o histórico em +2,5 s (correção de NTP) sem abrir nova época.
        val existing = listOf(StoredEventKey(tsDevice = 100_000, tsUtc = 100_000, type = SCREEN_INTERACTIVE, epochId = 3))
        val fresh = EventDedupe.filterNew(
            listOf(DeviceEvent(102_500, SCREEN_INTERACTIVE)),
            offsetMs = 2_500,
            currentEpoch = 3,
            existing = existing,
        )
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun maiorLacunaEntreLeituras() {
        val gaps = Observation.largestGapMs(listOf(Interval(0, 10), Interval(5, 20), Interval(50, 60), Interval(200, 210)))
        assertEquals(140L, gaps)
    }

    // ---------------- Consistência ----------------

    @Test
    fun checagensDeConsistencia() {
        val day = DayAggregate(LocalDate.of(2026, 11, 2), "UTC", 0, 24 * hour)
        day.unlockedMs = 3_600_000
        day.screenOnMs = 4_000_000
        day.unlocks = 1
        day.hourlyMs[10] = 3_600_000
        day.hourlyUnlocks[10] = 1
        assertTrue(Invariants.check(day).isEmpty())

        day.screenOnMs = 1_000
        assertTrue(Invariants.check(day).isNotEmpty())

        val s1 = Session(0, 100, StartReason.UNLOCK, EndReason.SCREEN_OFF, 0, TimeQuality.VERIFICADA)
        val s2 = Session(50, 150, StartReason.UNLOCK, EndReason.SCREEN_OFF, 0, TimeQuality.VERIFICADA)
        assertTrue(Invariants.sessionProblems(listOf(s1, s2)).isNotEmpty())
        assertTrue(Invariants.sessionProblems(listOf(s1)).isEmpty())
    }
}
