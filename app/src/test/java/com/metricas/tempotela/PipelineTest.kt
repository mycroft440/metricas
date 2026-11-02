package com.metricas.tempotela

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metricas.core.model.EventType
import com.metricas.core.model.TimeQuality
import com.metricas.core.session.SessionFlags
import com.metricas.tempotela.FakeDevice.Companion.DAY
import com.metricas.tempotela.FakeDevice.Companion.HOUR
import com.metricas.tempotela.FakeDevice.Companion.MIN
import com.metricas.tempotela.data.db.AppDatabase
import com.metricas.tempotela.data.db.DailySummaryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import kotlin.math.abs

/** Pipeline completo (Room real, SharedPreferences reais) com um aparelho simulado. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PipelineTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private lateinit var device: FakeDevice
    private lateinit var graph: AppGraph

    private fun start(iso: String) {
        device = FakeDevice(OffsetDateTime.parse(iso).toInstant().toEpochMilli())
        graph = AppGraph(context, env = device, usage = device, timeSources = device, db = db)
        graph.settings.referenceZoneId = "America/Sao_Paulo"
    }

    private fun refresh() = runBlocking {
        device.advance(1_000)
        graph.pipeline.refresh("teste")
    }

    private fun days(): Map<String, DailySummaryEntity> = runBlocking { db.dailySummaryDao().all().associateBy { it.date } }

    private fun count(type: Int): Int = runBlocking { db.rawEventDao().countsByTypeSince(Long.MIN_VALUE) }
        .firstOrNull { it.type == type }?.n ?: 0

    private fun assertNear(expectedMs: Long, actualMs: Long, toleranceMs: Long = 5_000) {
        assertTrue("esperado ~$expectedMs ms, obtido $actualMs ms", abs(expectedMs - actualMs) <= toleranceMs)
    }

    private fun assertNoInconsistency() {
        for (d in days().values) {
            assertFalse("dia ${d.date} inconsistente", SessionFlags.has(d.qualityFlags, SessionFlags.INCONSISTENT))
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun diaSimples() {
        start("2026-11-02T12:00:00-03:00")
        device.lock()
        device.advance(HOUR)
        device.use(30 * MIN)
        device.advance(HOUR)
        refresh()
        val d = days().getValue("2026-11-02")
        assertNear(30 * MIN, d.unlockedMs)
        assertEquals(1, d.unlocks)
        assertEquals(TimeQuality.VERIFICADA, graph.clock.read().quality)
        assertNoInconsistency()
    }

    @Test
    fun relogioDoAparelhoAtrasadoNaMeiaNoite() {
        start("2026-11-02T22:00:00-03:00")
        device.deviceOffset = -5 * MIN
        device.lock()
        refresh()
        device.advance(117 * MIN) // 23:58 (hora real)
        device.use(12 * MIN) // até 00:10 do dia 3
        device.advance(HOUR)
        refresh()
        val all = days()
        assertNear(2 * MIN, all.getValue("2026-11-02").unlockedMs)
        assertNear(10 * MIN, all.getValue("2026-11-03").unlockedMs)
        assertNoInconsistency()
    }

    @Test
    fun mudarRelogioSemInternetNaoDuplicaNemMudaODia() {
        start("2026-11-02T09:00:00-03:00")
        device.internet = false
        device.lock()
        refresh()
        device.use(10 * MIN)
        refresh()
        device.changeClock(DAY) // usuário adianta a data em 1 dia
        device.advance(MIN)
        device.use(20 * MIN)
        refresh()
        val all = days()
        assertNear(30 * MIN, all.getValue("2026-11-02").unlockedMs)
        assertTrue((all["2026-11-03"]?.unlockedMs ?: 0L) == 0L)
        assertEquals(2, count(EventType.SCREEN_INTERACTIVE))
        assertNoInconsistency()
    }

    @Test
    fun rebootComRelogioDeHardwareErradoNaoDuplicaEventos() {
        start("2026-11-02T09:00:00-03:00")
        device.lock()
        refresh()
        device.use(10 * MIN)
        device.advance(5 * MIN)
        refresh()
        device.advance(MIN)
        device.use(2 * MIN)
        device.advance(MIN)
        device.reboot(offMs = 10 * MIN, rtcErrorMs = 5_000)
        device.advance(10_000)
        device.changeClock(-5_000) // a rede corrige o relógio e o Android desloca o histórico
        device.advance(MIN)
        device.use(3 * MIN)
        device.advance(MIN)
        refresh()
        assertEquals(3, count(EventType.SCREEN_INTERACTIVE))
        assertEquals(1, count(EventType.DEVICE_STARTUP))
        assertNear(15 * MIN, days().getValue("2026-11-02").unlockedMs)
        assertNoInconsistency()
    }

    @Test
    fun lacunaDeRetencaoNaoViraUsoDeVariosDias() {
        start("2026-11-02T09:00:00-03:00")
        device.lock()
        refresh()
        device.unlock()
        device.advance(20 * MIN)
        refresh() // sessão aberta neste momento
        device.advance(10 * MIN)
        device.lock() // este bloqueio expira antes de ser lido
        device.advance(12 * DAY)
        device.use(5 * MIN)
        device.advance(MIN)
        refresh()
        val all = days()
        assertTrue(all.getValue("2026-11-02").unlockedMs <= 25 * MIN)
        assertEquals("GAP", all.getValue("2026-11-06").status)
        assertNear(5 * MIN, all.getValue("2026-11-14").unlockedMs)
        assertNoInconsistency()
    }

    @Test
    fun permissaoRevogadaNaoAvancaALeitura() {
        start("2026-11-02T09:00:00-03:00")
        device.usageAccess = false
        val result = refresh()
        assertFalse(result.ingest.ok)
        assertNull(graph.settings.watermarkUtc)
    }

    @Test
    fun ntpFalsificadoNaoDefineAHora() {
        start("2026-11-02T09:00:00-03:00")
        device.trustedTimeAvailable = false
        device.spoofedNtpMs = HOUR
        device.lock()
        refresh()
        val reading = graph.clock.read()
        assertNear(device.trueUtc, reading.trueNowUtc, 2_000)
        assertEquals(TimeQuality.VERIFICADA, reading.quality)
    }

    @Test
    fun recalculoSemLeituraNaoEstendeSessaoAberta() {
        start("2026-11-02T09:00:00-03:00")
        device.lock()
        refresh()
        device.unlock()
        device.advance(10 * MIN)
        refresh()
        device.lock()
        device.advance(2 * HOUR)
        val live = runBlocking { graph.recomputer.recompute(null) }!!
        assertTrue(live.todayUnlockedMs < 15 * MIN)
        assertNear(10 * MIN, days().getValue("2026-11-02").unlockedMs)
    }
}
