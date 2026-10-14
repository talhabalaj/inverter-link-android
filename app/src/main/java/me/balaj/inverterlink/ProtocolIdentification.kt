package me.balaj.inverterlink

class CommandRejected(val reply: String) : IllegalArgumentException("Inverter rejected the command: $reply.")

class InverterResponseTimeout : IllegalArgumentException("Inverter response timed out.")

// Fixed, read-only identifier formats from the PI17/PI18 specifications. No settings commands.
enum class ProtocolProbe(val wire: String, private val requestCrc: Boolean) {
    SEC_CRC("^P005PI", true),
    SEC_NO_CRC("^P003PI", false);

    fun request(): ByteArray =
        if (requestCrc) PI30.request(wire) else "$wire\r".toByteArray(Charsets.US_ASCII)

    companion object {
        fun response(data: ByteArray): String {
            // Some devices reject alternate queries using a PI30-formatted NAK.
            if (data.firstOrNull()?.u() == 40) {
                PI30.response(data)
                throw IllegalArgumentException("Unexpected protocol identifier reply.")
            }
            checkProtocol(data.size >= 5 && data.last().u() == 13, "Invalid identifier response framing.")
            val body = data.copyOfRange(0, data.size - 3)
            checkProtocol(
                data.copyOfRange(data.size - 3, data.size - 1).contentEquals(PI30.crc(body)),
                "Identifier checksum failed. Reply discarded.",
            )
            checkProtocol(body.all { it.u() in 32..126 }, "Invalid identifier response text.")
            val text = body.toString(Charsets.US_ASCII)
            if (text == "^0") throw CommandRejected("NAK")
            checkProtocol(
                text.length == 7 && text.startsWith("^D005") && text.takeLast(2).all { it in '0'..'9' },
                "Invalid identifier response length or format.",
            )
            return "PI${text.takeLast(2)}"
        }
    }
}
