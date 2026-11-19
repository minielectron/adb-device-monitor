package devicemonitor

import devicemonitor.data.storage.RunManifest
import devicemonitor.domain.releaseguard.ExecutionState
import devicemonitor.domain.releaseguard.ReleaseEvaluation
import devicemonitor.domain.releaseguard.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseGuardCliTest {
    @Test fun exitCodesAreStableAcrossVerdictsAndInfrastructureFailures() {
        assertEquals(RELEASE_GUARD_PASS, releaseGuardExitCode(manifest(ExecutionState.PASSED, Verdict.PASS)))
        assertEquals(RELEASE_GUARD_WARNING, releaseGuardExitCode(manifest(ExecutionState.WARNING, Verdict.WARNING)))
        assertEquals(
            RELEASE_GUARD_NOT_EVALUATED,
            releaseGuardExitCode(manifest(ExecutionState.EVIDENCE_ONLY, Verdict.NOT_EVALUATED))
        )
        assertEquals(RELEASE_GUARD_FAILED, releaseGuardExitCode(manifest(ExecutionState.FAILED, Verdict.FAIL)))
        assertEquals(
            RELEASE_GUARD_INVALID,
            releaseGuardExitCode(manifest(ExecutionState.FAILED, Verdict.FAIL, "Device is missing required capabilities: PSI"))
        )
        assertEquals(
            RELEASE_GUARD_INFRASTRUCTURE,
            releaseGuardExitCode(manifest(ExecutionState.FAILED, Verdict.FAIL, "adb disconnected"))
        )
        assertEquals(RELEASE_GUARD_INFRASTRUCTURE, releaseGuardExitCode(manifest(ExecutionState.CANCELLED, Verdict.CANCELLED)))
    }

    private fun manifest(state: ExecutionState, verdict: Verdict, error: String? = null) = RunManifest(
        runId = "run",
        projectId = "project",
        scenarioId = "scenario",
        scenarioRevision = 1,
        state = state,
        startedAt = "2026-11-16T00:00:00Z",
        updatedAt = "2026-11-16T00:01:00Z",
        evaluation = ReleaseEvaluation(verdict, emptyList()),
        error = error
    )
}
