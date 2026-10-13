package me.balaj.inverterlink

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

fun checkProtocol(ok: Boolean, message: String) {
    require(ok) { message }
}

fun Byte.u() = toInt() and 255

fun privateIPv4(value: String): Boolean {
    val parts = value.split('.')
    if (
        parts.size != 4 ||
            parts.any { it.toIntOrNull() !in 0..255 || it.toIntOrNull().toString() != it }
    )
        return false
    return parts[0] == "10" ||
        parts[0] == "192" && parts[1] == "168" ||
        parts[0] == "172" && parts[1].toInt() in 16..31
}

fun validateHost(input: String): String {
    val host = input.trim().lowercase(Locale.ROOT)
    checkProtocol(host.length in 1..253, "Enter a private IP address or hostname.")
    if (host.all { it.isDigit() || it == '.' })
        checkProtocol(privateIPv4(host), "Enter a private IPv4 address without leading zeros.")
    else
        checkProtocol(
            host.split('.').all {
                it.length in 1..63 &&
                    it.first().isLetterOrDigit() &&
                    it.last().isLetterOrDigit() &&
                    it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' }
            },
            "Invalid hostname.",
        )
    return host
}

fun resolveHost(input: String): String {
    val host = validateHost(input)
    return InetAddress.getAllByName(host)
        .firstOrNull { it is Inet4Address && privateIPv4(it.hostAddress.orEmpty()) }
        ?.hostAddress ?: error("Hostname did not resolve to a private IPv4 address.")
}

enum class ReadCommand(val wire: String, val label: String) {
    PROTOCOL("QPI", "Protocol"),
    FIRMWARE("QVFW", "Firmware"),
    RATINGS("QPIRI", "Ratings"),
    STATUS("QPIGS", "Status"),
    MODE("QMOD", "Mode"),
    WARNINGS("QPIWS", "Warnings"),
    LED("QLED", "LED settings"),
    FLAGS("QFLAG", "Flags"),
    EQUALIZATION("QBEQI", "Equalization"),
    MAX_CHARGE("QMCHGCR", "Charge currents"),
    AC_CHARGE("QMUCHGCR", "AC charge currents"),
}

object PI30 {
    fun crc(data: ByteArray): ByteArray {
        var crc = 0
        data.forEach { b ->
            crc = crc xor (b.u() shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 65535 }
        }
        return byteArrayOf((crc shr 8).toByte(), crc.toByte())
            .map { if (it.u() in listOf(10, 13, 40)) (it.u() + 1).toByte() else it }
            .toByteArray()
    }

    fun request(command: String): ByteArray {
        checkProtocol(
            command.isNotEmpty() && command.length <= 64 && command.all { it.code in 32..126 },
            "Invalid command.",
        )
        val body = command.toByteArray(Charsets.US_ASCII)
        return body + crc(body) + byteArrayOf(13)
    }

    fun response(data: ByteArray): String {
        checkProtocol(
            data.size >= 4 && data.first().u() == 40 && data.last().u() == 13,
            "Invalid inverter response framing.",
        )
        val body = data.copyOfRange(0, data.size - 3)
        checkProtocol(
            data.copyOfRange(data.size - 3, data.size - 1).contentEquals(crc(body)),
            "Inverter checksum failed. Reading discarded.",
        )
        val text = data.copyOfRange(1, data.size - 3)
        checkProtocol(text.all { it.u() in 32..126 }, "Invalid inverter response text.")
        val result = text.toString(Charsets.US_ASCII)
        checkProtocol(
            result !in listOf("NAK", "NOA", "ERCRC"),
            "Inverter rejected the command: $result.",
        )
        return result
    }
}

data class TunnelFrame(
    val transaction: Int,
    val code: Int,
    val address: Int,
    val function: Int,
    val payload: ByteArray,
) {
    fun bytes(): ByteArray =
        listOf(transaction, code, payload.size + 2)
            .flatMap { listOf((it shr 8).toByte(), it.toByte()) }
            .toByteArray() + byteArrayOf(address.toByte(), function.toByte()) + payload

    fun matches(id: Int, route: Int): Boolean {
        if (function == 1) return false
        checkProtocol(function == 4, "Unexpected logger response.")
        if (transaction != id) return false
        checkProtocol(
            code == route && address == 1,
            "Logger response route does not match the request.",
        )
        return true
    }
}

class FrameDecoder {
    private var buffer = byteArrayOf()

    fun append(bytes: ByteArray): List<TunnelFrame> {
        buffer += bytes
        checkProtocol(buffer.size <= 8192, "Logger sent too much data.")
        val frames = mutableListOf<TunnelFrame>()
        while (buffer.size >= 8) {
            fun word(i: Int) = (buffer[i].u() shl 8) or buffer[i + 1].u()
            val length = word(4) + 6
            checkProtocol(length in 8..4096, "Invalid logger frame length.")
            if (buffer.size < length) break
            frames +=
                TunnelFrame(
                    word(0),
                    word(2),
                    buffer[6].u(),
                    buffer[7].u(),
                    buffer.copyOfRange(8, length),
                )
            buffer = buffer.copyOfRange(length, buffer.size)
        }
        return frames
    }
}

data class Metadata(
    val identity: String,
    val firmware: String,
    val power: Double,
    val batteryVoltage: Double,
) {
    companion object {
        fun parse(identity: String, firmware: String, ratings: String): Metadata {
            val fields = ratings.trim().split(Regex("\\s+"))
            val power = fields.getOrNull(6)?.toDoubleOrNull()
            val voltage = fields.getOrNull(7)?.toDoubleOrNull()
            checkProtocol(
                power != null &&
                    power.isFinite() &&
                    power > 0 &&
                    voltage != null &&
                    voltage.isFinite() &&
                    voltage > 0,
                "Invalid inverter ratings.",
            )
            return Metadata(identity, firmware, power!!, voltage!!)
        }
    }
}

data class Reading(
    val time: Long,
    val values: Map<String, Double>,
    val mode: String,
    val warnings: String,
) {
    val hasWarning
        get() = '1' in warnings

    companion object {
        val mapping =
            linkedMapOf(
                "Grid voltage" to 0,
                "Grid frequency" to 1,
                "Output voltage" to 2,
                "Output frequency" to 3,
                "Apparent power" to 4,
                "Load power" to 5,
                "Load" to 6,
                "DC bus voltage" to 7,
                "Battery voltage" to 8,
                "Battery charge current" to 9,
                "Reported battery" to 10,
                "Temperature" to 11,
                "Reported PV current" to 12,
                "PV voltage" to 13,
                "Battery SCC voltage" to 14,
                "Battery discharge current" to 15,
                "PV charging power" to 19,
            )
        val units =
            listOf(
                    "V",
                    "Hz",
                    "V",
                    "Hz",
                    "VA",
                    "W",
                    "%",
                    "V",
                    "V",
                    "A",
                    "%",
                    "°C",
                    "A",
                    "V",
                    "V",
                    "A",
                    "W",
                )
                .zip(mapping.keys)
                .associate { it.second to it.first }

        fun parse(status: String, mode: String, warnings: String): Reading {
            val fields = status.trim().split(Regex("\\s+"))
            // shortcut: reject firmware layouts other than the verified 24-field PI30 response.
            checkProtocol(
                fields.size == 24,
                "Unsupported inverter status layout (${fields.size} fields).",
            )
            listOf(16 to 8, 20 to 3).forEach { (i, n) ->
                checkProtocol(
                    fields[i].length == n && fields[i].all { it == '0' || it == '1' },
                    "Invalid inverter status bits.",
                )
            }
            checkProtocol(
                warnings.isNotEmpty() && warnings.all { it == '0' || it == '1' },
                "Invalid warning bits.",
            )
            val values = mapping.mapValues { (name, index) ->
                fields[index].toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: error("Invalid reading: $name.")
            }
            return Reading(
                System.currentTimeMillis(),
                values,
                mapOf(
                    "P" to "Starting",
                    "S" to "Standby",
                    "L" to "Grid",
                    "B" to "Battery",
                    "F" to "Fault",
                    "H" to "Power saving",
                    "D" to "Shutdown",
                )[mode] ?: "Unknown ($mode)",
                warnings,
            )
        }
    }
}

data class LEDSettings(
    val enabled: Boolean,
    val speed: Int,
    val effect: Int,
    val brightness: Int,
    val count: Int,
    val colors: List<String>,
) {
    companion object {
        fun parse(response: String): LEDSettings {
            val f = response.trim().split(Regex("\\s+"))
            val n = f.take(5).map { it.toIntOrNull() }
            checkProtocol(
                f.size in 7..8 &&
                    n.size == 5 &&
                    n[0] in 0..1 &&
                    n[1] in 0..2 &&
                    n[2] in 0..3 &&
                    n[3] in 0..9 &&
                    n[4] in 2..3 &&
                    f.size >= 5 + n[4]!!,
                "Unsupported inverter LED settings response.",
            )
            val colors =
                f.drop(5).map { raw ->
                    checkProtocol(
                        raw.length == 9 && raw.all { it in '0'..'9' },
                        "Invalid LED color response.",
                    )
                    val bytes = raw.chunked(3).map { it.toInt() }
                    checkProtocol(bytes.all { it in 0..255 }, "Invalid LED color response.")
                    bytes.joinToString("") { "%02X".format(Locale.ROOT, it) }
                }
            return LEDSettings(n[0] == 1, n[1]!!, n[2]!!, n[3]!!, n[4]!!, colors)
        }
    }
}
