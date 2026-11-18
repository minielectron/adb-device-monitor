package devicemonitor

import devicemonitor.data.adb.Adb
import devicemonitor.data.device.AdbDeviceDriver
import devicemonitor.domain.releaseguard.ScenarioStep
import devicemonitor.domain.releaseguard.UiSelector
import java.nio.file.Files
import kotlin.test.*

class AdbDeviceDriverTest {
    @Test fun prefersOverrideDisplaySize() {
        assertEquals(1280 to 720, AdbDeviceDriver.parseDisplaySize("Physical size: 1920x1080\nOverride size: 1280x720"))
        assertEquals(1080 to 2400, AdbDeviceDriver.parseDisplaySize("Physical size: 1080x2400"))
        assertNull(AdbDeviceDriver.parseDisplaySize("unknown"))
    }

    @Test fun findsElementsByStableSelectorPriority() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <hierarchy rotation="0">
              <node resource-id="com.example:id/browse" text="Browse" content-desc="Open browse" bounds="[10,20][110,220]" />
              <node resource-id="com.example:id/other" text="Browse" content-desc="Other" bounds="[200,20][300,220]" />
            </hierarchy>""".trimIndent()
        val byId = AdbDeviceDriver.findBounds(xml, UiSelector(resourceId = "browse", text = "Browse"))
        assertEquals(60, byId?.centerX)
        assertEquals(120, byId?.centerY)
        val byDescription = AdbDeviceDriver.findBounds(xml, UiSelector(contentDescription = "Other"))
        assertEquals(250, byDescription?.centerX)
        assertFailsWith<IllegalArgumentException> {
            AdbDeviceDriver.findBounds(xml, UiSelector(text = "Browse"))
        }
    }

    @Test fun rejectsDoctypeBeforeParsingUiHierarchy() {
        val malicious = """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><hierarchy><node text="&e;" bounds="[0,0][1,1]"/></hierarchy>"""
        assertFails { AdbDeviceDriver.findBounds(malicious, UiSelector(text = "anything")) }
    }

    @Test fun normalizesCoordinatesAndQuotesTextAsOneDeviceShellCommand() {
        val dir = Files.createTempDirectory("release-guard-driver")
        val calls = dir.resolve("calls.txt")
        val adbFile = dir.resolve("adb").toFile()
        adbFile.writeText(
            """#!/bin/sh
            echo "${'$'}#|${'$'}*" >> "${calls}"
            case "${'$'}*" in
              *"wm size"*) echo "Physical size: 1920x1080" ;;
            esac
            """.trimIndent()
        )
        assertTrue(adbFile.setExecutable(true))
        val driver = AdbDeviceDriver(Adb(adbFile.absolutePath), "device-1")
        val artifacts = dir.resolve("artifacts").toFile()

        driver.perform(ScenarioStep.TapAt(.5, .5), "com.example.app", artifacts)
        driver.perform(ScenarioStep.EnterText("hello; rm -rf /"), "com.example.app", artifacts)

        val commands = calls.toFile().readLines()
        assertTrue(commands.any { "input tap 960 540" in it })
        assertTrue(commands.any { "input text 'hello;%srm%s-rf%s/'" in it })
        assertTrue(commands.filter { "input text" in it }.all { it.startsWith("4|") })
    }
}
