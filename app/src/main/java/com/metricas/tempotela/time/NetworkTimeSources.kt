package com.metricas.tempotela.time

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.time.TrustedTime
import com.google.android.gms.time.TrustedTimeClient
import com.metricas.core.time.HttpDateMath
import com.metricas.core.time.SntpMath
import com.metricas.core.time.TimeSample
import com.metricas.core.time.TimeSource
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.time.DateTimeException
import java.util.concurrent.TimeUnit

/**
 * Fontes de hora pela internet, em ordem de preferência (Seção 3.2 do plano).
 * Todas as funções bloqueiam: chamar fora da thread principal.
 */
class NetworkTimeSources(private val context: Context) {

    @Volatile
    private var trustedClient: TrustedTimeClient? = null
    private val random = SecureRandom()

    private fun trustedTimeClient(): TrustedTimeClient? {
        trustedClient?.let { return it }
        return try {
            Tasks.await(TrustedTime.createClient(context), 10, TimeUnit.SECONDS).also { trustedClient = it }
        } catch (e: Exception) {
            null
        }
    }

    /** 1. Google Play services TrustedTime (traz estimativa de erro). */
    fun trustedTime(): TimeSample? {
        val client = trustedTimeClient() ?: return null
        val signal = client.latestTimeSignal ?: return null
        val el0 = SystemClock.elapsedRealtime()
        val wall0 = System.currentTimeMillis()
        val computed = signal.computeCurrentInstant() ?: return null
        val el1 = SystemClock.elapsedRealtime()
        val half = (el1 - el0) / 2
        val error = computed.estimatedErrorMillis ?: (signal.acquisitionEstimatedErrorMillis + 1_000L)
        return TimeSample(
            source = TimeSource.TRUSTED_TIME,
            server = "Google TrustedTime",
            elapsedMs = el0 + half,
            trueUtcMs = computed.instantMillis,
            deviceWallMs = wall0 + half,
            errorMs = error + half,
        )
    }

    /** 2. Hora de rede mantida pelo próprio Android (API 33+). */
    fun networkClock(): TimeSample? =
        if (Build.VERSION.SDK_INT >= 33) networkClock33() else null

    @RequiresApi(33)
    private fun networkClock33(): TimeSample? = try {
        val clock = SystemClock.currentNetworkTimeClock()
        val el0 = SystemClock.elapsedRealtime()
        val wall0 = System.currentTimeMillis()
        val t = clock.millis()
        val el1 = SystemClock.elapsedRealtime()
        val half = (el1 - el0) / 2
        TimeSample(
            source = TimeSource.NETWORK_CLOCK,
            server = "Android",
            elapsedMs = el0 + half,
            trueUtcMs = t,
            deviceWallMs = wall0 + half,
            errorMs = NETWORK_CLOCK_ERROR_MS,
        )
    } catch (e: DateTimeException) {
        null
    }

    /** 3. SNTP direto: NTP.br (Hora Legal Brasileira) e Google. */
    fun sntp(): TimeSample? {
        for (host in SNTP_SERVERS) {
            val sample = try {
                sntpFrom(host)
            } catch (e: Exception) {
                null
            }
            if (sample != null) return sample
        }
        return null
    }

    private fun sntpFrom(host: String): TimeSample? {
        val address = InetAddress.getByName(host)
        var best: TimeSample? = null
        DatagramSocket().use { socket ->
            socket.soTimeout = SNTP_TIMEOUT_MS
            for (attempt in 0 until SNTP_ATTEMPTS) {
                val nonce = ByteArray(8).also { random.nextBytes(it) }
                val request = SntpMath.buildRequest(nonce)
                val buffer = ByteArray(512)
                val t0 = SystemClock.elapsedRealtimeNanos()
                try {
                    socket.send(DatagramPacket(request, request.size, address, SntpMath.PORT))
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val t3 = SystemClock.elapsedRealtimeNanos()
                    val wall3 = System.currentTimeMillis()
                    val response = SntpMath.parse(buffer, packet.length)
                    if (SntpMath.validate(response, nonce) != null) continue
                    val t0Ms = t0 / 1_000_000
                    val t3Ms = t3 / 1_000_000
                    val m = SntpMath.measure(t0Ms, response.receiveMs, response.transmitMs, t3Ms)
                    val sample = TimeSample(
                        source = TimeSource.SNTP,
                        server = host,
                        elapsedMs = t3Ms,
                        trueUtcMs = t3Ms + m.offsetMs,
                        deviceWallMs = wall3,
                        errorMs = m.errorMs,
                        rttMs = m.delayMs,
                    )
                    val current = best
                    if (current == null || sample.errorMs < current.errorMs) best = sample
                } catch (e: java.net.SocketTimeoutException) {
                    if (best == null) return null
                    break
                }
            }
        }
        return best
    }

    /** 4. Cabeçalho HTTP `Date` via HTTPS (precisão de ~1 s). */
    fun httpsDate(): TimeSample? {
        for (url in HTTPS_URLS) {
            val sample = try {
                httpsDateFrom(url)
            } catch (e: Exception) {
                null
            }
            if (sample != null) return sample
        }
        return null
    }

    private fun httpsDateFrom(url: String): TimeSample? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.connect()
            // t0 depois do handshake TLS: mede só a ida e volta da requisição.
            val t0 = SystemClock.elapsedRealtime()
            connection.responseCode
            val t3 = SystemClock.elapsedRealtime()
            val wall3 = System.currentTimeMillis()
            val header = connection.getHeaderField("Date") ?: return null
            val date = HttpDateMath.parseRfc1123(header) ?: return null
            val (trueAtT3, error) = HttpDateMath.estimate(date, t0, t3)
            return TimeSample(
                source = TimeSource.HTTPS_DATE,
                server = URL(url).host,
                elapsedMs = t3,
                trueUtcMs = trueAtT3,
                deviceWallMs = wall3,
                errorMs = error,
                rttMs = t3 - t0,
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val SNTP_SERVERS = listOf("a.st1.ntp.br", "b.st1.ntp.br", "c.st1.ntp.br", "time.google.com")
        val HTTPS_URLS = listOf("https://www.google.com/generate_204", "https://www.cloudflare.com/")
        private const val SNTP_TIMEOUT_MS = 3_000
        private const val SNTP_ATTEMPTS = 4
        private const val HTTP_TIMEOUT_MS = 5_000
        private const val NETWORK_CLOCK_ERROR_MS = 1_000L
    }
}
