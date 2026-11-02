package com.metricas.tempotela.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.migration.Migration

@Database(
    entities = [
        RawEventEntity::class,
        IngestRunEntity::class,
        TimeSyncEntity::class,
        ClockEpochEntity::class,
        ClockEventEntity::class,
        ZoneHistoryEntity::class,
        PermissionLogEntity::class,
        SessionEntity::class,
        DailySummaryEntity::class,
        HourlyBucketEntity::class,
    ],
    version = 1,
    // O esquema de cada versão é exportado para app/schemas: toda mudança futura de estrutura
    // exige uma migração em MIGRATIONS (nunca recriar o banco: o histórico antigo só existe aqui).
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawEventDao(): RawEventDao
    abstract fun ingestRunDao(): IngestRunDao
    abstract fun timeSyncDao(): TimeSyncDao
    abstract fun clockEpochDao(): ClockEpochDao
    abstract fun clockEventDao(): ClockEventDao
    abstract fun zoneHistoryDao(): ZoneHistoryDao
    abstract fun permissionLogDao(): PermissionLogDao
    abstract fun sessionDao(): SessionDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun hourlyBucketDao(): HourlyBucketDao

    companion object {
        const val NAME = "metricas.db"

        @Volatile
        private var instance: AppDatabase? = null

        /** Migrações entre versões do esquema (ex.: Migration(1, 2) { ... }). */
        val MIGRATIONS: Array<Migration> = arrayOf()

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                    .addMigrations(*MIGRATIONS)
                    // Sem WAL: todo dado confirmado fica no arquivo principal, então o backup
                    // automático do Android (que copia o arquivo) nunca sai sem as últimas gravações.
                    .setJournalMode(JournalMode.TRUNCATE)
                    .build()
                    .also { instance = it }
            }
    }
}
