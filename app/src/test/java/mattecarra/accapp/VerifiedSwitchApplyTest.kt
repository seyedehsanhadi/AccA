package mattecarra.accapp

import mattecarra.accapp.acc.VerifiedSwitch
import mattecarra.accapp.acc._interface.AccInterface
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class VerifiedSwitchApplyTest {
    private val artifact = listOf("schema=1", "charging_switch=battery/input_suspend 0 1", "class=cut", "conf=verified", "device=test", "soc=testsoc", "ok=1")
    @Test fun unprovenNativeLimitRequiresRescan() {
        assertEquals(VerifiedSwitch.ApplyMode.LEVEL_RERUN, VerifiedSwitch.applyMode("level", "needs-test"))
        assertEquals(VerifiedSwitch.ApplyMode.PIN_DIRECT, VerifiedSwitch.applyMode("level", "verified"))
        assertEquals(VerifiedSwitch.ApplyMode.LIVE_TEST, VerifiedSwitch.applyMode("cut", "needs-test"))
    }
    @Test fun completeArtifactAccepted() {
        assertTrue(VerifiedSwitch.parseArtifact(artifact, "test", "testsoc") is VerifiedSwitch.Verified)
    }
    @Test fun partialAndDuplicateArtifactsRejected() {
        assertEquals(VerifiedSwitch.None, VerifiedSwitch.parseArtifact(artifact + "class=level", "test", "testsoc"))
        assertEquals(VerifiedSwitch.None, VerifiedSwitch.parseArtifact(artifact + "note=after-sentinel", "test", "testsoc"))
        assertEquals(VerifiedSwitch.None, VerifiedSwitch.parseArtifact(artifact.dropLast(1), "test", "testsoc"))
        assertEquals(VerifiedSwitch.None, VerifiedSwitch.parseArtifact(listOf("conf=needs-test") + artifact, "test", "testsoc"))
    }
    @Test fun preconditionsNeedCompleteArtifact() {
        assertEquals(VerifiedSwitch.None, VerifiedSwitch.parseArtifact(listOf("result=precondition"), "test", "testsoc"))
        assertTrue(VerifiedSwitch.parseArtifact(listOf("schema=1", "result=precondition", "reason=hot", "ok=0"), "test", "testsoc") is VerifiedSwitch.Precondition)
    }
    @Test fun fingerprintCannotSilentlyGrantVerification() {
        assertEquals(VerifiedSwitch.DeviceMismatch, VerifiedSwitch.parseArtifact(artifact, "another", "testsoc"))
        assertTrue(VerifiedSwitch.parseArtifact(artifact, "", "testsoc") is VerifiedSwitch.NeedsTest)
    }
    @Test fun allGroupedPathsResolveIncludingRelativeOnes() {
        assertEquals(listOf("/sys/class/power_supply/battery/a", "/sys/test/b"), VerifiedSwitch.switchPaths("battery/a 0 1\t/sys/test/b on off"))
        assertNull(VerifiedSwitch.switchPaths("battery/a 0"))
        assertNull(VerifiedSwitch.switchPaths("../a 0 1"))
    }
    private fun handler(written: Boolean, running: Boolean, calls: MutableList<String>, restarted: Boolean = true): AccInterface =
        Proxy.newProxyInstance(AccInterface::class.java.classLoader, arrayOf(AccInterface::class.java)) { _, method, _ ->
            calls.add(method.name)
            when (method.name) {
                "updateAccChargingSwitch" -> written
                "accRestartDaemon" -> restarted
                "isAccdRunning" -> running
                else -> error(method.name)
            }
        } as AccInterface
    @Test fun failedWriteDoesNotRestart() = runBlocking {
        val calls = mutableListOf<String>()
        assertFalse(VerifiedSwitch.pinAndRestart(handler(false, false, calls), "battery/a 0 1"))
        assertEquals(listOf("updateAccChargingSwitch"), calls)
    }
    @Test fun stoppedDaemonNeverReportedAsApplied() = runBlocking {
        val calls = mutableListOf<String>()
        assertFalse(VerifiedSwitch.pinAndRestart(handler(true, false, calls), "battery/a 0 1"))
        assertEquals(2, calls.count { it == "isAccdRunning" })
    }
    @Test fun failedRestartCannotConfirmAnOldRunningDaemon() = runBlocking {
        assertFalse(VerifiedSwitch.pinAndRestart(handler(true, true, mutableListOf(), false), "battery/a 0 1"))
    }
    @Test fun runningDaemonConfirmsApplied() = runBlocking {
        assertTrue(VerifiedSwitch.pinAndRestart(handler(true, true, mutableListOf()), "battery/a 0 1"))
    }
}