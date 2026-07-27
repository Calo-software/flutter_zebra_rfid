package nz.calo.flutter_zebra_rfid.barcode

import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID
import nz.calo.flutter_zebra_rfid.capture.CaptureDiagnosticSink
import nz.calo.flutter_zebra_rfid.capture.record

internal data class DataWedgeCommand(
    val label: String,
    val extraKey: String,
    val value: Any,
    val responseExtra: String? = null,
    val acceptedFailureCodes: Set<String> = emptySet(),
    val sendResult: String = SEND_RESULT_LAST,
    val completionDelayMs: Long? = null,
    val postCompletionDelayMs: Long? = null,
)

internal data class DataWedgeCommandResult(
    val label: String,
    val response: Any? = null,
)

/**
 * Serializes DataWedge intent API calls and advances only after the matching
 * result arrives. DataWedge does not queue commands on behalf of callers.
 */
internal class DataWedgeCommandCoordinator(
    private val sendIntent: (Intent) -> Unit,
    private val scheduleTimeout: (Runnable, Long) -> Unit,
    private val cancelTimeout: (Runnable) -> Unit,
    private val commandIdentifier: () -> String = { "flutter-zebra-${UUID.randomUUID()}" },
    private val timeoutMs: Long = 2_500L,
    private val maxAttempts: Int = 2,
    private val diagnostics: CaptureDiagnosticSink = CaptureDiagnosticSink.NONE,
) {
    private data class Sequence(
        val commands: ArrayDeque<DataWedgeCommand>,
        val completed: MutableList<DataWedgeCommandResult>,
        val onSuccess: (List<DataWedgeCommandResult>) -> Unit,
        val onError: (String) -> Unit,
    )

    private val sequences = ArrayDeque<Sequence>()
    private var activeSequence: Sequence? = null
    private var activeCommand: DataWedgeCommand? = null
    private var activeIdentifier: String? = null
    private var activeAttempt = 0
    private var timeout: Runnable? = null

    fun enqueue(
        commands: List<DataWedgeCommand>,
        onSuccess: (List<DataWedgeCommandResult>) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        if (commands.isEmpty()) {
            onSuccess(emptyList())
            return
        }
        sequences.addLast(
            Sequence(
                commands = ArrayDeque(commands),
                completed = mutableListOf(),
                onSuccess = onSuccess,
                onError = onError,
            ),
        )
        diagnostics.record(
            "datawedge",
            "command_sequence",
            "queued",
            mapOf(
                "command_count" to commands.size,
                "commands" to commands.joinToString(",") { it.label },
            ),
        )
        startNextSequenceIfIdle()
    }

    fun handleResult(intent: Intent): Boolean {
        if (intent.action != ACTION_RESULT) return false
        val command = activeCommand ?: return false
        val identifier = intent.getStringExtra(EXTRA_COMMAND_IDENTIFIER)

        if (command.responseExtra != null && intent.hasExtra(command.responseExtra)) {
            if (identifier != null && identifier != activeIdentifier) return false
            completeCommand(intent.extras?.get(command.responseExtra))
            return true
        }

        if (identifier == null || identifier != activeIdentifier) return false
        if (command.sendResult == SEND_RESULT_COMPLETE && intent.hasExtra(EXTRA_RESULT_LIST)) {
            val results = extractResultList(intent)
            Log.i(TAG, "${command.label} complete results=${results.map(::describeBundle)}")
            val failure = results.firstOrNull {
                it.getString(EXTRA_RESULT) == RESULT_FAILURE &&
                    it.getString(RESULT_CODE) !in command.acceptedFailureCodes
            }
            if (results.isEmpty()) {
                failSequence("${command.label} failed: DataWedge returned an empty result list")
            } else if (failure == null) {
                diagnostics.record(
                    "datawedge",
                    command.label,
                    "completed",
                    mapOf("attempt" to activeAttempt),
                )
                completeCommand(results)
            } else {
                failSequence(
                    "${command.label} failed: ${failure.getString(RESULT_CODE) ?: describeBundle(failure)}",
                )
            }
            return true
        }
        val result = intent.getStringExtra(EXTRA_RESULT)
        val resultCode = extractResultCode(intent.extras?.get(EXTRA_RESULT_INFO))
        if (result == RESULT_SUCCESS || resultCode in command.acceptedFailureCodes) {
            diagnostics.record(
                "datawedge",
                command.label,
                "completed",
                mapOf("attempt" to activeAttempt, "result_code" to resultCode),
            )
            completeCommand()
        } else {
            failSequence(
                "${command.label} failed: ${resultCode ?: result ?: "unknown DataWedge error"}",
            )
        }
        return true
    }

    fun cancel(reason: String = "DataWedge coordinator disposed") {
        diagnostics.record(
            "datawedge",
            "command_sequence",
            "cancelled",
            mapOf("reason" to reason),
        )
        timeout?.let(cancelTimeout)
        timeout = null
        val errorCallbacks = buildList {
            activeSequence?.let { add(it.onError) }
            addAll(sequences.map { it.onError })
        }
        sequences.clear()
        activeSequence = null
        activeCommand = null
        activeIdentifier = null
        errorCallbacks.forEach { it(reason) }
    }

    private fun startNextSequenceIfIdle() {
        if (activeSequence != null) return
        activeSequence = sequences.pollFirst() ?: return
        sendNextCommand()
    }

    private fun sendNextCommand() {
        val sequence = activeSequence ?: return
        val command = sequence.commands.pollFirst()
        if (command == null) {
            activeSequence = null
            sequence.onSuccess(sequence.completed.toList())
            startNextSequenceIfIdle()
            return
        }
        activeCommand = command
        activeAttempt = 0
        sendActiveCommand()
    }

    private fun sendActiveCommand() {
        val command = activeCommand ?: return
        activeAttempt += 1
        val identifier = commandIdentifier()
        activeIdentifier = identifier

        val intent = Intent(ACTION_DATAWEDGE).apply {
            when (val commandValue = command.value) {
                is Bundle -> putExtra(command.extraKey, commandValue)
                is String -> putExtra(command.extraKey, commandValue)
                else -> error(
                    "Unsupported DataWedge value: ${commandValue::class.java.name}",
                )
            }
            putExtra(EXTRA_SEND_RESULT, command.sendResult)
            putExtra(EXTRA_COMMAND_IDENTIFIER, identifier)
            putExtra(EXTRA_RESULT_CATEGORY, Intent.CATEGORY_DEFAULT)
        }
        Log.i(TAG, "Sending ${command.label} attempt=$activeAttempt id=$identifier")
        diagnostics.record(
            "datawedge",
            command.label,
            "sent",
            mapOf("attempt" to activeAttempt),
        )
        sendIntent(intent)

        timeout?.let(cancelTimeout)
        command.completionDelayMs?.let { delay ->
            timeout = Runnable {
                if (activeIdentifier == identifier) completeCommand()
            }.also { scheduleTimeout(it, delay) }
            return
        }
        timeout = Runnable {
            if (activeIdentifier != identifier) return@Runnable
            if (activeAttempt < maxAttempts) {
                Log.w(TAG, "Retrying ${command.label} after DataWedge result timeout")
                diagnostics.record(
                    "datawedge",
                    command.label,
                    "result_timeout_retry",
                    mapOf("attempt" to activeAttempt),
                )
                sendActiveCommand()
            } else {
                failSequence("${command.label} timed out waiting for DataWedge")
            }
        }.also { scheduleTimeout(it, timeoutMs) }
    }

    private fun completeCommand(response: Any? = null) {
        timeout?.let(cancelTimeout)
        timeout = null
        val command = activeCommand ?: return
        activeIdentifier = null
        command.postCompletionDelayMs?.let { delay ->
            timeout = Runnable {
                if (activeCommand == command) finishCommand(command, response)
            }.also { scheduleTimeout(it, delay) }
            return
        }
        finishCommand(command, response)
    }

    private fun finishCommand(command: DataWedgeCommand, response: Any?) {
        timeout?.let(cancelTimeout)
        timeout = null
        activeSequence?.completed?.add(
            DataWedgeCommandResult(command.label, response),
        )
        activeCommand = null
        sendNextCommand()
    }

    private fun failSequence(message: String) {
        timeout?.let(cancelTimeout)
        timeout = null
        Log.e(TAG, message)
        diagnostics.record(
            "datawedge",
            activeCommand?.label ?: "command_sequence",
            "failed",
            mapOf(
                "attempt" to activeAttempt,
                "failure_type" to message.substringBefore(':'),
            ),
        )
        val sequence = activeSequence
        activeSequence = null
        activeCommand = null
        activeIdentifier = null
        sequence?.onError(message)
        startNextSequenceIfIdle()
    }

    private fun extractResultCode(resultInfo: Any?): String? = when (resultInfo) {
        is Bundle -> resultInfo.getString("RESULT_CODE")
        is ArrayList<*> -> resultInfo
            .filterIsInstance<Bundle>()
            .firstNotNullOfOrNull { it.getString("RESULT_CODE") }
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun extractResultList(intent: Intent): List<Bundle> =
        (intent.getSerializableExtra(EXTRA_RESULT_LIST) as? ArrayList<*>)
            ?.filterIsInstance<Bundle>()
            .orEmpty()

    @Suppress("DEPRECATION")
    private fun describeBundle(bundle: Bundle): String = bundle.keySet()
        .sorted()
        .joinToString(prefix = "{", postfix = "}") { key ->
            val value = bundle.get(key)
            val rendered = when (value) {
                is Array<*> -> value.contentToString()
                is IntArray -> value.contentToString()
                is LongArray -> value.contentToString()
                is BooleanArray -> value.contentToString()
                else -> value.toString()
            }
            "$key=$rendered"
        }

    private companion object {
        const val TAG = "DataWedgeCoordinator"
        const val ACTION_DATAWEDGE = "com.symbol.datawedge.api.ACTION"
        const val ACTION_RESULT = "com.symbol.datawedge.api.RESULT_ACTION"
        const val EXTRA_SEND_RESULT = "SEND_RESULT"
        const val EXTRA_COMMAND_IDENTIFIER = "COMMAND_IDENTIFIER"
        const val EXTRA_RESULT = "RESULT"
        const val EXTRA_RESULT_INFO = "RESULT_INFO"
        const val EXTRA_RESULT_LIST = "RESULT_LIST"
        const val EXTRA_RESULT_CATEGORY = "com.symbol.datawedge.api.RESULT_CATEGORY"
        const val RESULT_SUCCESS = "SUCCESS"
        const val RESULT_FAILURE = "FAILURE"
        const val RESULT_CODE = "RESULT_CODE"
    }
}

internal const val SEND_RESULT_LAST = "LAST_RESULT"
internal const val SEND_RESULT_COMPLETE = "COMPLETE_RESULT"
