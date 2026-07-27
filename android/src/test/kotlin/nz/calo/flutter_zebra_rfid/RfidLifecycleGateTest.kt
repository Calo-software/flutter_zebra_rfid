package nz.calo.flutter_zebra_rfid

import nz.calo.flutter_zebra_rfid.capture.RfidLifecycleCompletion
import nz.calo.flutter_zebra_rfid.capture.RfidLifecycleGate
import nz.calo.flutter_zebra_rfid.capture.RfidLifecycleRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RfidLifecycleGateTest {
    @Test
    fun invalidatedLateSuccessMustBeTerminatedBeforeRetryStarts() {
        val gate = RfidLifecycleGate()
        val first = gate.request("RFD40") as RfidLifecycleRequest.Start

        gate.invalidate(first.generation)
        assertTrue(gate.request("RFD40") is RfidLifecycleRequest.Joined)
        assertEquals(
            RfidLifecycleCompletion.TERMINATE_STALE_SESSION,
            gate.completeSuccess(first.generation, "RFD40"),
        )
        assertTrue(gate.request("RFD40") is RfidLifecycleRequest.Joined)

        gate.completeTermination(first.generation)

        val retry = gate.request("RFD40") as RfidLifecycleRequest.Start
        assertTrue(retry.generation > first.generation)
    }

    @Test
    fun timeoutDoesNotInvalidateAValidLateSuccess() {
        val gate = RfidLifecycleGate()
        val request = gate.request("RFD40") as RfidLifecycleRequest.Start

        gate.recordTimeout(request.generation)

        assertTrue(gate.request("RFD40") is RfidLifecycleRequest.Joined)
        assertEquals(
            RfidLifecycleCompletion.ADOPT_SESSION,
            gate.completeSuccess(request.generation, "RFD40"),
        )
    }

    @Test
    fun aCompletionForAnotherCaptureDeviceCannotReplaceTheSelection() {
        val gate = RfidLifecycleGate()
        val request = gate.request("RFD40") as RfidLifecycleRequest.Start

        gate.select("RFD90")

        assertEquals(
            RfidLifecycleCompletion.TERMINATE_STALE_SESSION,
            gate.completeSuccess(request.generation, "RFD40"),
        )
    }
}
