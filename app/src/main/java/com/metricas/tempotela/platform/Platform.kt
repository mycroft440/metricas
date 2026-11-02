package com.metricas.tempotela.platform

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import java.util.concurrent.TimeUnit
import com.metricas.core.ingest.DeviceEvent
import com.metricas.core.model.EventType

/** Leituras dos três relógios do aparelho no mesmo instante. */
data class DeviceClockReading(val bootCount: Int, val elapsedMs: Long, val wallMs: Long)

object DeviceClock {
    fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun read(context: Context): DeviceClockReading =
        DeviceClockReading(bootCount(context), SystemClock.elapsedRealtime(), System.currentTimeMillis())
}

object UsageAccess {
    fun has(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Abre a tela de "Acesso ao uso" (direto no app quando o fabricante permite). */
    fun openSettings(context: Context) {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOf(direct, list, details)) {
            try {
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // tenta a próxima
            } catch (e: SecurityException) {
                // tenta a próxima
            }
        }
    }
}

/**
 * Proteções do sistema que podem impedir a coleta em segundo plano. Os eventos ficam no
 * Android por ~10 dias; se o app ficar pausado por mais que isso, dados são perdidos.
 */
object BackgroundProtection {
    /** "Pausar atividade do app se não usado" ligado (hibernação, Android 11+). */
    fun unusedAppRestrictionsEnabled(context: Context): Boolean = try {
        val status = PackageManagerCompat.getUnusedAppRestrictionsStatus(context).get(3, TimeUnit.SECONDS)
        status == UnusedAppRestrictionsConstants.API_30 ||
            status == UnusedAppRestrictionsConstants.API_30_BACKPORT ||
            status == UnusedAppRestrictionsConstants.API_31
    } catch (e: Exception) {
        false
    }

    fun unusedAppRestrictionsIntent(context: Context): Intent =
        IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)

    /** O app está sujeito à otimização de bateria (pode atrasar as leituras). */
    fun batteryOptimized(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batterySettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

object DeviceState {
    fun isUserUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    fun isDeviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: true

    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** Lê os eventos de tela/keyguard/boot registrados pelo sistema (UsageStatsManager). */
class UsageEventSource(context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    /** [beginDevice, endDevice) no relógio do aparelho, em blocos de 1 dia. */
    fun query(beginDevice: Long, endDevice: Long): List<DeviceEvent> {
        val out = ArrayList<DeviceEvent>()
        if (usm == null || endDevice <= beginDevice) return out
        var start = beginDevice
        while (start < endDevice) {
            val end = minOf(endDevice, start + DAY_MS)
            queryChunk(start, end, out)
            start = end
        }
        return out
    }

    private fun queryChunk(begin: Long, end: Long, out: MutableList<DeviceEvent>) {
        val events: UsageEvents = (
            if (Build.VERSION.SDK_INT >= 35) queryFiltered(begin, end) else usm?.queryEvents(begin, end)
            ) ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            if (EventType.isTracked(type)) out += DeviceEvent(event.timeStamp, type)
        }
    }

    @RequiresApi(35)
    private fun queryFiltered(begin: Long, end: Long): UsageEvents? {
        val query = UsageEventsQuery.Builder(begin, end)
            .setEventTypes(*EventType.TRACKED)
            .build()
        return usm?.queryEvents(query)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
