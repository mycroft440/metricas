package com.metricas.tempotela

import android.app.Application
import android.content.Context
import com.metricas.tempotela.data.AppSettings
import com.metricas.tempotela.data.db.AppDatabase
import com.metricas.tempotela.pipeline.Ingestor
import com.metricas.tempotela.pipeline.Pipeline
import com.metricas.tempotela.pipeline.Recomputer
import com.metricas.tempotela.platform.AndroidEnvironment
import com.metricas.tempotela.platform.DeviceEnvironment
import com.metricas.tempotela.platform.EventSource
import com.metricas.tempotela.platform.UsageEventSource
import com.metricas.tempotela.time.ClockEpochTracker
import com.metricas.tempotela.time.NetworkTimeSources
import com.metricas.tempotela.time.TimeSources
import com.metricas.tempotela.time.TimeCorrector
import com.metricas.tempotela.time.TimeSyncer
import com.metricas.tempotela.time.TrustedClock
import com.metricas.tempotela.time.ZoneRecorder
import com.metricas.tempotela.work.Scheduler

class MetricasApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Scheduler.ensureScheduled(this)
    }
}

/**
 * Injeção de dependências manual. Ambiente, fonte de eventos, fontes de hora e banco podem ser
 * substituídos (testes com aparelho simulado).
 */
class AppGraph(
    context: Context,
    val env: DeviceEnvironment = AndroidEnvironment(context),
    val usage: EventSource = UsageEventSource(context),
    val timeSources: TimeSources = NetworkTimeSources(context),
    val db: AppDatabase = AppDatabase.get(context),
) {
    val appContext: Context = context.applicationContext
    val settings = AppSettings(appContext)
    val clock = TrustedClock(appContext, env)
    val epochs = ClockEpochTracker(appContext, db, env)
    val syncer = TimeSyncer(env, db, clock, epochs, timeSources)
    val corrector = TimeCorrector(db, clock, settings)
    val ingestor = Ingestor(env, db, settings, clock, epochs, usage)
    val recomputer = Recomputer(env, db, settings, clock)
    val zones = ZoneRecorder(db, clock)
    val pipeline = Pipeline(clock, syncer, corrector, ingestor, recomputer, zones)
}

val Context.graph: AppGraph
    get() = (applicationContext as MetricasApp).graph
