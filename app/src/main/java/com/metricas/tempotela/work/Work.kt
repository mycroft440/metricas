package com.metricas.tempotela.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.metricas.core.day.DayResolver
import com.metricas.tempotela.data.db.ClockEventEntity
import com.metricas.tempotela.graph
import com.metricas.tempotela.time.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.TimeUnit

private const val KEY_TRIGGER = "trigger"

/** Copia os eventos do sistema e recalcula os totais. */
class IngestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER) ?: "periodico"
        return try {
            applicationContext.graph.pipeline.refresh(trigger)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** Sincroniza a hora pela internet (exige rede). */
class TimeSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER) ?: "periodico"
        return try {
            when (applicationContext.graph.pipeline.syncTime(trigger)) {
                is SyncOutcome.Success -> Result.success()
                is SyncOutcome.Failure -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** Roda logo após a meia-noite real para fechar o dia anterior e se reagenda. */
class DailyCloseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            applicationContext.graph.pipeline.refresh("fechamento-diario")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // O próximo ciclo periódico recupera.
        }
        // REPLACE aqui cancelaria este próprio worker; APPEND encadeia o próximo após este.
        Scheduler.scheduleDailyClose(applicationContext, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

object Scheduler {
    private const val INGEST_PERIODIC = "ingest-periodic"
    private const val INGEST_NOW = "ingest-now"
    private const val SYNC_PERIODIC = "timesync-periodic"
    private const val SYNC_NOW = "timesync-now"
    private const val DAILY_CLOSE = "daily-close"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensureScheduled(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            INGEST_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<IngestWorker>(2, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_TRIGGER to "periodico"))
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            SYNC_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TimeSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(network)
                .setInputData(workDataOf(KEY_TRIGGER to "periodico"))
                .build(),
        )
        scheduleDailyClose(context, replace = false)
    }

    fun ingestNow(context: Context, trigger: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            INGEST_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IngestWorker>().setInputData(workDataOf(KEY_TRIGGER to trigger)).build(),
        )
    }

    fun syncNow(context: Context, trigger: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TimeSyncWorker>()
                .setConstraints(network)
                .setInputData(workDataOf(KEY_TRIGGER to trigger))
                .build(),
        )
    }

    /** Atraso calculado pela hora real até a próxima virada de dia (+5 min). */
    fun scheduleDailyClose(context: Context, replace: Boolean) =
        scheduleDailyClose(context, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)

    fun scheduleDailyClose(context: Context, policy: ExistingWorkPolicy) {
        val graph = context.graph
        val settings = graph.settings
        val zone: ZoneId = if (settings.followDeviceZone) ZoneId.systemDefault() else settings.referenceZone()
        val resolver = DayResolver({ zone }, settings.dayCutoffHour)
        val now = graph.clock.now()
        val nextDayStart = resolver.dayStartUtc(resolver.dayOf(now).plusDays(1), zone)
        val delay = (nextDayStart + 5 * 60_000L - now).coerceAtLeast(60_000L)
        WorkManager.getInstance(context).enqueueUniqueWork(
            DAILY_CLOSE,
            policy,
            OneTimeWorkRequestBuilder<DailyCloseWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Cria logo no início do boot a âncora estimada da hora, presa ao relógio monotônico:
        // mudanças de relógio feitas depois disso não afetam a hora do app, mesmo sem internet.
        context.graph.clock.read()
        Scheduler.ensureScheduled(context)
        Scheduler.syncNow(context, "boot")
        Scheduler.ingestNow(context, "boot")
        Scheduler.scheduleDailyClose(context, replace = true)
    }
}

/** Relógio ou fuso alterados: registra, ressincroniza a hora e reingere. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_TIME_CHANGED && action != Intent.ACTION_TIMEZONE_CHANGED) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = app.graph
                if (action == Intent.ACTION_TIMEZONE_CHANGED) {
                    graph.zones.record()
                    if (graph.settings.followDeviceZone) graph.settings.needsFullRecompute = true
                } else {
                    graph.db.clockEventDao().insert(
                        ClockEventEntity(
                            detectedAtUtc = graph.clock.now(),
                            kind = "RELOGIO_ALTERADO",
                            deltaMs = null,
                            oldZone = null,
                            newZone = null,
                        ),
                    )
                    graph.epochs.check("relogio-alterado") { graph.clock.now() }
                }
                Scheduler.syncNow(app, "relogio-alterado")
                Scheduler.ingestNow(app, "relogio-alterado")
                Scheduler.scheduleDailyClose(app, replace = true)
            } finally {
                pending.finish()
            }
        }
    }
}
