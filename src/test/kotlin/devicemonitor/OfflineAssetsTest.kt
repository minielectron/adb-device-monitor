package devicemonitor

import devicemonitor.presentation.report.Capabilities
import devicemonitor.presentation.web.COMPARE_PAGE
import devicemonitor.presentation.web.DASHBOARD_PAGE
import devicemonitor.presentation.web.HOME_PAGE
import devicemonitor.presentation.web.OFFLINE_WEB_ASSETS
import devicemonitor.presentation.web.PAGE_CSP
import devicemonitor.presentation.web.PLAYER_DEBUGGER_PAGE
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
        assertFalse(remoteAsset.containsMatchIn(PLAYER_DEBUGGER_PAGE))
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
        assertContains(RELEASE_GUARD_PAGE, "['launchDeepLink','Launch deep link']")
        assertContains(RELEASE_GUARD_PAGE, "'Deep link URI'")
        assertContains(RELEASE_GUARD_PAGE, "myapp://content/123?source=release-guard")
        assertContains(RELEASE_GUARD_PAGE, "Open full logcat · attempt")
        assertContains(RELEASE_GUARD_PAGE, "Open complete scenario log")
        assertContains(RELEASE_GUARD_PAGE, "var out={SHELL:true,LOGCAT:true}")
        assertContains(RELEASE_GUARD_PAGE, "function insertStep(index)")
        assertContains(RELEASE_GUARD_PAGE, "if(!hadDraft)newScenario()")
        assertContains(RELEASE_GUARD_PAGE, "Add first step")
        assertContains(RELEASE_GUARD_PAGE, "Add step above step")
        assertContains(RELEASE_GUARD_PAGE, "Add step below step")
        assertFalse("onclick=\"addStep()\"" in RELEASE_GUARD_PAGE)
        assertContains(RELEASE_GUARD_PAGE, "No performance budgets · evidence-only run")
        assertContains(RELEASE_GUARD_PAGE, "not_evaluated:'Evidence only'")
        assertContains(RELEASE_GUARD_PAGE, "EVIDENCE_ONLY")
        assertFalse("window.open(" in RELEASE_GUARD_PAGE)
    }

    @Test fun playerDebuggerUsesOfflineApisSavedDraftAndRequestedDefaults() {
        assertContains(HOME_PAGE, "href=\"/player-debugger\"")
        assertContains(PLAYER_DEBUGGER_PAGE, "aps.playerDebugger.draft.v1")
        assertContains(PLAYER_DEBUGGER_PAGE, "beforeunload',saveDraft")
        assertContains(PLAYER_DEBUGGER_PAGE, "/api/player/start")
        assertContains(PLAYER_DEBUGGER_PAGE, "/api/player/status")
        assertContains(PLAYER_DEBUGGER_PAGE, "/api/player/timeline")
        assertContains(PLAYER_DEBUGGER_PAGE, "/api/player/report")
        assertContains(PLAYER_DEBUGGER_PAGE, "id=\"playerReportViewer\"")
        assertContains(PLAYER_DEBUGGER_PAGE, "id=\"pdHandoffMap\"")
        listOf("cpuUtil", "perCoreCpu", "topProcessCpu", "memoryUsed", "psiPressure", "topProcessMemory").forEach { id ->
            assertTrue(Regex("""id="$id"[^>]*\bchecked\b""").containsMatchIn(PLAYER_DEBUGGER_PAGE), "$id should be checked by default")
        }
        assertContains(PLAYER_DEBUGGER_PAGE, "Reported")
        assertContains(PLAYER_DEBUGGER_PAGE, "Measured")
        assertContains(PLAYER_DEBUGGER_PAGE, "Inferred")
        assertContains(PLAYER_DEBUGGER_PAGE, "Adapter unknown")
    }

    @Test fun homeAndPlayerDebuggerShareBalancedFirstUseMetricDefaults() {
        listOf("m_cpu", "m_percore", "m_topprocs", "m_mempct", "m_psi", "m_topmem").forEach { id ->
            assertTrue(Regex("""id="$id"[^>]*\bchecked\b""").containsMatchIn(HOME_PAGE), "$id should be checked by default")
        }
        assertContains(HOME_PAGE, "id=\"m_pss\"><span>")
        assertFalse(HOME_PAGE.contains("id=\"m_pss\" checked"))

        listOf("cpuUtil", "perCoreCpu", "topProcessCpu", "memoryUsed", "psiPressure", "topProcessMemory").forEach { id ->
            assertTrue(Regex("""id="$id"[^>]*\bchecked\b""").containsMatchIn(PLAYER_DEBUGGER_PAGE), "$id should be checked by default")
        }
    }

    @Test fun rootRequiredMetricsAreMarkedGatedAndDefensivelySerialized() {
        listOf("m_psi", "m_thermal").forEach { id ->
            assertTrue(Regex("""id="$id"[^>]*data-root-required="true"""").containsMatchIn(HOME_PAGE))
            assertContains(HOME_PAGE, "rootMetricEnabled('$id')")
        }
        listOf("m_topprocs", "m_maincpu", "m_mediacpu").forEach { id ->
            assertFalse(Regex("""id="$id"[^>]*data-root-required="true"""").containsMatchIn(HOME_PAGE))
        }
        assertContains(HOME_PAGE, "var ROOT_METRIC_IDS = ['m_psi', 'm_thermal']")
        assertContains(HOME_PAGE, "selectedDevice.root === 'root'")
        assertContains(HOME_PAGE, "input.dataset.rootPreference")
        assertContains(HOME_PAGE, "aria-label=\"Requires rooted ADB device\"")

        assertContains(PLAYER_DEBUGGER_PAGE, "id=\"psiPressure\" type=\"checkbox\" data-root-required=\"true\"")
        assertFalse(Regex("""id="topProcessCpu"[^>]*data-root-required="true"""").containsMatchIn(PLAYER_DEBUGGER_PAGE))
        assertContains(PLAYER_DEBUGGER_PAGE, "device.root==='root'")
        assertContains(PLAYER_DEBUGGER_PAGE, "id==='psiPressure'?rootSignalEnabled(id)")
        assertContains(PLAYER_DEBUGGER_PAGE, "input.dataset.rootPreference")
    }

    @Test fun dashboardKeepsTunnelModeWithoutProjectSpecificLifecycle() {
        assertContains(DASHBOARD_PAGE, "id=\"c-tunnel\"")
        assertContains(DASHBOARD_PAGE, "Negotiated tunnel state")
        assertFalse("mk-player-lifecycle" in DASHBOARD_PAGE)
        assertFalse("id=\"c-video\"" in DASHBOARD_PAGE)
        assertFalse("Player state" in DASHBOARD_PAGE)
    }

    @Test fun diagnosticReportsOpenInTheStatePreservingViewer() {
        assertContains(HOME_PAGE, "id=\"reportViewer\"")
        assertContains(HOME_PAGE, "'/api/layout-inspector-report': 'Layout Inspector'")
        assertContains(HOME_PAGE, "Object.prototype.hasOwnProperty.call(reportPaths, path)")
        assertContains(HOME_PAGE, "openReport('/api/snapshot-report', btn)")
        assertContains(HOME_PAGE, "openReport('/api/procrank-report', btn)")
        assertContains(HOME_PAGE, "openReport('/api/layout-inspector-report', btn)")
        assertContains(HOME_PAGE, "id=\"layoutBtn\"")
        assertContains(HOME_PAGE, "/api/layout-inspector-status")
        assertFalse(Regex("""window\.open\(['\"]/api/(?:snapshot|procrank)-report""").containsMatchIn(HOME_PAGE))
    }

    @Test fun homeHandlesNativeDiagnosticsDownloadConfirmation() {
        assertContains(HOME_PAGE, "id=\"downloadToast\"")
        assertContains(HOME_PAGE, "aria-live=\"polite\"")
        assertContains(HOME_PAGE, "studio-download-finished")
        assertContains(HOME_PAGE, "Diagnostics saved to Downloads")
        assertContains(HOME_PAGE, "Diagnostics download failed")
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
