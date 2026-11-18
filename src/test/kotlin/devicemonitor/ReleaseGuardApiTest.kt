package devicemonitor

import devicemonitor.data.adb.Adb
import devicemonitor.data.storage.ReleaseGuardJson
import devicemonitor.domain.releaseguard.ReleaseScenario
import devicemonitor.domain.releaseguard.ScenarioStep
import devicemonitor.presentation.web.WebApp
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReleaseGuardApiTest {
    @Test fun projectAndImmutableScenarioWorkflowIsLocalAndMethodSafe() {
        withApp { base ->
            val page = request(base, "GET", "/release-guard")
            assertEquals(200, page.status)
            assertContains(page.body, "Offline performance release gate")
            assertFalse(Regex("(?:src|href)=[\"']https?://", RegexOption.IGNORE_CASE).containsMatchIn(page.body))

            val projectJson = """{"id":"tv-player","name":"TV Player","packageName":"com.example.player"}"""
            assertEquals(200, request(base, "POST", "/api/release/projects", projectJson).status)
            val projects = request(base, "GET", "/api/release/projects")
            assertEquals(200, projects.status)
            assertContains(projects.body, "tv-player")

            val scenario = ReleaseScenario(
                id = "cold-launch",
                name = "Cold launch",
                packageName = "com.example.player",
                steps = listOf(ScenarioStep.LaunchApp())
            )
            val scenarioPath = "/api/release/scenarios?projectId=tv-player"
            assertEquals(200, request(base, "POST", scenarioPath, ReleaseGuardJson.scenario(scenario)).status)
            val scenarios = request(base, "GET", scenarioPath)
            assertEquals(200, scenarios.status)
            assertContains(scenarios.body, "cold-launch")

            val changedSameRevision = scenario.copy(name = "Changed without a revision")
            val immutable = request(base, "POST", scenarioPath, ReleaseGuardJson.scenario(changedSameRevision))
            assertEquals(400, immutable.status)
            assertContains(immutable.body, "immutable")

            assertEquals(405, request(base, "DELETE", "/api/release/projects").status)
            assertEquals(404, request(base, "GET", "/api/release/projects/extra").status)
            assertEquals(404, request(base, "GET", "/api/release/artifact?runId=missing&key=../../secret").status)
        }
    }

    @Test fun rejectsUnknownProjectsAndOversizedJson() {
        withApp { base ->
            val scenario = ReleaseScenario(
                id = "journey",
                name = "Journey",
                packageName = "com.example.app",
                steps = listOf(ScenarioStep.Wait(1))
            )
            val unknown = request(
                base,
                "POST",
                "/api/release/scenarios?projectId=missing",
                ReleaseGuardJson.scenario(scenario)
            )
            assertEquals(400, unknown.status)
            assertContains(unknown.body, "Unknown project")

            val tooLarge = request(base, "POST", "/api/release/projects", "x".repeat(1_048_577))
            assertEquals(413, tooLarge.status)
        }
    }

    private fun withApp(block: (String) -> Unit) {
        val dir = Files.createTempDirectory("release-guard-api").toFile()
        val adb = dir.resolve("adb")
        adb.writeText("#!/bin/sh\nexit 0\n")
        check(adb.setExecutable(true))
        val app = WebApp(
            port = 0,
            adb = Adb(adb.absolutePath),
            bindHost = "127.0.0.1",
            releaseGuardRoot = dir.resolve("release-guard")
        )
        app.start()
        try {
            block("http://127.0.0.1:${app.actualPort}")
        } finally {
            app.stop()
        }
    }

    private fun request(base: String, method: String, path: String, body: String? = null): Response {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 3_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            if (body.length > 1_048_576) connection.setChunkedStreamingMode(8_192)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return Response(status, responseBody)
    }

    private data class Response(val status: Int, val body: String)
}
