package devicemonitor

import devicemonitor.application.MonitorSession
import devicemonitor.application.SessionConfig
import devicemonitor.application.playerdebug.PlayerMonitorCapture
import devicemonitor.application.playerdebug.PlayerMonitorCaptureFactory
import devicemonitor.data.adb.Adb
import devicemonitor.data.playerdebug.MediaSnapshot
import devicemonitor.data.playerdebug.OptdParseDiagnostic
import devicemonitor.domain.playerdebug.EvidenceSource
import devicemonitor.domain.playerdebug.PlaybackEvent
import devicemonitor.presentation.web.WebApp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.*

class PlayerDebuggerApiTest {
    @Test fun capturesPersistsPagesReanalyzesAndReopensSanitizedBundle() {
        lateinit var received: SessionConfig
        withApp(PlayerMonitorCaptureFactory { config ->
            received = config
            FakeCapture(config.runDirectoryOverride, events())
        }) { base ->
            val start = request(
                base, "POST", "/api/player/start",
                """{"serial":"device-1","packageName":"com.example.player","durationSec":1,"intervalSec":0.25}""".toByteArray()
            )
            assertEquals(202, start.status)
            val captureId = Regex("\"captureId\":\"([^\"]+)\"").find(start.text)?.groupValues?.get(1)
            assertNotNull(captureId)
            assertTrue(received.cpuUtilEnabled)
            assertTrue(received.perCoreEnabled)
            assertTrue(received.topProcsEnabled)
            assertTrue(received.memoryMbEnabled)
            assertTrue(received.psiEnabled)
            assertTrue(received.topMemEnabled)
            assertTrue(received.playerDebugEnabled)
            assertTrue(received.logcatEnabled)

            awaitCapture(base, captureId)
            val status = request(base, "GET", "/api/player/status")
            assertEquals(200, status.status)
            assertFalse("session.started" in status.text, "Status polling must not serialize the full timeline")
            val graph = request(base, "GET", "/api/player/graph?captureId=$captureId")
            assertEquals(200, graph.status)
            assertContains(graph.text, "view-1")
            val firstPage = request(base, "GET", "/api/player/timeline?captureId=$captureId&offset=0&limit=2")
            assertEquals(200, firstPage.status)
            assertContains(firstPage.text, "\"nextOffset\":2")
            val anchored = request(base, "GET", "/api/player/timeline?captureId=$captureId&sequence=3&limit=3")
            assertEquals(200, anchored.status)
            assertContains(anchored.text, "error.reported")
            val incidents = request(base, "GET", "/api/player/incidents?captureId=$captureId")
            assertEquals(200, incidents.status)
            assertContains(incidents.text, "playback_error")
            val report = request(base, "GET", "/api/player/report?captureId=$captureId")
            assertEquals(200, report.status)
            assertContains(report.text, "Player Debugger report")
            assertFalse("http://" in report.text || "https://" in report.text)
            assertEquals(200, request(base, "POST", "/api/player/reanalyze?captureId=$captureId").status)
            assertEquals(200, request(base, "GET", "/api/player/export-preview?captureId=$captureId").status)

            val exported = request(base, "POST", "/api/player/export?captureId=$captureId")
            assertEquals(200, exported.status)
            assertTrue(exported.bytes.copyOfRange(0, 2).contentEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
            val imported = request(base, "POST", "/api/player/import", exported.bytes, "application/zip")
            assertEquals(201, imported.status)
            assertFalse(captureId in imported.text)
            val captures = request(base, "GET", "/api/player/captures")
            assertEquals(2, Regex("\"captureId\"").findAll(captures.text).count())
        }
    }

    @Test fun rejectsUnsafeMethodsUnknownCapturesAndInvalidInputs() {
        withApp(PlayerMonitorCaptureFactory { FakeCapture(null, emptyList()) }) { base ->
            assertEquals(405, request(base, "DELETE", "/api/player/start").status)
            assertEquals(404, request(base, "GET", "/api/player/graph?captureId=missing").status)
            assertEquals(400, request(base, "GET", "/api/player/capabilities?serial=../../bad").status)
            val invalid = request(base, "POST", "/api/player/start", "{}".toByteArray())
            assertEquals(400, invalid.status)
            assertContains(invalid.text, "invalid_capture")
        }
    }

    private fun awaitCapture(base: String, captureId: String) {
        repeat(200) {
            val response = request(base, "GET", "/api/player/captures")
            if (response.status == 200 && captureId in response.text && "COMPLETE" in response.text) return
            Thread.sleep(10)
        }
        fail("Player Debugger capture was not persisted")
    }

    private fun withApp(factory: PlayerMonitorCaptureFactory, block: (String) -> Unit) {
        val dir = Files.createTempDirectory("player-debugger-api").toFile()
        val adbFile = File(dir, "adb").apply { writeText("#!/bin/sh\nexit 0\n") }
        check(adbFile.setExecutable(true))
        val app = WebApp(
            port = 0,
            adb = Adb(adbFile.absolutePath),
            bindHost = "127.0.0.1",
            releaseGuardRoot = File(dir, "release-guard"),
            playerDebuggerRoot = File(dir, "player-debugger"),
            playerCaptureFactory = factory
        )
        app.start()
        try {
            block("http://127.0.0.1:${app.actualPort}")
        } finally {
            app.stop()
        }
    }

    private fun request(
        base: String,
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "application/json"
    ): Response {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 3_000
        connection.readTimeout = 8_000
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        connection.disconnect()
        return Response(status, bytes)
    }

    private data class Response(val status: Int, val bytes: ByteArray) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }

    private fun events() = listOf(
        event(1, "session.started", view = "view-1"),
        event(2, "player.created", view = "view-1", player = "player-1"),
        event(3, "error.reported", view = "view-1", player = "player-1")
    )

    private fun event(sequence: Long, type: String, view: String?, player: String? = null) = PlaybackEvent(
        sequence = sequence,
        schema = "optd/1",
        type = type,
        timeNs = sequence,
        packageName = "com.example.player",
        viewingSessionId = view,
        instanceId = player,
        source = EvidenceSource.APP_REPORTED,
        confidence = 1.0
    )

    private class FakeCapture(
        override val runDirectory: File?,
        private val capturedEvents: List<PlaybackEvent>
    ) : PlayerMonitorCapture {
        @Volatile private var value = MonitorSession.Status.STARTING
        override val status: MonitorSession.Status get() = value
        override val error: String? = null
        override fun start() { value = MonitorSession.Status.DONE }
        override fun stop() { value = MonitorSession.Status.DONE }
        override fun events() = capturedEvents
        override fun diagnostics() = emptyList<OptdParseDiagnostic>()
        override fun snapshots() = emptyList<MediaSnapshot>()
    }
}
