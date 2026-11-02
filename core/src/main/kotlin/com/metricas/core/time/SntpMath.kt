package com.metricas.core.time

/** Codificação/decodificação de pacotes SNTP (RFC 4330 / 5905) e cálculo de desvio. */
object SntpMath {
    const val PACKET_SIZE = 48
    const val PORT = 123
    private const val SECONDS_1900_TO_1970 = 2_208_988_800L
    private const val ERA_SECONDS = 0x1_0000_0000L

    const val MODE_SERVER = 4
    const val MODE_BROADCAST = 5

    /** Atraso + dispersão até a referência do servidor acima disso = servidor pouco confiável. */
    const val MAX_ROOT_ERROR_MS = 1_000L

    /** Ida e volta acima disso não serve para medir a hora com precisão. */
    const val MAX_DELAY_MS = 3_000L

    /** Requisição cliente: LI=0, VN=4, Mode=3. O carimbo de envio leva um nonce aleatório. */
    fun buildRequest(nonce: ByteArray): ByteArray {
        require(nonce.size == 8) { "nonce deve ter 8 bytes" }
        val buf = ByteArray(PACKET_SIZE)
        buf[0] = 0x23
        System.arraycopy(nonce, 0, buf, 40, 8)
        return buf
    }

    class Response(
        val leap: Int,
        val version: Int,
        val mode: Int,
        val stratum: Int,
        val rootDelayMs: Long,
        val rootDispersionMs: Long,
        val originate: ByteArray,
        val receiveMs: Long,
        val transmitMs: Long,
    ) {
        /** Incerteza do próprio servidor em relação à sua referência (RFC 5905: λ = δ/2 + ε). */
        val rootErrorMs: Long get() = rootDelayMs / 2 + rootDispersionMs
    }

    fun parse(buf: ByteArray, length: Int = buf.size): Response {
        require(length >= PACKET_SIZE && buf.size >= PACKET_SIZE) { "pacote NTP curto" }
        val b0 = buf[0].toInt() and 0xFF
        return Response(
            leap = (b0 shr 6) and 0x3,
            version = (b0 shr 3) and 0x7,
            mode = b0 and 0x7,
            stratum = buf[1].toInt() and 0xFF,
            rootDelayMs = readShortFormatMs(buf, 4),
            rootDispersionMs = readShortFormatMs(buf, 8),
            originate = buf.copyOfRange(24, 32),
            receiveMs = readTimestampMs(buf, 32),
            transmitMs = readTimestampMs(buf, 40),
        )
    }

    /** @return motivo da rejeição, ou null se a resposta é válida. */
    fun validate(resp: Response, nonce: ByteArray): String? = when {
        resp.mode != MODE_SERVER && resp.mode != MODE_BROADCAST -> "modo inválido (${resp.mode})"
        resp.leap == 3 -> "servidor não sincronizado"
        resp.stratum == 0 -> "kiss-o'-death"
        resp.stratum > 15 -> "stratum inválido (${resp.stratum})"
        !resp.originate.contentEquals(nonce) -> "resposta não corresponde à requisição"
        resp.receiveMs == 0L || resp.transmitMs == 0L -> "carimbo vazio"
        resp.transmitMs < resp.receiveMs -> "carimbos fora de ordem"
        resp.rootDelayMs < 0 || resp.rootErrorMs > MAX_ROOT_ERROR_MS -> "servidor com incerteza alta"
        else -> null
    }

    data class Measurement(val offsetMs: Long, val delayMs: Long) {
        val errorMs: Long get() = maxOf(1L, delayMs / 2)
    }

    /**
     * t0/t3: relógio local (envio/recebimento). t1/t2: servidor (recebimento/envio).
     * offset = hora_do_servidor − relógio_local.
     */
    fun measure(t0LocalMs: Long, t1ServerMs: Long, t2ServerMs: Long, t3LocalMs: Long): Measurement {
        val offset = ((t1ServerMs - t0LocalMs) + (t2ServerMs - t3LocalMs)) / 2
        val delay = (t3LocalMs - t0LocalMs) - (t2ServerMs - t1ServerMs)
        return Measurement(offset, delay.coerceAtLeast(0))
    }

    /** Erro total: metade da ida e volta + incerteza do servidor até a referência. */
    fun totalErrorMs(m: Measurement, resp: Response): Long = m.errorMs + resp.rootErrorMs

    fun readTimestampMs(buf: ByteArray, offset: Int): Long {
        var seconds = readUInt32(buf, offset)
        val fraction = readUInt32(buf, offset + 4)
        if (seconds == 0L && fraction == 0L) return 0L
        // Era 1 (a partir de 2036): bit mais alto zerado.
        if ((seconds and 0x8000_0000L) == 0L) seconds += ERA_SECONDS
        return (seconds - SECONDS_1900_TO_1970) * 1000L + ((fraction * 1000L) ushr 32)
    }

    fun writeTimestampMs(buf: ByteArray, offset: Int, ms: Long) {
        var seconds = ms / 1000 + SECONDS_1900_TO_1970
        if (seconds >= ERA_SECONDS) seconds -= ERA_SECONDS
        val fraction = ((ms % 1000) shl 32) / 1000
        writeUInt32(buf, offset, seconds)
        writeUInt32(buf, offset + 4, fraction)
    }

    /** Formato curto NTP (16.16 segundos, com sinal) em ms. */
    fun readShortFormatMs(buf: ByteArray, offset: Int): Long {
        val raw = readUInt32(buf, offset).toInt().toLong() // com sinal
        return (raw * 1000L) shr 16
    }

    fun writeShortFormatMs(buf: ByteArray, offset: Int, ms: Long) {
        writeUInt32(buf, offset, ((ms shl 16) / 1000) and 0xFFFF_FFFFL)
    }

    private fun readUInt32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)

    private fun writeUInt32(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value ushr 24).toByte()
        buf[off + 1] = (value ushr 16).toByte()
        buf[off + 2] = (value ushr 8).toByte()
        buf[off + 3] = value.toByte()
    }
}
