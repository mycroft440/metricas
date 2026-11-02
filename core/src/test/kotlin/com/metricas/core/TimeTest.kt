package com.metricas.core

import com.metricas.core.model.TimeQuality
import com.metricas.core.time.HttpDateMath
import com.metricas.core.time.SntpMath
import com.metricas.core.time.TimeAnchor
import com.metricas.core.time.TimeMath
import com.metricas.core.time.TimeSample
import com.metricas.core.time.TimeSource
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeTest {
    private val t = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()

    @Test
    fun ancoraMonotonica() {
        val anchor = TimeAnchor(1, 1_000_000, t, 20, TimeSource.SNTP, "a.st1.ntp.br")
        assertEquals(t + 3_600_000, TimeMath.trueAt(anchor, 4_600_000))
        assertEquals(200L, TimeMath.errorAt(anchor, 4_600_000))
        assertEquals(TimeQuality.VERIFICADA, TimeMath.quality(2_000))
        assertEquals(TimeQuality.INFERIDA, TimeMath.quality(2_001))
    }

    @Test
    fun deteccaoDeAjusteDeRelogio() {
        val delta = TimeMath.clockJumpMs(1_000, t, 61_000, t + 60_000 + 3_600_000)
        assertEquals(3_600_000L, delta)
        assertTrue(TimeMath.isClockJump(delta, 60_000))
        // 3 s de deriva em 1 dia não é ajuste.
        assertFalse(TimeMath.isClockJump(3_000, 86_400_000))
    }

    @Test
    fun escolheFonteDeMenorErro() {
        val samples = listOf(
            TimeSample(TimeSource.SNTP, "ntp", 1_000, t, t + 300_000, 30),
            TimeSample(TimeSource.TRUSTED_TIME, "google", 2_000, t + 1_010, t + 301_000, 25),
            TimeSample(TimeSource.NETWORK_CLOCK, "android", 3_000, t + 2_000, t + 302_000, 1_000),
        )
        val choice = assertNotNull(TimeMath.choose(samples))
        assertEquals(TimeSource.TRUSTED_TIME, choice.best.source)
        assertEquals(10L, choice.disagreementMs)
        // relógio_do_aparelho − hora_real = (t + 301 000) − (t + 1 010)
        assertEquals(299_990L, choice.best.deviceOffsetMs)
    }

    @Test
    fun sntpCarimboIdaEVolta() {
        val buf = ByteArray(48)
        val ms = Instant.parse("2026-11-02T12:34:56.789Z").toEpochMilli()
        SntpMath.writeTimestampMs(buf, 40, ms)
        assertTrue(abs(SntpMath.readTimestampMs(buf, 40) - ms) <= 1)
        val after2036 = Instant.parse("2040-01-01T00:00:00Z").toEpochMilli()
        SntpMath.writeTimestampMs(buf, 32, after2036)
        assertTrue(abs(SntpMath.readTimestampMs(buf, 32) - after2036) <= 1)
    }

    @Test
    fun sntpRequisicaoEValidacao() {
        val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val req = SntpMath.buildRequest(nonce)
        assertEquals(0x23, req[0].toInt())
        assertTrue(req.copyOfRange(40, 48).contentEquals(nonce))

        val resp = ByteArray(48)
        resp[0] = 0x24 // LI=0, VN=4, modo servidor
        resp[1] = 1
        System.arraycopy(nonce, 0, resp, 24, 8)
        SntpMath.writeTimestampMs(resp, 32, t)
        SntpMath.writeTimestampMs(resp, 40, t + 2)
        val parsed = SntpMath.parse(resp)
        assertNull(SntpMath.validate(parsed, nonce))
        assertTrue(abs(parsed.transmitMs - (t + 2)) <= 1)
        assertNotNull(SntpMath.validate(parsed, byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)))

        resp[1] = 0
        assertNotNull(SntpMath.validate(SntpMath.parse(resp), nonce))
        resp[1] = 1
        resp[0] = 0xE4.toByte() // leap = 3
        assertNotNull(SntpMath.validate(SntpMath.parse(resp), nonce))
    }

    @Test
    fun sntpCalculoDeDesvio() {
        // Servidor 5 s à frente, 20 ms de rede em cada sentido, 2 ms de processamento.
        val m = SntpMath.measure(t0LocalMs = 1_000, t1ServerMs = 6_020, t2ServerMs = 6_022, t3LocalMs = 1_042)
        assertEquals(5_000L, m.offsetMs)
        assertEquals(40L, m.delayMs)
        assertEquals(20L, m.errorMs)
    }

    @Test
    fun cabecalhoHttpDate() {
        val d = assertNotNull(HttpDateMath.parseRfc1123("Mon, 02 Nov 2026 12:00:00 GMT"))
        assertEquals(t, d)
        val (trueAtT3, err) = HttpDateMath.estimate(d, 100, 300)
        assertEquals(t + 600, trueAtT3)
        assertEquals(600L, err)
        assertNull(HttpDateMath.parseRfc1123("data inválida"))
    }
}
