package devicemonitor

import devicemonitor.data.adb.Adb
import devicemonitor.data.device.CapabilityProbe
import devicemonitor.domain.releaseguard.*
import java.nio.file.Files
import kotlin.test.*

class CapabilityProbeTest {
    @Test fun probesCapabilitiesWithoutModelSpecificRulesAndCachesSuccess() {
        val dir = Files.createTempDirectory("release-guard-fake-adb")
        val calls = dir.resolve("calls.txt")
        val adbFile = dir.resolve("adb").toFile()
        adbFile.writeText(
            """#!/bin/sh
            echo "${'$'}*" >> "${calls}"
            case "${'$'}*" in
              *"getprop ro.product.model"*)
                echo "model=QA Box"
                echo "android=14"
                echo "firmware=vendor/device/build:14/test"
                echo "characteristics=tv,nosdcard"
                ;;
              *"echo release_guard_ready"*) echo "release_guard_ready" ;;
              *"for t in cmd"*)
                echo "package=1"; echo "uiautomator=1"; echo "input=1"; echo "perfetto=1"
                echo "procrank=0"; echo "screencap=1"; echo "logcat=1"
                ;;
              *"dumpsys gfxinfo"*)
                echo "gfxinfo=1"; echo "psi=1"; echo "thermal=0"; echo "battery=1"; echo "playback=1"
                echo "codec=1"; echo "audio=1"; echo "surface=1"; echo "root=0"
                ;;
            esac
            """.trimIndent()
        )
        assertTrue(adbFile.setExecutable(true))
        val probe = CapabilityProbe(Adb(adbFile.absolutePath))

        val first = probe.probe("192.168.1.10:5555")
        val callCount = calls.toFile().readLines().size
        val cached = probe.probe("192.168.1.10:5555")
        assertEquals(callCount, calls.toFile().readLines().size)
        assertEquals(first, cached)
        assertEquals(FormFactor.TV, first.formFactor)
        assertEquals(Transport.TCP_IP, first.transport)
        assertEquals("QA Box", first.properties["model"])
        assertTrue(DeviceCapability.PERFETTO in first.capabilities)
        assertTrue(DeviceCapability.PLAYBACK_DIAGNOSTICS in first.capabilities)
        assertTrue(DeviceCapability.MEDIA_CODEC_DIAGNOSTICS in first.capabilities)
        assertTrue(DeviceCapability.AUDIO_DIAGNOSTICS in first.capabilities)
        assertTrue(DeviceCapability.SURFACE_DIAGNOSTICS in first.capabilities)
        assertFalse(DeviceCapability.PROCRANK in first.capabilities)
        assertFalse(DeviceCapability.ROOT in first.capabilities)
    }

    @Test fun parsesFormFactorAndTransportFromCapabilitiesNotManufacturer() {
        assertEquals(FormFactor.EMULATOR, CapabilityProbe.formFactor(mapOf("characteristics" to "phone,emulator")))
        assertEquals(FormFactor.TABLET, CapabilityProbe.formFactor(mapOf("characteristics" to "tablet")))
        assertEquals(FormFactor.PHONE, CapabilityProbe.formFactor(mapOf("characteristics" to "phone")))
        assertNull(CapabilityProbe.formFactor(mapOf("model" to "Some Television")))
        assertEquals(Transport.EMULATOR, CapabilityProbe.transport("emulator-5554"))
        assertEquals(Transport.USB, CapabilityProbe.transport("R58M123"))
    }
}
