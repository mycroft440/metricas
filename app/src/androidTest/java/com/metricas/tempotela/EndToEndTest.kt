package com.metricas.tempotela

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.metricas.core.model.EventType
import com.metricas.tempotela.platform.UsageAccess
import com.metricas.tempotela.platform.UsageEventSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ponta a ponta num Android real (emulador no CI): liga/desliga a tela e desbloqueia de verdade,
 * e confere que o sistema registrou os eventos e que o app mediu a sessão.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun sleepScreen() {
        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(3_000)
    }

    private fun wakeAndUnlock() {
        shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(1_500)
        shell("wm dismiss-keyguard")
        Thread.sleep(1_500)
    }

    @Test
    fun registraEventosReaisEMedeASessao() {
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        Thread.sleep(500)
        assertTrue("acesso ao uso não concedido", UsageAccess.has(context))

        wakeAndUnlock()
        val start = System.currentTimeMillis()
        sleepScreen()
        wakeAndUnlock()
        val unlockedAt = System.currentTimeMillis()
        Thread.sleep(6_000)
        val lockedAt = System.currentTimeMillis()
        sleepScreen()
        wakeAndUnlock()

        val events = UsageEventSource(context).query(start - 2_000, System.currentTimeMillis() + 1_000)
        val types = events.map { it.type }.toSet()
        assertTrue("sem SCREEN_NON_INTERACTIVE: $types", EventType.SCREEN_NON_INTERACTIVE in types)
        assertTrue("sem SCREEN_INTERACTIVE: $types", EventType.SCREEN_INTERACTIVE in types)

        val graph = context.graph
        val result = runBlocking { graph.pipeline.refresh("teste-e2e") }
        assertTrue("ingestão falhou: ${result.ingest.message}", result.ingest.ok)

        val sessions = runBlocking { graph.db.sessionDao().all() }
        val offset = graph.clock.read().deviceOffsetMs
        val expectedMs = lockedAt - unlockedAt
        val match = sessions.any { s ->
            val startDevice = s.startUtc + offset
            startDevice >= start - 2_000 && kotlin.math.abs(s.durationMs - expectedMs) <= 4_000
        }
        assertTrue(
            "nenhuma sessão de ~${expectedMs / 1000} s encontrada: " +
                sessions.takeLast(5).joinToString { "${it.durationMs / 1000}s/${it.startReason}" },
            match,
        )
        assertTrue(runBlocking { graph.db.dailySummaryDao().all() }.isNotEmpty())
    }
}
