package com.metricas.tempotela.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Evento bruto do sistema, com o carimbo original e a hora real (internet). */
@Entity(
    tableName = "raw_event",
    indices = [
        Index(value = ["epochId", "tsDevice", "type"], unique = true),
        Index(value = ["tsUtc"]),
        Index(value = ["ingestRunId"]),
    ],
)
data class RawEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsDevice: Long,
    val tsUtc: Long,
    val offsetMs: Long,
    val offsetErrorMs: Long,
    val quality: String,
    val epochId: Long,
    val type: Int,
    val ingestRunId: Long,
)

@Entity(tableName = "ingest_run")
data class IngestRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerName: String,
    val startedAtUtc: Long,
    val elapsedMs: Long,
    val bootCount: Int,
    val epochId: Long,
    val deviceWallMs: Long,
    val offsetMs: Long,
    val offsetErrorMs: Long,
    val quality: String,
    val queryBeginDevice: Long,
    val queryEndDevice: Long,
    val observedStartUtc: Long,
    val observedEndUtc: Long,
    val eventsRead: Int,
    val eventsNew: Int,
    val ok: Boolean,
    val error: String?,
)

@Entity(tableName = "time_sync", indices = [Index(value = ["chosen"])])
data class TimeSyncEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerName: String,
    val elapsedMs: Long,
    val bootCount: Int,
    val epochId: Long,
    val deviceWallMs: Long,
    val trueUtcMs: Long,
    val deviceOffsetMs: Long,
    val errorMs: Long,
    val source: String,
    val server: String,
    val rttMs: Long?,
    val disagreementMs: Long?,
    val chosen: Boolean,
    val ok: Boolean,
    val error: String?,
)

@Entity(tableName = "clock_epoch")
data class ClockEpochEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bootCount: Int,
    val startElapsedMs: Long,
    val startDeviceWallMs: Long,
    val reason: String,
    val jumpDeltaMs: Long,
    val triggerName: String,
)

@Entity(tableName = "clock_event")
data class ClockEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val detectedAtUtc: Long,
    val kind: String,
    val deltaMs: Long?,
    val oldZone: String?,
    val newZone: String?,
)

@Entity(tableName = "zone_history")
data class ZoneHistoryEntity(
    @PrimaryKey val effectiveFromUtc: Long,
    val zoneId: String,
)

@Entity(tableName = "permission_log")
data class PermissionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsUtc: Long,
    val granted: Boolean,
)

@Entity(tableName = "usage_session", indices = [Index(value = ["startUtc"])])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startUtc: Long,
    val endUtc: Long,
    val durationMs: Long,
    val startReason: String,
    val endReason: String,
    val flags: Int,
    val quality: String,
    val algoVersion: Int,
)

@Entity(tableName = "daily_summary")
data class DailySummaryEntity(
    /** Data local ISO (aaaa-mm-dd), no fuso de referência. */
    @PrimaryKey val date: String,
    val zoneId: String,
    val dayStartUtc: Long,
    val dayLengthMs: Long,
    val unlockedMs: Long,
    val screenOnMs: Long,
    val unlocks: Int,
    val sessions: Int,
    val longestSessionMs: Long,
    val medianSessionMs: Long,
    val firstUnlockUtc: Long?,
    val lastLockUtc: Long?,
    val coveredMs: Long,
    val coverageRatio: Double,
    val status: String,
    val timeQuality: String,
    val qualityFlags: Int,
    val hasOpenSession: Boolean,
    val algoVersion: Int,
    val computedAtUtc: Long,
)

@Entity(tableName = "hourly_bucket", primaryKeys = ["date", "hour"])
data class HourlyBucketEntity(
    val date: String,
    val hour: Int,
    val unlockedMs: Long,
    val unlocks: Int,
)
