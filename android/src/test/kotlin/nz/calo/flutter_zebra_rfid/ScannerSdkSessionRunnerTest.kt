package nz.calo.flutter_zebra_rfid

import com.zebra.scannercontrol.DCSSDKDefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import nz.calo.flutter_zebra_rfid.barcode.ScannerSdkSessionOperation
import nz.calo.flutter_zebra_rfid.barcode.ScannerSdkSessionRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ScannerSdkSessionRunnerTest {
    @Test
    fun blockedEstablishCallDoesNotBlockCallerAndCompletesOnMainPoster() {
        val mainTasks = LinkedBlockingQueue<() -> Unit>()
        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val callerThread = Thread.currentThread().id
        var operationThread: Long? = null
        var callbackThread: Long? = null
        var result: Result<DCSSDKDefs.DCSSDK_RESULT>? = null
        val runner = ScannerSdkSessionRunner(postToMain = mainTasks::add)

        runner.run(
            ScannerSdkSessionOperation.ESTABLISH,
            scannerId = 42,
            sdkCall = {
                operationThread = Thread.currentThread().id
                operationStarted.countDown()
                releaseOperation.await(5, TimeUnit.SECONDS)
                DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS
            },
        ) {
            callbackThread = Thread.currentThread().id
            result = it
        }

        assertTrue(operationStarted.await(1, TimeUnit.SECONDS))
        assertNull(result)
        assertNotEquals(callerThread, operationThread)

        releaseOperation.countDown()
        mainTasks.poll(1, TimeUnit.SECONDS).invoke()

        assertEquals(callerThread, callbackThread)
        assertEquals(
            DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS,
            result!!.getOrThrow(),
        )
        runner.dispose()
    }

    @Test
    fun duplicateEstablishRequestsShareOneSdkCall() {
        val mainTasks = LinkedBlockingQueue<() -> Unit>()
        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val sdkCalls = AtomicInteger()
        val completions = AtomicInteger()
        val runner = ScannerSdkSessionRunner(postToMain = mainTasks::add)
        val sdkCall = {
            sdkCalls.incrementAndGet()
            operationStarted.countDown()
            releaseOperation.await(5, TimeUnit.SECONDS)
            DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS
        }

        runner.run(ScannerSdkSessionOperation.ESTABLISH, 42, sdkCall) {
            completions.incrementAndGet()
        }
        assertTrue(operationStarted.await(1, TimeUnit.SECONDS))
        runner.run(ScannerSdkSessionOperation.ESTABLISH, 42, sdkCall) {
            completions.incrementAndGet()
        }

        assertEquals(1, sdkCalls.get())
        releaseOperation.countDown()
        mainTasks.poll(1, TimeUnit.SECONDS).invoke()

        assertEquals(2, completions.get())
        runner.dispose()
    }

    @Test
    fun conflictingRequestFailsWithAlreadyConnectingWithoutSecondSdkCall() {
        val mainTasks = LinkedBlockingQueue<() -> Unit>()
        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val sdkCalls = AtomicInteger()
        var conflict: Result<DCSSDKDefs.DCSSDK_RESULT>? = null
        val runner = ScannerSdkSessionRunner(postToMain = mainTasks::add)

        runner.run(ScannerSdkSessionOperation.ESTABLISH, 42, {
            sdkCalls.incrementAndGet()
            operationStarted.countDown()
            releaseOperation.await(5, TimeUnit.SECONDS)
            DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS
        }) {}
        assertTrue(operationStarted.await(1, TimeUnit.SECONDS))

        runner.run(ScannerSdkSessionOperation.TERMINATE, 42, {
            sdkCalls.incrementAndGet()
            DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS
        }) {
            conflict = it
        }
        mainTasks.poll(1, TimeUnit.SECONDS).invoke()

        assertEquals(1, sdkCalls.get())
        assertTrue(conflict!!.exceptionOrNull()!!.message!!.contains("ALREADY_CONNECTING"))
        releaseOperation.countDown()
        mainTasks.poll(1, TimeUnit.SECONDS).invoke()
        runner.dispose()
    }

    @Test
    fun disposeSuppressesLateCompletion() {
        val mainTasks = LinkedBlockingQueue<() -> Unit>()
        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        var completed = false
        val runner = ScannerSdkSessionRunner(postToMain = mainTasks::add)

        runner.run(ScannerSdkSessionOperation.ESTABLISH, 42, {
            operationStarted.countDown()
            releaseOperation.await(5, TimeUnit.SECONDS)
            DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS
        }) {
            completed = true
        }
        assertTrue(operationStarted.await(1, TimeUnit.SECONDS))

        runner.dispose()
        releaseOperation.countDown()
        mainTasks.poll(1, TimeUnit.SECONDS)?.invoke()

        assertFalse(completed)
    }

    @Test
    fun terminateUsesBackgroundExecutor() {
        val mainTasks = LinkedBlockingQueue<() -> Unit>()
        val callerThread = Thread.currentThread().id
        var operationThread: Long? = null
        var completed = false
        val runner = ScannerSdkSessionRunner(postToMain = mainTasks::add)

        runner.run(ScannerSdkSessionOperation.TERMINATE, 42, {
            operationThread = Thread.currentThread().id
            DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS
        }) {
            completed = it.isSuccess
        }
        mainTasks.poll(1, TimeUnit.SECONDS).invoke()

        assertNotEquals(callerThread, operationThread)
        assertTrue(completed)
        runner.dispose()
    }

    @Test
    fun sdkExceptionIsDeliveredAsFailureAndRunnerAcceptsNextOperation() {
        val mainTasks = LinkedBlockingQueue<() -> Unit>()
        val completions = mutableListOf<Result<DCSSDKDefs.DCSSDK_RESULT>>()
        val runner = ScannerSdkSessionRunner(postToMain = mainTasks::add)

        runner.run(ScannerSdkSessionOperation.ESTABLISH, 42, {
            throw IllegalStateException("Bluetooth transport failed")
        }) {
            completions.add(it)
        }
        mainTasks.poll(1, TimeUnit.SECONDS).invoke()

        runner.run(ScannerSdkSessionOperation.ESTABLISH, 42, {
            DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS
        }) {
            completions.add(it)
        }
        mainTasks.poll(1, TimeUnit.SECONDS).invoke()

        assertEquals("Bluetooth transport failed", completions[0].exceptionOrNull()?.message)
        assertEquals(
            DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS,
            completions[1].getOrThrow(),
        )
        runner.dispose()
    }
}
