package me.balaj.inverterlink

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val settings
        get() = Sample.settings

    private fun setting(id: String) = Setting.all.single { it.id == id }

    private fun fails(action: () -> Unit) {
        try {
            action()
            fail("Expected validation failure")
        } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test
    fun verifiedCrcAndQueries() {
        assertArrayEquals(
            byteArrayOf(0x51, 0x50, 0x49, 0xbe.toByte(), 0xac.toByte(), 13),
            PI30.request("QPI"),
        )
        assertEquals(
            "PI30",
            PI30.response(byteArrayOf(40, 0x50, 0x49, 0x33, 0x30, 0x9a.toByte(), 0x0b, 13)),
        )
        fails { PI30.response(byteArrayOf(40, 0x50, 0x49, 0x33, 0x30, 0x9a.toByte(), 0x0c, 13)) }
        assertEquals(11, ReadCommand.entries.size)
    }

    @Test
    fun rejectsNakAndNonAscii() {
        for (text in listOf("NAK", "NOA", "ERCRC")) {
            val body = "($text".toByteArray()
            fails { PI30.response(body + PI30.crc(body) + byteArrayOf(13)) }
        }
        val body = byteArrayOf(40, 1)
        fails { PI30.response(body + PI30.crc(body) + byteArrayOf(13)) }
    }

    @Test
    fun fragmentedAndCombinedFrames() {
        val a = TunnelFrame(1, 258, 255, 1, "LOGGER".toByteArray())
        val b = TunnelFrame(2, 258, 1, 4, "status".toByteArray())
        val decoder = FrameDecoder()
        assertTrue(decoder.append(a.bytes().take(3).toByteArray()).isEmpty())
        val frames = decoder.append(a.bytes().drop(3).toByteArray() + b.bytes())
        assertEquals(2, frames.size)
        assertArrayEquals(a.payload, frames[0].payload)
        assertEquals(2, frames[1].transaction)
    }

    @Test
    fun rejectsBadLengthsAndOverflow() {
        fails { FrameDecoder().append(byteArrayOf(0, 0, 0, 0, 0, 0, 1, 4)) }
        fails { FrameDecoder().append(ByteArray(8193)) }
    }

    @Test
    fun pinsReplyRoute() {
        assertFalse(TunnelFrame(1, 258, 255, 1, byteArrayOf()).matches(1, 258))
        assertFalse(TunnelFrame(2, 258, 1, 4, byteArrayOf()).matches(1, 258))
        fails { TunnelFrame(1, 259, 1, 4, byteArrayOf()).matches(1, 258) }
        fails { TunnelFrame(1, 258, 255, 4, byteArrayOf()).matches(1, 258) }
        assertTrue(TunnelFrame(1, 258, 1, 4, byteArrayOf()).matches(1, 258))
    }

    @Test
    fun parsesRecordedReadings() {
        val r = Sample.reading
        assertEquals(764.0, r.values["Load power"]!!, 0.0)
        assertEquals(471.0, r.values["PV charging power"]!!, 0.0)
        assertEquals("Grid", r.mode)
        assertFalse(r.hasWarning)
    }

    @Test
    fun rejectsUnsupportedReadings() {
        fails { Reading.parse("1 2 3", "L", "0") }
        fails { Reading.parse(Sample.replies.getValue("QPIGS").replace("221.8", "NaN"), "L", "0") }
        fails { Reading.parse(Sample.replies.getValue("QPIGS"), "L", "warning") }
    }

    @Test
    fun validatesPrivateHosts() {
        assertEquals("inverter.local", validateHost(" Inverter.LOCAL "))
        assertTrue(privateIPv4("192.168.0.25"))
        for (host in
            listOf(
                "https://router.local",
                "8.8.8.8",
                "192.168.0.025",
                "-bad.local",
                "a..local",
                "a/b",
            )) {
            fails { validateHost(host) }
        }
    }

    @Test
    fun preservesAll37Settings() {
        assertEquals(37, Setting.all.size)
        assertEquals(37, Setting.all.map { it.id }.distinct().size)
        assertEquals(4, Setting.all.map { it.category }.distinct().size)
    }

    @Test
    fun goldenSettingCommands() {
        val golden =
            mapOf(
                "output_source" to ("2" to "POP02"),
                "input_range" to ("1" to "PGR01"),
                "output_voltage" to ("220" to "V220"),
                "output_frequency" to ("60" to "F60"),
                "battery_type" to ("2" to "PBT02"),
                "cutoff" to ("21.0" to "PSDV21.0"),
                "bulk" to ("28.2" to "PCVV28.2"),
                "float" to ("27.0" to "PBFT27.0"),
                "max_charge" to ("40" to "MNCHGC040"),
                "max_ac_charge" to ("20" to "MUCHGC020"),
                "charge_source" to ("3" to "PCP03"),
                "back_to_grid" to ("24.5" to "PBCV24.5"),
                "back_to_battery" to ("27.0" to "PBDV27.0"),
                "equalization" to ("1" to "PBEQE1"),
                "equalization_now" to ("0" to "PBEQA0"),
                "equalization_time" to ("60" to "PBEQT060"),
                "equalization_timeout" to ("120" to "PBEQOT120"),
                "equalization_period" to ("30" to "PBEQP030"),
                "equalization_voltage" to ("27.00" to "PBEQV27.00"),
                "led_status" to ("1" to "PLEDE1"),
                "led_speed" to ("2" to "PLEDS2"),
                "led_effect" to ("3" to "PLEDM3"),
                "led_brightness" to ("9" to "PLEDB9"),
                "led_count" to ("3" to "PLEDT3"),
                "led_color_1" to ("#0000FF" to "PLEDC1000000255"),
                "led_color_2" to ("FF0000" to "PLEDC2255000000"),
            )
        golden.forEach { (id, pair) ->
            assertEquals(id, pair.second, setting(id).command(pair.first, settings))
        }
        for (letter in "abdjkuvxyz") {
            assertEquals("PE$letter", setting("flag_$letter").command("1", settings))
            assertEquals("PD$letter", setting("flag_$letter").command("0", settings))
        }
        val three = settings.copy(led = settings.led.copy(count = 3))
        assertEquals("PLEDC3148000255", setting("led_color_3").command("9400FF", three))
    }

    @Test
    fun validatesEveryNumericBoundary() {
        for (s in Setting.all) {
            when (val i = s.input) {
                is Input.Decimal -> {
                    s.command(i.min.toString(), settings)
                    s.command(i.max.toString(), settings)
                    fails { s.command((i.max + 1).toString(), settings) }
                    fails { s.command("NaN", settings) }
                    fails { s.command((i.min + 0.0001).toString(), settings) }
                }
                is Input.Integer -> {
                    s.command(i.min.toString(), settings)
                    s.command(i.max.toString(), settings)
                    fails { s.command((i.min - 1).toString(), settings) }
                    fails { s.command((i.max + 1).toString(), settings) }
                    if (i.step > 1) fails { s.command((i.min + 1).toString(), settings) }
                }
                else -> Unit
            }
        }
    }

    @Test
    fun validatesChoiceAndColorInputs() {
        fails { setting("led_color_3").command("0000FF", settings) }
        fails { setting("max_charge").command("25", settings) }
        fails { setting("output_source").command("99", settings) }
        for (color in listOf("FF0000;PF", "FFFFF", "GG0000", "FFFFFFFF")) {
            fails { setting("led_color_1").command(color, settings) }
        }
        assertNull(settings.raw(Source.LED("color", 3)))
        assertEquals("Two-color mode", setting("led_color_3").display(null, settings))
    }

    @Test
    fun comparesReadback() {
        assertTrue(setting("output_voltage").verifies("230", settings))
        assertFalse(setting("output_voltage").verifies("220", settings))
        assertTrue(setting("led_color_1").verifies("#0000ff", settings))
        assertTrue(setting("bulk").verifies("28.20", settings))
    }

    @Test
    fun rejectsInvalidSettingsLayouts() {
        fails {
            InverterSettings.parse("1 2", "EabD", "0", Sample.replies.getValue("QLED"), "10", "10")
        }
        fails {
            InverterSettings.parse(
                Sample.replies.getValue("QPIRI"),
                "EaDa",
                Sample.replies.getValue("QBEQI"),
                Sample.replies.getValue("QLED"),
                "10",
                "10",
            )
        }
        fails { LEDSettings.parse("1 0 3 0 2 000000999 000000255") }
    }

    private suspend fun withMock(
        replies: Map<String, String> = Sample.replies,
        sent: MutableList<String>? = null,
        identify: Boolean = true,
        encodedReplies: Map<String, ByteArray> = emptyMap(),
        block: suspend (InverterSession, AtomicInteger) -> Unit,
    ) =
        coroutineScope {
            val server = ServerSocket(0)
            val writes = AtomicInteger()
            val responder =
                launch(Dispatchers.IO) {
                    server.use {
                        it.accept().use { socket ->
                            val decoder = FrameDecoder()
                            val buffer = ByteArray(4096)
                            while (isActive) {
                                val count = socket.getInputStream().read(buffer)
                                if (count < 0) break
                                for (frame in decoder.append(buffer.copyOf(count))) {
                                    val reply =
                                        if (frame.function == 1)
                                            TunnelFrame(
                                                frame.transaction,
                                                258,
                                                255,
                                                1,
                                                "TEST-LOGGER".toByteArray(),
                                            )
                                        else {
                                            val suffix =
                                                if (frame.payload.toString(Charsets.US_ASCII).startsWith("^P003PI")) 1 else 3
                                            val command =
                                                frame.payload
                                                    .copyOfRange(0, frame.payload.size - suffix)
                                                    .toString(Charsets.US_ASCII)
                                            sent?.add(command)
                                            val text =
                                                replies[command]
                                                    ?: "ACK".also { writes.incrementAndGet() }
                                            if (text == "NO_REPLY") continue
                                            val body =
                                                (if (command.startsWith("^P") && text.startsWith("PI"))
                                                    "^D005${text.removePrefix("PI")}" else "($text").toByteArray()
                                            TunnelFrame(
                                                frame.transaction,
                                                258,
                                                1,
                                                4,
                                                encodedReplies[command] ?: (body + PI30.crc(body) + byteArrayOf(13)),
                                            )
                                        }
                                    socket.getOutputStream().write(reply.bytes())
                                }
                            }
                        }
                    }
                }
            val socket = withContext(Dispatchers.IO) { Socket("127.0.0.1", server.localPort) }
            try {
                InverterSession(socket).use { session ->
                    withContext(Dispatchers.IO) {
                        if (identify) session.identify()
                        block(session, writes)
                    }
                }
            } finally {
                socket.close()
                responder.cancelAndJoin()
                server.close()
            }
        }

    @Test
    fun alternateIdentifierFramesMatchGoldenBytes() {
        assertArrayEquals(byteArrayOf(94,80,48,48,53,80,73,0x71,0x8b.toByte(),13), ProtocolProbe.SEC_CRC.request())
        assertArrayEquals("^P003PI\r".toByteArray(), ProtocolProbe.SEC_NO_CRC.request())
        assertEquals("PI18", ProtocolProbe.response(byteArrayOf(94,68,48,48,53,49,56,0x3b,0x03,13)))
        assertEquals("PI17", ProtocolProbe.response(byteArrayOf(94,68,48,48,53,49,55,0xca.toByte(),0xec.toByte(),13)))
    }

    @Test
    fun alternateIdentifiersRejectWrongLengthAndCorruption() {
        fun frame(body: String): ByteArray = body.toByteArray().let { it + PI30.crc(it) + byteArrayOf(13) }
        for (body in listOf("^D00418", "^D00618", "^D005AA", "^D005180", "^D0051", "^D0051\u0001", "^A00518")) {
            fails { ProtocolProbe.response(frame(body)) }
        }
        val corrupt = frame("^D00518").also { it[it.lastIndex-1] = (it[it.lastIndex-1].u() xor 1).toByte() }
        fails { ProtocolProbe.response(corrupt) }
        fails { ProtocolProbe.response(frame("^D00518").dropLast(1).toByteArray()) }
    }

    @Test
    fun protocolProbesStopOnCorruption() = runBlocking {
        withTimeout(10000) {
            fun corrupt(body: String): ByteArray = body.toByteArray().let { it + PI30.crc(it) + byteArrayOf(13) }
                .also { it[it.lastIndex-1] = (it[it.lastIndex-1].u() xor 1).toByte() }
            for (command in listOf("QPI", "^P005PI")) {
                val sent = mutableListOf<String>()
                try {
                    withMock(replies = Sample.replies + mapOf("QPI" to "NAK", "^P005PI" to "PI18"), sent = sent,
                        encodedReplies = mapOf(command to corrupt(if (command == "QPI") "(NAK" else "^D00518"))) { _, _ ->
                        fail("Corrupt reply must not connect")
                    }
                } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("checksum")) }
                assertEquals(if (command == "QPI") listOf("QPI") else listOf("QPI", "^P005PI"), sent)
            }
        }
    }

    @Test
    fun unavailableQueriesDoNotTriggerAlternateProbes() = runBlocking {
        withTimeout(10000) {
            val sent = mutableListOf<String>()
            try {
                withMock(replies = Sample.replies + ("QPI" to "NOA"), sent = sent) { _, _ -> fail("NOA must fail") }
            } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("NOA")) }
            assertEquals(listOf("QPI"), sent)
        }
    }

    @Test
    fun identifierTimeoutStillTriesNoCrcFormat() = runBlocking {
        withTimeout(10000) {
            val sent = mutableListOf<String>()
            try {
                withMock(replies = Sample.replies + mapOf("QPI" to "NAK", "^P005PI" to "NO_REPLY", "^P003PI" to "PI17"), sent = sent) { _, _ ->
                    fail("Alternate protocol must not connect")
                }
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("PI17"))
                assertTrue(e.message!!.contains("^P005PI: No reply"))
            }
            assertEquals(listOf("QPI", "^P005PI", "^P003PI"), sent)
        }
    }

    @Test
    fun unverifiedProtocolCannotSendSettings() = runBlocking {
        withTimeout(10000) {
            val sent = mutableListOf<String>()
            withMock(replies = Sample.replies + mapOf("QPI" to "NAK", "^P005PI" to "PI18"), sent = sent, identify = false) { session, writes ->
                try { session.identify(); fail("Alternate protocol must not connect") }
                catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("PI18")) }
                try { session.apply(setting("output_voltage"), "220"); fail("Settings must remain disabled") }
                catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("verified PI30")) }
                assertEquals(0, writes.get())
                assertEquals(listOf("QPI", "^P005PI"), sent)
            }
        }
    }

    @Test
    fun rejectedQpiIdentifiesPi18WithoutEnablingSettings() = runBlocking {
        withTimeout(10000) {
            val sent = mutableListOf<String>()
            try {
                withMock(replies = Sample.replies + mapOf("QPI" to "NAK", "^P005PI" to "PI18"), sent = sent) { _, _ ->
                    fail("Unsupported protocol must not connect")
                }
            } catch (e: IllegalArgumentException) {
                assertTrue("Missing alternate protocol: ${e.message}", e.message!!.contains("PI18"))
            }
            assertEquals(listOf("QPI", "^P005PI"), sent)
        }
    }

    @Test
    fun rejectedQpiIdentifiesPi17WithoutEnablingSettings() = runBlocking {
        withTimeout(10000) {
            val sent = mutableListOf<String>()
            try {
                withMock(replies = Sample.replies + mapOf("QPI" to "NAK", "^P005PI" to "NAK", "^P003PI" to "PI17"), sent = sent) { _, _ ->
                    fail("Unsupported protocol must not connect")
                }
            } catch (e: IllegalArgumentException) {
                assertTrue("Missing alternate protocol: ${e.message}", e.message!!.contains("PI17"))
            }
            assertEquals(listOf("QPI", "^P005PI", "^P003PI"), sent)
        }
    }

    @Test
    fun startupRejectionNamesTheCommand() = runBlocking {
        withTimeout(10000) {
            for (command in listOf("QPI", "QVFW", "QPIRI")) {
                try {
                    withMock(replies = Sample.replies + (command to "NAK")) { _, _ ->
                        fail("Rejected startup query should fail")
                    }
                } catch (e: IllegalArgumentException) {
                    assertTrue("Missing command in: ${e.message}", e.message!!.contains(command))
                    assertTrue(e.message!!.contains("NAK"))
                }
            }
        }
    }

    @Test
    fun readingRejectionNamesTheCommand() = runBlocking {
        withTimeout(10000) {
            for (command in listOf("QPIGS", "QMOD", "QPIWS")) {
                withMock(replies = Sample.replies + (command to "NAK")) { session, _ ->
                    try {
                        session.read()
                        fail("Rejected reading query should fail")
                    } catch (e: IllegalArgumentException) {
                        assertTrue("Missing command in: ${e.message}", e.message!!.contains(command))
                        assertTrue(e.message!!.contains("NAK"))
                    }
                }
            }
        }
    }

    @Test
    fun realSocketHandshakeAndReads() = runBlocking {
        withTimeout(10000) {
            withMock { session, _ ->
                assertEquals("TEST-LOGGER", session.metadata.identity)
                assertEquals(764.0, session.read().values["Load power"]!!, 0.0)
                assertEquals(37, Setting.all.size)
                assertEquals(24.0, session.metadata.batteryVoltage, 0.0)
                assertEquals("0000FF", session.settings().raw(Source.LED("color", 1)))
            }
        }
    }

    @Test
    fun writeReadbackMismatchNeverRetries() = runBlocking {
        withTimeout(10000) {
            withMock { session, writes ->
                try {
                    session.apply(setting("output_voltage"), "220")
                    fail("Mismatch should fail")
                } catch (e: IllegalArgumentException) {
                    assertTrue(e.message!!.contains("readback"))
                }
                assertEquals(1, writes.get())
            }
        }
    }

    @Test
    fun cancelledSocketReadStopsPromptly() = runBlocking {
        val server = ServerSocket(0)
        val peer = launch(Dispatchers.IO) { server.accept().use { delay(5000) } }
        val socket = Socket("127.0.0.1", server.localPort)
        val session = InverterSession(socket)
        val job = launch(Dispatchers.IO) { session.identify() }
        delay(100)
        withTimeout(1000) { job.cancelAndJoin() }
        session.close()
        peer.cancelAndJoin()
        server.close()
    }
}
