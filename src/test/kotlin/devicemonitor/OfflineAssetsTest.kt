package devicemonitor

import devicemonitor.presentation.report.Capabilities
import devicemonitor.presentation.web.COMPARE_PAGE
import devicemonitor.presentation.web.DASHBOARD_PAGE
import devicemonitor.presentation.web.HOME_PAGE
import devicemonitor.presentation.web.OFFLINE_WEB_ASSETS
import devicemonitor.presentation.web.PAGE_CSP
import devicemonitor.presentation.web.RELEASE_GUARD_PAGE
import devicemonitor.presentation.web.resolveOfflineAsset
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineAssetsTest {
    private val remoteAsset = Regex("""(?:src|href)=["']https?://""", RegexOption.IGNORE_CASE)

    @Test fun interactivePagesUseBundledLibrariesOnly() {
        assertContains(DASHBOARD_PAGE, "src=\"/assets/chart.umd.js\"")
        assertContains(COMPARE_PAGE, "src=\"/assets/chart.umd.js\"")
        assertContains(COMPARE_PAGE, "src=\"/assets/jspdf.umd.min.js\"")
        assertFalse(remoteAsset.containsMatchIn(DASHBOARD_PAGE))
        assertFalse(remoteAsset.containsMatchIn(COMPARE_PAGE))
        assertFalse(remoteAsset.containsMatchIn(RELEASE_GUARD_PAGE))
        OFFLINE_WEB_ASSETS.values.forEach { (resource, _) ->
            assertNotNull(javaClass.classLoader.getResource(resource), "missing bundled resource: $resource")
        }
    }

    @Test fun releaseGuardIsOfflineAndHomeNavigationPreservesDrafts() {
        assertContains(HOME_PAGE, "href=\"/release-guard\"")
        assertContains(HOME_PAGE, "aps.home.draft.v1")
        assertContains(HOME_PAGE, "beforeunload', saveHomeDraft")
        assertContains(RELEASE_GUARD_PAGE, "aps.releaseGuard.draft.v1")
        assertContains(RELEASE_GUARD_PAGE, "/api/release/start")
        assertContains(RELEASE_GUARD_PAGE, "/api/release/status")
        assertContains(RELEASE_GUARD_PAGE, "/api/release/baseline")
        assertFalse("window.open(" in RELEASE_GUARD_PAGE)
    }

    @Test fun diagnosticReportsOpenInTheStatePreservingViewer() {
        assertContains(HOME_PAGE, "id=\"reportViewer\"")
        assertContains(HOME_PAGE, "var reportPaths = { '/api/snapshot-report': 'Device Snapshot', '/api/procrank-report': 'Memory Snapshot' }")
        assertContains(HOME_PAGE, "Object.prototype.hasOwnProperty.call(reportPaths, path)")
        assertContains(HOME_PAGE, "openReport('/api/snapshot-report', btn)")
        assertContains(HOME_PAGE, "openReport('/api/procrank-report', btn)")
        assertFalse(Regex("""window\.open\(['\"]/api/(?:snapshot|procrank)-report""").containsMatchIn(HOME_PAGE))
    }

    @Test fun assetAllowlistRejectsTraversalAndPrefixMatches() {
        assertNotNull(resolveOfflineAsset("/assets/chart.umd.js"))
        assertNotNull(resolveOfflineAsset("/assets/jspdf.umd.min.js"))
        assertNull(resolveOfflineAsset("/assets/../chart.umd.js"))
        assertNull(resolveOfflineAsset("/assets/chart.umd.js.map"))
        assertNull(resolveOfflineAsset("/assets/chart.umd.js/extra"))
        assertContains(PAGE_CSP, "default-src 'self'")
        assertContains(PAGE_CSP, "connect-src 'self'")
    }

    @Test fun generatedDiagnosticsAreStandaloneAndServerRendered() {
        val dir = Files.createTempDirectory("offline-diagnostics-test").toFile().apply { deleteOnExit() }
        val device = dir.resolve("device.html")
        val snapshot = Capabilities.Snapshot(
            summary = listOf("Model" to "Test Box", "Android" to "14"),
            memTotalMb = 2048.0,
            memFreeMb = 128.0,
            memAvailableMb = 512.0,
            androidMb = 600.0,
            thirdPartyMb = 250.0,
            launcherMb = 75.0,
            launcherPkg = "example.launcher",
            processes = listOf(Capabilities.ProcEntry("system_server", 90.0, "android"))
        )
        Capabilities.writeHtml(snapshot, "offline-test", device)
        val deviceHtml = device.readText()
        assertFalse(remoteAsset.containsMatchIn(deviceHtml))
        assertContains(deviceHtml, "<svg")
        assertFalse("<canvas" in deviceHtml)
        assertFalse("new Chart" in deviceHtml)

        val memory = dir.resolve("memory.html")
        val entries = listOf(Capabilities.ProcrankEntry(42, "system_server", 500.0, 120.0, 80.0, 60.0))
        Capabilities.writeProcrankHtml(
            Capabilities.ProcrankSnapshot(entries, "procrank", 80.0, 120.0, 2048.0, 128.0, 512.0),
            "offline-test",
            memory
        )
        val memoryHtml = memory.readText()
        assertFalse(remoteAsset.containsMatchIn(memoryHtml))
        assertContains(memoryHtml, "<svg")
        assertTrue("system_server" in memoryHtml)
    }
}
