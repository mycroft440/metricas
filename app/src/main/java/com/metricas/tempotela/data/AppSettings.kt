package com.metricas.tempotela.data

import android.content.Context
import java.time.ZoneId

/** Preferências do usuário e marcadores internos (SharedPreferences "settings"). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class Snapshot(
        val referenceZoneId: String,
        val followDeviceZone: Boolean,
        val dayCutoffHour: Int,
        val includePartialDays: Boolean,
    )

    init {
        if (!prefs.contains(KEY_ZONE)) {
            prefs.edit().putString(KEY_ZONE, ZoneId.systemDefault().id).apply()
        }
    }

    var referenceZoneId: String
        get() = prefs.getString(KEY_ZONE, null) ?: ZoneId.systemDefault().id
        set(value) = prefs.edit().putString(KEY_ZONE, value).apply()

    var followDeviceZone: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_DEVICE, false)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_DEVICE, value).apply()

    var dayCutoffHour: Int
        get() = prefs.getInt(KEY_CUTOFF, 0).coerceIn(0, 23)
        set(value) = prefs.edit().putInt(KEY_CUTOFF, value.coerceIn(0, 23)).apply()

    var includePartialDays: Boolean
        get() = prefs.getBoolean(KEY_PARTIAL, false)
        set(value) = prefs.edit().putBoolean(KEY_PARTIAL, value).apply()

    /** Fim (em hora real) da última janela ingerida com sucesso. */
    var watermarkUtc: Long?
        get() = if (prefs.contains(KEY_WATERMARK)) prefs.getLong(KEY_WATERMARK, 0) else null
        set(value) {
            if (value == null) prefs.edit().remove(KEY_WATERMARK).apply()
            else prefs.edit().putLong(KEY_WATERMARK, value).apply()
        }

    /** Pede recálculo completo na próxima vez (mudança de fuso/corte). */
    var needsFullRecompute: Boolean
        get() = prefs.getBoolean(KEY_FULL_RECOMPUTE, true)
        set(value) = prefs.edit().putBoolean(KEY_FULL_RECOMPUTE, value).apply()

    var lastPermissionGranted: Boolean?
        get() = if (prefs.contains(KEY_LAST_PERMISSION)) prefs.getBoolean(KEY_LAST_PERMISSION, false) else null
        set(value) {
            if (value == null) prefs.edit().remove(KEY_LAST_PERMISSION).apply()
            else prefs.edit().putBoolean(KEY_LAST_PERMISSION, value).apply()
        }

    fun referenceZone(): ZoneId = try {
        ZoneId.of(referenceZoneId)
    } catch (e: Exception) {
        ZoneId.systemDefault()
    }

    fun snapshot() = Snapshot(referenceZoneId, followDeviceZone, dayCutoffHour, includePartialDays)

    companion object {
        private const val KEY_ZONE = "reference_zone"
        private const val KEY_FOLLOW_DEVICE = "follow_device_zone"
        private const val KEY_CUTOFF = "day_cutoff_hour"
        private const val KEY_PARTIAL = "include_partial_days"
        private const val KEY_WATERMARK = "watermark_utc"
        private const val KEY_FULL_RECOMPUTE = "needs_full_recompute"
        private const val KEY_LAST_PERMISSION = "last_permission_granted"

        fun isValidZone(id: String): Boolean = try {
            ZoneId.of(id.trim())
            true
        } catch (e: Exception) {
            false
        }
    }
}
