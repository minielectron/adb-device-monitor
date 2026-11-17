package devicemonitor

import devicemonitor.data.device.LayoutInspectorCapture
import devicemonitor.data.device.LayoutInspectorSnapshot
import devicemonitor.presentation.report.LayoutInspectorReport
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutInspectorTest {
    @Test fun parsesNestedElementsBoundsStatesAndProtectsPasswords() {
        val hierarchy = LayoutInspectorCapture.parse("""
            <?xml version="1.0" encoding="UTF-8"?>
            <hierarchy rotation="1">
              <node index="0" text="Welcome" resource-id="app:id/root" class="android.widget.FrameLayout"
                    package="app" content-desc="Root" clickable="false" enabled="true" focusable="false"
                    focused="false" scrollable="false" selected="false" checked="false" password="false"
                    bounds="[0,0][1920,1080]">
                <node index="1" text="top-secret" resource-id="app:id/password" class="android.widget.EditText"
                      package="app" content-desc="account secret" clickable="true" enabled="true" focusable="true"
                      focused="true" scrollable="false" selected="false" checked="false" password="true"
                      bounds="[100,200][500,260]" />
              </node>
            </hierarchy>
        """.trimIndent())

        assertEquals(1, hierarchy.rotation)
        assertEquals(2, hierarchy.elements.size)
        assertEquals(0, hierarchy.elements[0].depth)
        assertEquals(1, hierarchy.elements[1].depth)
        with(hierarchy.elements[1]) {
            assertEquals("EditText", type)
            assertEquals("app:id/password", resourceId)
            assertEquals("Protected", text)
            assertEquals("Protected", contentDescription)
            assertEquals(400, bounds.width)
            assertEquals(60, bounds.height)
            assertEquals(300, bounds.centerX)
            assertEquals(230, bounds.centerY)
            assertTrue(clickable)
            assertTrue(focusable)
            assertTrue(focused)
            assertTrue(password)
        }
    }

    @Test fun skipsMalformedBoundsCapsNodesAndRejectsXxe() {
        val xml = """<hierarchy rotation="0">
            <node class="bad" bounds="not-bounds"/>
            <node class="one" bounds="[0,0][1,1]"/>
            <node class="two" bounds="[1,1][2,2]"/>
        </hierarchy>"""
        val hierarchy = LayoutInspectorCapture.parse(xml, maxNodes = 1)
        assertEquals(1, hierarchy.elements.size)
        assertTrue(hierarchy.truncated)
        assertEquals("one", hierarchy.elements.single().className)

        val malicious = """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><hierarchy><node text="&e;" bounds="[0,0][1,1]"/></hierarchy>"""
        assertFails { LayoutInspectorCapture.parse(malicious) }
    }

    @Test fun reportIsInteractiveSelfContainedAndDoesNotLeakPasswordText() {
        val hierarchy = LayoutInspectorCapture.parse("""
            <hierarchy rotation="0"><node text="secret-value" content-desc="secret-label" password="true"
              resource-id="app:id/pin" class="android.widget.EditText" package="app" enabled="true"
              clickable="true" focusable="true" bounds="[10,20][110,70]"/></hierarchy>
        """.trimIndent())
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().use { output -> ImageIO.write(image, "png", output); output.toByteArray() }
        val html = LayoutInspectorReport.html(
            LayoutInspectorSnapshot("device-1", Instant.parse("2026-11-17T12:00:00Z"), 120, 80, hierarchy, bytes)
        )

        assertContains(html, "data:image/png;base64,")
        assertContains(html, "Layout Inspector")
        assertContains(html, "app:id/pin")
        assertContains(html, "Protected")
        assertContains(html, "/api/layout-inspector-status")
        assertContains(html, "location.protocol==='file:'")
        assertContains(html, "connect-src 'self'")
        assertFalse("secret-value" in html)
        assertFalse("secret-label" in html)
        assertFalse(Regex("(?:src|href)=\\\"https?://", RegexOption.IGNORE_CASE).containsMatchIn(html))
    }
}
