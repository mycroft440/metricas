package com.metricas.tempotela.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    exportSchema = false,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                    .build()
                    .also { instance = it }
            }
    }
}
