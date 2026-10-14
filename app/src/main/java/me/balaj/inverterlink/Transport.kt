package me.balaj.inverterlink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.net.*
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.ArrayDeque
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class WiFiScope(val network: Network?, val local: String, val prefix: Int) {
    companion object {
        fun current(context: Context): WiFiScope {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network =
                cm.allNetworks.firstOrNull {
                    cm.getNetworkCapabilities(it)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } ?: error("Join the inverter’s Wi-Fi network.")
            val address =
                cm.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                    it.address is Inet4Address && privateIPv4(it.address.hostAddress.orEmpty())
                } ?: error("Wi-Fi has no private IPv4 address.")
            return WiFiScope(network, address.address.hostAddress!!, address.prefixLength)
        }
    }

    fun resolve(input: String): String {
        val host = validateHost(input)
        return (network?.getAllByName(host) ?: InetAddress.getAllByName(host))
            .firstOrNull { it is Inet4Address && privateIPv4(it.hostAddress.orEmpty()) }
            ?.hostAddress ?: error("Hostname did not resolve to a private IPv4 address.")
    }

    fun hosts(): List<String> {
        val p = maxOf(24, prefix)
        checkProtocol(p <= 30, "This Wi-Fi subnet has no addresses to scan.")
        fun word(s: String) = s.split('.').fold(0L) { n, b -> (n shl 8) or b.toLong() }
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        val first = word(local) and mask
        val last = first or (mask xor 0xFFFFFFFFL)
        return ((first + 1) until last)
            .map { v -> listOf(24, 16, 8, 0).joinToString(".") { ((v shr it) and 255).toString() } }
            .filter { it != local }
    }
}

suspend fun probe(scope: WiFiScope, host: String, port: Int = 8899): Boolean =
    withContext(Dispatchers.IO) {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            scope.network?.bindSocket(socket)
            socket.bind(InetSocketAddress(scope.local, 0))
            socket.soTimeout = 700
            val bytes = "set>server=${scope.local}:$port;".toByteArray(Charsets.US_ASCII)
            socket.connect(InetSocketAddress(host, 58899))
            socket.send(DatagramPacket(bytes, bytes.size))
            try {
                val reply = DatagramPacket(ByteArray(128), 128)
                socket.receive(reply)
                reply.data
                    .copyOfRange(0, reply.length)
                    .toString(Charsets.US_ASCII)
                    .trimEnd('\r', '\n') in listOf("rsp>server=1;", "rsp>server=2;")
            } catch (_: SocketTimeoutException) {
                false
            }
        }
    }

suspend fun discover(scope: WiFiScope, exclude: String? = null, onFound: suspend (String) -> Unit) =
    coroutineScope {
        val limit = Semaphore(24)
        scope
            .hosts()
            .filter { it != exclude }
            .map { host ->
                launch(Dispatchers.IO) {
                    limit.withPermit {
                        ensureActive()
                        try {
                            if (probe(scope, host)) onFound(host)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {}
                    }
                }
            }
            .joinAll()
    }

class InverterSession(private val socket: Socket) : Closeable {
    private val decoder = FrameDecoder()
    private val frames = ArrayDeque<TunnelFrame>()
    private var transaction = 0
    private var route = 0
    private var heartbeatAt = 0L
    lateinit var metadata: Metadata
        private set

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    companion object {
        suspend fun connect(
            scope: WiFiScope,
            ip: String,
            expected: String? = null,
        ): InverterSession =
            withContext(Dispatchers.IO) {
                checkProtocol(
                    privateIPv4(ip) && ip != scope.local,
                    "Choose the logger’s private IP, not this phone’s address.",
                )
                var session: InverterSession? = null
                try {
                    ServerSocket().use { server ->
                        server.reuseAddress = true
                        server.soTimeout = 300
                        server.bind(InetSocketAddress(scope.local, 8899))
                        val deadline = System.nanoTime() + 15_000_000_000L
                        var announceAt = 0L
                        var accepted: Socket? = null
                        while (accepted == null && System.nanoTime() < deadline) {
                            ensureActive()
                            if (System.nanoTime() >= announceAt) {
                                probe(scope, ip, server.localPort)
                                announceAt = System.nanoTime() + 3_000_000_000L
                            }
                            try {
                                val peer = server.accept()
                                if (peer.inetAddress.hostAddress == ip) {
                                    accepted = peer
                                } else peer.close()
                            } catch (_: SocketTimeoutException) {}
                        }
                        session =
                            InverterSession(
                                accepted
                                    ?: error(
                                        "Logger callback timed out. Check Wi-Fi, LAN permission and TCP 8899."
                                    )
                            )
                    }
                    session!!.identify()
                    checkProtocol(
                        expected == null || session!!.metadata.identity == expected,
                        "This is a different inverter.",
                    )
                    session!!
                } catch (e: Throwable) {
                    session?.close()
                    throw e
                }
            }
    }

    private suspend fun frame(seconds: Double): TunnelFrame {
        val deadline = System.nanoTime() + (seconds * 1e9).toLong()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (frames.isNotEmpty()) return frames.removeFirst()
            if (System.nanoTime() >= deadline) throw InverterResponseTimeout()
            socket.soTimeout =
                minOf(250, maxOf(1, ((deadline - System.nanoTime()) / 1_000_000).toInt()))
            val bytes = ByteArray(4096)
            try {
                val count = input.read(bytes)
                checkProtocol(count > 0, "Logger disconnected.")
                frames.addAll(decoder.append(bytes.copyOf(count)))
            } catch (_: SocketTimeoutException) {}
        }
    }

    private fun send(code: Int, function: Int, payload: ByteArray): Int {
        transaction = if (transaction == 65535) 1 else transaction + 1
        output.write(TunnelFrame(transaction, code, 1, function, payload).bytes())
        output.flush()
        return transaction
    }

    private fun heartbeat() {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val year = now.year - 2000
        checkProtocol(year in 0..255, "Phone date is out of range.")
        send(
            0,
            1,
            byteArrayOf(
                year.toByte(),
                now.monthValue.toByte(),
                now.dayOfMonth.toByte(),
                now.hour.toByte(),
                now.minute.toByte(),
                now.second.toByte(),
                0,
                60,
            ),
        )
        heartbeatAt = System.nanoTime()
    }

    suspend fun identify() {
        heartbeat()
        val reply = frame(5.0)
        val identity = reply.payload.toString(Charsets.US_ASCII).trimEnd('\u0000', ' ')
        checkProtocol(
            reply.function == 1 &&
                reply.address == 255 &&
                reply.transaction == transaction &&
                reply.code != 0 &&
                identity.length in 1..64 &&
                identity.all { it.code in 32..126 },
            "Invalid Eybond logger identity.",
        )
        route = reply.code
        val protocol =
            try {
                query(ReadCommand.PROTOCOL)
            } catch (e: IllegalArgumentException) {
                if ((e.cause as? CommandRejected)?.reply == "NAK") throw identifyAlternateProtocol()
                throw e
            }
        checkProtocol(
            protocol == "PI30",
            "Only the verified PI30 model is supported.",
        )
        val firmware = query(ReadCommand.FIRMWARE)
        metadata = Metadata.parse(identity, firmware, query(ReadCommand.RATINGS))
    }

    private suspend fun identifyAlternateProtocol(): IllegalArgumentException {
        val results = mutableListOf("QPI: NAK")
        var detected: String? = null
        for (probe in ProtocolProbe.entries) {
            try {
                detected = exchange(probe.request(), ProtocolProbe::response)
                results += "${probe.wire}: $detected"
                break
            } catch (e: CommandRejected) {
                results += "${probe.wire}: ${e.reply}"
                if (e.reply != "NAK") break
            } catch (_: InverterResponseTimeout) {
                results += "${probe.wire}: No reply"
            } catch (e: IllegalArgumentException) {
                results += "${probe.wire}: ${e.message}"
                break // Invalid CRC, route or framing is not evidence to try another dialect.
            }
        }
        val summary =
            if (detected != null) "Received $detected. Monitoring and settings for this protocol are not supported yet."
            else "The inverter rejected QPI and could not be identified. Monitoring and settings are unavailable."
        return IllegalArgumentException(
            "$summary\n\nProtocol report (${BuildConfig.VERSION_NAME}):\n${results.joinToString("\n")}\n\nCopy this report to share. No inverter setting was changed."
        )
    }

    private suspend fun exchange(payload: ByteArray, response: (ByteArray) -> String = PI30::response): String {
        if (System.nanoTime() - heartbeatAt >= 45_000_000_000L) heartbeat()
        val id = send(route, 4, payload)
        val deadline = System.nanoTime() + 4_000_000_000L
        while (true) {
            val remaining = (deadline - System.nanoTime()) / 1e9
            if (remaining <= 0) throw InverterResponseTimeout()
            val reply = frame(remaining)
            if (reply.matches(id, route)) return response(reply.payload)
        }
    }

    suspend fun query(command: ReadCommand): String =
        try {
            exchange(PI30.request(command.wire))
        } catch (e: IllegalArgumentException) {
            // Preserve the failing query when startup closes the connection before diagnostics open.
            throw IllegalArgumentException("${command.label} (${command.wire}): ${e.message}", e)
        }

    suspend fun read(): Reading {
        val status = query(ReadCommand.STATUS)
        val mode = query(ReadCommand.MODE)
        return Reading.parse(status, mode, query(ReadCommand.WARNINGS))
    }

    suspend fun settings(): InverterSettings {
        val ratings = query(ReadCommand.RATINGS)
        val flags = query(ReadCommand.FLAGS)
        val eq = query(ReadCommand.EQUALIZATION)
        val led = query(ReadCommand.LED)
        val max = query(ReadCommand.MAX_CHARGE)
        return InverterSettings.parse(ratings, flags, eq, led, max, query(ReadCommand.AC_CHARGE))
    }

    suspend fun apply(setting: Setting, value: String): InverterSettings {
        checkProtocol(::metadata.isInitialized, "Connect to a verified PI30 inverter before changing settings.")
        val before = settings()
        val command = setting.command(value, before)
        checkProtocol(
            exchange(PI30.request(command)) == "ACK",
            "Inverter did not acknowledge ${setting.title}.",
        )
        val after = settings()
        checkProtocol(
            setting.verifies(value, after),
            "Acknowledged, but readback did not match. Check the inverter display before retrying.",
        )
        return after
    }

    suspend fun keepAlive() {
        if (System.nanoTime() - heartbeatAt >= 45_000_000_000L) heartbeat()
    }

    override fun close() {
        socket.close()
    }
}
