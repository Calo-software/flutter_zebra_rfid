package nz.calo.flutter_zebra_rfid.barcode

import com.zebra.scannercontrol.DCSSDKDefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal enum class ScannerSdkSessionOperation {
    ESTABLISH,
    TERMINATE,
}

internal class ScannerSdkSessionRunner(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zebra-scanner-session").apply { isDaemon = true }
    },
    private val postToMain: ((() -> Unit) -> Unit),
) {
    private data class ActiveOperation(
        val generation: Long,
        val operation: ScannerSdkSessionOperation,
        val scannerId: Int,
        val callbacks: MutableList<(Result<DCSSDKDefs.DCSSDK_RESULT>) -> Unit>,
    )

    private val lock = Any()
    private var generation = 0L
    private var activeOperation: ActiveOperation? = null
    private var disposed = false

    fun run(
        operation: ScannerSdkSessionOperation,
        scannerId: Int,
        sdkCall: () -> DCSSDKDefs.DCSSDK_RESULT,
        onComplete: (Result<DCSSDKDefs.DCSSDK_RESULT>) -> Unit,
    ) {
        val operationToStart: ActiveOperation
        synchronized(lock) {
            if (disposed) {
                postFailure(
                    onComplete,
                    IllegalStateException("Barcode Scanner SDK session runner is disposed"),
                )
                return
            }

            val active = activeOperation
            if (active != null) {
                if (active.operation == operation && active.scannerId == scannerId) {
                    active.callbacks.add(onComplete)
                } else {
                    postFailure(
                        onComplete,
                        IllegalStateException(
                            "ALREADY_CONNECTING: Scanner SDK session operation is already active",
                        ),
                    )
                }
                return
            }

            operationToStart = ActiveOperation(
                generation = ++generation,
                operation = operation,
                scannerId = scannerId,
                callbacks = mutableListOf(onComplete),
            )
            activeOperation = operationToStart
        }

        executor.execute {
            val result = runCatching(sdkCall)
            postToMain {
                val callbacks = synchronized(lock) {
                    val active = activeOperation
                    if (
                        disposed ||
                        active == null ||
                        active.generation != operationToStart.generation
                    ) {
                        emptyList()
                    } else {
                        activeOperation = null
                        active.callbacks.toList()
                    }
                }
                callbacks.forEach { callback -> callback(result) }
            }
        }
    }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            generation++
            activeOperation = null
        }
        executor.shutdownNow()
    }

    private fun postFailure(
        callback: (Result<DCSSDKDefs.DCSSDK_RESULT>) -> Unit,
        error: Throwable,
    ) {
        postToMain { callback(Result.failure(error)) }
    }
}
