package devicemonitor

import devicemonitor.data.adb.Adb
import devicemonitor.presentation.web.WebApp
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LayoutInspectorApiTest {
    @Test fun capturesServesAndRejectsUnauthorizedOrConcurrentRequests() {
        withApp { base ->
            val unauthorized = request(base, "POST", "/api/layout-inspector", "serial=missing")
            assertEquals(400, unauthorized.status)
            assertContains(unauthorized.text, "not authorized")

            assertEquals(200, request(base, "POST", "/api/layout-inspector", "serial=device-1").status)
            assertEquals(409, request(base, "POST", "/api/layout-inspector", "serial=device-1").status)

            repeat(150) {
                val status = request(base, "GET", "/api/layout-inspector-status")
                assertEquals(200, status.status)
                if ("\"done\":true" in status.text) {
                    val report = request(base, "GET", "/api/layout-inspector-report")
                    assertEquals(200, report.status)
                    assertContains(report.text, "app:id/title")
                    assertContains(report.text, "data:image/png;base64,")
                    return@withApp
                }
                if ("\"error\":null" !in status.text) fail(status.text)
                Thread.sleep(20)
            }
            fail("Layout Inspector capture did not complete")
        }
    }

    private fun withApp(block: (String) -> Unit) {
        val dir = Files.createTempDirectory("layout-inspector-api").toFile()
        val png = File(dir, "screen.png")
        ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), "png", output)
            png.writeBytes(output.toByteArray())
        }
        val pngPath = png.absolutePath.replace("'", "'\\''")
        val adbFile = File(dir, "adb").apply {
            writeText("""#!/bin/sh
if [ "${'$'}1" = "devices" ]; then
  printf 'List of devices attached\ndevice-1 device model:Test_Box\n'
  exit 0
fi
if [ "${'$'}3" = "shell" ]; then
  case "${'$'}4" in
    *uiautomator*)
      sleep 1
      printf '%s' '<hierarchy rotation="0"><node text="Title" resource-id="app:id/title" class="android.widget.TextView" package="app" enabled="true" clickable="false" bounds="[10,20][200,60]"/></hierarchy>'
      ;;
    *) exit 0 ;;
  esac
  exit 0
fi
if [ "${'$'}3" = "pull" ]; then
  cp '$pngPath' "${'$'}5"
  exit 0
fi
exit 0
""")
        }
        assertTrue(adbFile.setExecutable(true))
        val app = WebApp(
            port = 0,
            adb = Adb(adbFile.absolutePath),
            bindHost = "127.0.0.1",
            releaseGuardRoot = File(dir, "release-guard"),
            playerDebuggerRoot = File(dir, "player-debugger"),
            layoutInspectorRoot = File(dir, "reports")
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
        connection.readTimeout = 8_000
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        connection.disconnect()
        return Response(status, bytes.toString(Charsets.UTF_8))
    }

    private data class Response(val status: Int, val text: String)
}
