package com.metricas.tempotela.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface RawEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<RawEventEntity>): List<Long>

    @Query("SELECT * FROM raw_event WHERE tsUtc >= :fromUtc ORDER BY tsUtc, id")
    suspend fun eventsFrom(fromUtc: Long): List<RawEventEntity>

    @Query("SELECT * FROM raw_event WHERE tsUtc BETWEEN :fromUtc AND :toUtc ORDER BY tsUtc, id")
    suspend fun eventsBetween(fromUtc: Long, toUtc: Long): List<RawEventEntity>

    @Query("SELECT * FROM raw_event ORDER BY tsUtc DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<RawEventEntity>

    @Query("SELECT COUNT(*) FROM raw_event WHERE type IN (17, 18)")
    suspend fun keyguardEventCount(): Int

    @Query("SELECT MIN(tsUtc) FROM raw_event")
    suspend fun minTsUtc(): Long?

    @Query("SELECT COUNT(*) FROM raw_event")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM raw_event WHERE quality != 'VERIFICADA'")
    suspend fun unverifiedCount(): Int

    @Query(
        "UPDATE raw_event SET tsUtc = tsDevice - :offsetMs, offsetMs = :offsetMs, " +
            "offsetErrorMs = :errorMs, quality = :quality WHERE ingestRunId = :runId",
    )
    suspend fun reoffsetRun(runId: Long, offsetMs: Long, errorMs: Long, quality: String): Int

    @Query("SELECT MIN(tsUtc) FROM raw_event WHERE ingestRunId = :runId")
    suspend fun minTsForRun(runId: Long): Long?
}

@Dao
interface IngestRunDao {
    @Insert
    suspend fun insert(run: IngestRunEntity): Long

    @Update
    suspend fun update(run: IngestRunEntity)

    @Query("SELECT * FROM ingest_run WHERE ok = 1 ORDER BY observedStartUtc")
    suspend fun okRuns(): List<IngestRunEntity>

    @Query("SELECT * FROM ingest_run ORDER BY id DESC LIMIT 1")
    suspend fun last(): IngestRunEntity?

    @Query("SELECT * FROM ingest_run WHERE ok = 1 ORDER BY id DESC LIMIT 1")
    suspend fun lastOk(): IngestRunEntity?

    @Query("SELECT * FROM ingest_run WHERE ok = 1 AND bootCount = :bootCount AND quality != 'VERIFICADA'")
    suspend fun unverifiedRunsForBoot(bootCount: Int): List<IngestRunEntity>

    @Query("SELECT COUNT(*) FROM ingest_run WHERE ok = 1")
    suspend fun okCount(): Int
}

@Dao
interface TimeSyncDao {
    @Insert
    suspend fun insert(sync: TimeSyncEntity): Long

    @Query("SELECT * FROM time_sync WHERE chosen = 1 ORDER BY id DESC LIMIT 1")
    suspend fun latestChosen(): TimeSyncEntity?

    @Query("SELECT * FROM time_sync ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TimeSyncEntity>
}

@Dao
interface ClockEpochDao {
    @Insert
    suspend fun insert(epoch: ClockEpochEntity): Long

    @Query("SELECT COUNT(*) FROM clock_epoch")
    suspend fun count(): Int
}

@Dao
interface ClockEventDao {
    @Insert
    suspend fun insert(event: ClockEventEntity): Long

    @Query("SELECT * FROM clock_event ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ClockEventEntity>
}

@Dao
interface ZoneHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(zone: ZoneHistoryEntity)

    @Query("SELECT * FROM zone_history ORDER BY effectiveFromUtc")
    suspend fun all(): List<ZoneHistoryEntity>

    @Query("SELECT * FROM zone_history ORDER BY effectiveFromUtc DESC LIMIT 1")
    suspend fun latest(): ZoneHistoryEntity?
}

@Dao
interface PermissionLogDao {
    @Insert
    suspend fun insert(entry: PermissionLogEntity): Long

    @Query("SELECT * FROM permission_log ORDER BY id DESC LIMIT 1")
    suspend fun latest(): PermissionLogEntity?
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insertAll(sessions: List<SessionEntity>)

    @Query("DELETE FROM usage_session WHERE startUtc >= :fromUtc")
    suspend fun deleteFrom(fromUtc: Long): Int

    @Query("DELETE FROM usage_session")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM usage_session WHERE startUtc >= :fromUtc AND startUtc < :toUtc ORDER BY startUtc")
    suspend fun between(fromUtc: Long, toUtc: Long): List<SessionEntity>

    @Query("SELECT * FROM usage_session ORDER BY startUtc")
    suspend fun all(): List<SessionEntity>
}

@Dao
interface DailySummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(days: List<DailySummaryEntity>)

    @Query("DELETE FROM daily_summary WHERE date >= :fromDate")
    suspend fun deleteFrom(fromDate: String): Int

    @Query("DELETE FROM daily_summary")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM daily_summary ORDER BY date")
    suspend fun all(): List<DailySummaryEntity>
}

@Dao
interface HourlyBucketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(buckets: List<HourlyBucketEntity>)

    @Query("DELETE FROM hourly_bucket WHERE date >= :fromDate")
    suspend fun deleteFrom(fromDate: String): Int

    @Query("DELETE FROM hourly_bucket")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM hourly_bucket WHERE date >= :fromDate ORDER BY date, hour")
    suspend fun from(fromDate: String): List<HourlyBucketEntity>
}
