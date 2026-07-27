package nz.calo.flutter_zebra_rfid

import java.nio.file.Files
import java.util.concurrent.Executor
import nz.calo.flutter_zebra_rfid.capture.CaptureDiagnosticStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class CaptureDiagnosticStoreTest {
    @Test
    fun recordsOrderedPrivacySafeEvents() {
        val directory = Files.createTempDirectory("capture-diagnostics").toFile()
        var now = 1_000L
        val store = CaptureDiagnosticStore(
            directory = directory,
            nowMillis = { now },
            ioExecutor = Executor { it.run() },
        )

        store.record(
            category = "capture_device",
            operation = "selection",
            outcome = "started",
            details = mapOf(
                "terminal_id" to "terminal-22",
                "reader_serial" to "reader-40",
                "rfid" to "E2000017221101441890FFFF",
                "bin_id" to "10000001",
                "token" to "secret",
            ),
        )
        now += 1
        store.record(
            category = "datawedge",
            operation = "enumerate_scanners",
            outcome = "completed",
            details = mapOf("endpoint_status" to "WAITFORTRIGGER"),
        )

        val records = store.snapshot()

        assertEquals(listOf(1L, 2L), records.map { it.sequence })
        assertEquals("terminal-22", records.first().details["terminal_id"])
        assertEquals("reader-40", records.first().details["reader_serial"])
        assertFalse(records.first().details.containsKey("rfid"))
        assertFalse(records.first().details.containsKey("bin_id"))
        assertFalse(records.first().details.containsKey("token"))
        assertTrue(
            records.last().details["endpoint_status"] == "WAITFORTRIGGER",
        )
    }

    @Test
    fun retainsOnlyRecentBoundedEvents() {
        val directory = Files.createTempDirectory("capture-diagnostics").toFile()
        var now = 1_000L
        val store = CaptureDiagnosticStore(
            directory = directory,
            nowMillis = { now },
            ioExecutor = Executor { it.run() },
            maxEvents = 2,
            maxAgeMs = 100,
        )

        store.record("capture_device", "first", "completed", emptyMap())
        now += 50
        store.record("capture_device", "second", "completed", emptyMap())
        now += 50
        store.record("capture_device", "third", "completed", emptyMap())

        assertEquals(
            listOf("second", "third"),
            store.snapshot().map { it.operation },
        )

        now += 101

        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun reloadsAndClearsThePrivateDiagnosticFile() {
        val directory = Files.createTempDirectory("capture-diagnostics").toFile()
        val directExecutor = Executor { it.run() }
        val first = CaptureDiagnosticStore(
            directory = directory,
            nowMillis = { 1_000L },
            ioExecutor = directExecutor,
        )
        first.record(
            "datawedge",
            "scanner_status",
            "completed",
            mapOf("endpoint_status" to "WAITFORTRIGGER"),
        )

        val reloaded = CaptureDiagnosticStore(
            directory = directory,
            nowMillis = { 1_001L },
            ioExecutor = directExecutor,
        )

        assertEquals("scanner_status", reloaded.snapshot().single().operation)

        reloaded.clear()

        val cleared = CaptureDiagnosticStore(
            directory = directory,
            nowMillis = { 1_002L },
            ioExecutor = directExecutor,
        )
        assertTrue(cleared.snapshot().isEmpty())
    }

    @Test
    fun dropsOldestEventsToStayInsideTheByteLimit() {
        val directory = Files.createTempDirectory("capture-diagnostics").toFile()
        val store = CaptureDiagnosticStore(
            directory = directory,
            nowMillis = { 1_000L },
            ioExecutor = Executor { it.run() },
            maxBytes = 400,
        )

        repeat(5) { index ->
            store.record(
                "capture_device",
                "event-$index",
                "completed",
                mapOf("detail" to "x".repeat(120)),
            )
        }

        val records = store.snapshot()
        assertTrue(records.size < 5)
        assertEquals("event-4", records.last().operation)
    }
}
