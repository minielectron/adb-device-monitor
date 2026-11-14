package devicemonitor

import devicemonitor.common.Log
import devicemonitor.data.adb.Adb
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the adb wrapper against a tiny fake `adb` shell script (no device needed). */
class AdbAndLogTest {

    private fun fakeAdb(body: String): Adb {
        val f = Files.createTempFile("fake-adb", ".sh").toFile()
        f.writeText("#!/bin/bash\n$body\n")
        f.setExecutable(true)
        f.deleteOnExit()
        return Adb(f.absolutePath)
    }

    @Test fun parsesDeviceList() {
        val adb = fakeAdb("""
            echo "List of devices attached"
            echo "FAKE0001   device product:fake model:FakeSTB transport_id:1"
            echo "10.0.0.5:5555   offline"
        """.trimIndent())
        val d = adb.devices()
        assertEquals(listOf("FAKE0001", "10.0.0.5:5555"), d.map { it.serial })
        assertEquals("device", d[0].state)
        assertEquals("offline", d[1].state)
        assertTrue("model:FakeSTB" in d[0].description)
    }

    @Test fun hungCommandTimesOutInsteadOfBlockingForever() {
        val adb = fakeAdb("sleep 30")
        val t0 = System.nanoTime()
        assertFailsWith<IllegalStateException> { adb.shell("X", "anything", 1) }
        assertTrue((System.nanoTime() - t0) / 1e9 < 6, "timeout must bound the call")
    }

    @Test fun abortGateIsScopedToTheStoppedRunsThreads() {
        val adb = fakeAdb("echo hello")
        var fromAborted = "?"
        val worker = Thread { fromAborted = adb.shell("X", "cmd") }
        adb.beginAbort(listOf(worker))
        worker.start(); worker.join(5000)
        assertEquals("", fromAborted, "a stopping run's thread must not launch adb")
        assertEquals("hello", adb.shell("X", "cmd"), "other threads (device list, snapshots) keep working")
        adb.endAbort()
        var after = "?"
        Thread { after = adb.shell("X", "cmd") }.apply { start(); join(5000) }
        assertEquals("hello", after)
    }

    @Test fun stopKillsTheInFlightCommandOfThatThread() {
        val adb = fakeAdb("sleep 30")
        var error: Throwable? = null
        val worker = Thread { try { adb.shell("X", "slow", 25) } catch (t: Throwable) { error = t } }
        worker.start()
        Thread.sleep(500)
        val t0 = System.nanoTime()
        adb.beginAbort(listOf(worker))
        worker.join(5000)
        assertTrue(!worker.isAlive && (System.nanoTime() - t0) / 1e9 < 4, "Stop must unblock the thread quickly")
        assertEquals(null, error)
    }

    @Test fun runLogReceivesLinesOnlyWhileAttached() {
        val f = File.createTempFile("run", ".log").apply { deleteOnExit() }
        Log.attachRun(f)
        Log.info("inside-run")
        Log.debug("debug-goes-to-file")
        Log.detachRun()
        Log.info("after-run")
        val text = f.readText()
        assertTrue("inside-run" in text && "debug-goes-to-file" in text)
        assertTrue("after-run" !in text)
        assertTrue(Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} INFO  \[.+] inside-run""", RegexOption.MULTILINE).containsMatchIn(text))
    }

    @Test fun threadDumpListsThisThread() {
        assertTrue(Thread.currentThread().name in Log.threadDump("test"))
    }
}
