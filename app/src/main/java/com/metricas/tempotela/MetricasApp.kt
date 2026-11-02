package com.metricas.tempotela

import android.app.Application
import android.content.Context
import com.metricas.tempotela.data.AppSettings
import com.metricas.tempotela.data.db.AppDatabase
import com.metricas.tempotela.pipeline.Ingestor
import com.metricas.tempotela.pipeline.Pipeline
import com.metricas.tempotela.pipeline.Recomputer
import com.metricas.tempotela.platform.UsageEventSource
import com.metricas.tempotela.time.ClockEpochTracker
import com.metricas.tempotela.time.NetworkTimeSources
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

/** Injeção de dependências manual. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val db: AppDatabase = AppDatabase.get(appContext)
    val settings = AppSettings(appContext)
    val clock = TrustedClock(appContext)
    val epochs = ClockEpochTracker(appContext, db)
    val timeSources = NetworkTimeSources(appContext)
    val syncer = TimeSyncer(appContext, db, clock, epochs, timeSources)
    val corrector = TimeCorrector(db, clock)
    val usage = UsageEventSource(appContext)
    val ingestor = Ingestor(appContext, db, settings, clock, epochs, usage)
    val recomputer = Recomputer(appContext, db, settings, clock)
    val zones = ZoneRecorder(db, clock)
    val pipeline = Pipeline(clock, syncer, corrector, ingestor, recomputer, zones)
}

val Context.graph: AppGraph
    get() = (applicationContext as MetricasApp).graph
