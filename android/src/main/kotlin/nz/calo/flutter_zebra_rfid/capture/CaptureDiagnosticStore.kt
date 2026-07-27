package nz.calo.flutter_zebra_rfid.capture

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal data class CaptureDiagnosticRecord(
    val timestampMs: Long,
    val sequence: Long,
    val category: String,
    val operation: String,
    val outcome: String,
    val details: Map<String, String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp_ms", timestampMs)
        put("sequence", sequence)
        put("category", category)
        put("operation", operation)
        put("outcome", outcome)
        put("details", JSONObject(details))
    }

    companion object {
        fun fromJson(json: JSONObject): CaptureDiagnosticRecord {
            val detailsJson = json.optJSONObject("details") ?: JSONObject()
            val details = buildMap {
                detailsJson.keys().forEach { key ->
                    put(key, detailsJson.optString(key))
                }
            }
            return CaptureDiagnosticRecord(
                timestampMs = json.getLong("timestamp_ms"),
                sequence = json.getLong("sequence"),
                category = json.getString("category"),
                operation = json.getString("operation"),
                outcome = json.getString("outcome"),
                details = details,
            )
        }
    }
}

internal fun interface CaptureDiagnosticSink {
    fun record(
        category: String,
        operation: String,
        outcome: String,
        details: Map<String, Any?>,
    )

    companion object {
        val NONE = CaptureDiagnosticSink { _, _, _, _ -> }
    }
}

internal fun CaptureDiagnosticSink.record(
    category: String,
    operation: String,
    outcome: String,
) = record(category, operation, outcome, emptyMap())

class CaptureDiagnosticStore(
    directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ioExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zebra-capture-diagnostics").apply { isDaemon = true }
    },
    private val maxEvents: Int = 500,
    private val maxAgeMs: Long = 15 * 60 * 1_000L,
    private val maxBytes: Int = 256 * 1_024,
) : CaptureDiagnosticSink {
    private val file = File(directory, FILE_NAME)
    private val records = ArrayDeque<CaptureDiagnosticRecord>()
    private val persistScheduled = AtomicBoolean(false)
    private var nextSequence = 1L
    @Volatile
    private var persistDirty = false

    init {
        load()
    }

    override fun record(
        category: String,
        operation: String,
        outcome: String,
        details: Map<String, Any?>,
    ) {
        synchronized(records) {
            records.addLast(
                CaptureDiagnosticRecord(
                    timestampMs = nowMillis(),
                    sequence = nextSequence++,
                    category = category.take(MAX_TEXT_LENGTH),
                    operation = operation.take(MAX_TEXT_LENGTH),
                    outcome = outcome.take(MAX_TEXT_LENGTH),
                    details = sanitizeDetails(details),
                ),
            )
            pruneLocked()
        }
        schedulePersist()
    }

    internal fun snapshot(): List<CaptureDiagnosticRecord> = synchronized(records) {
        pruneLocked()
        records.toList()
    }

    fun clear() {
        synchronized(records) {
            records.clear()
        }
        schedulePersist()
    }

    fun close() {
        persistLatest()
        (ioExecutor as? ExecutorService)?.shutdown()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            file.readLines(StandardCharsets.UTF_8)
                .filter { it.isNotBlank() }
                .map { CaptureDiagnosticRecord.fromJson(JSONObject(it)) }
        }.onSuccess { loaded ->
            synchronized(records) {
                records.addAll(loaded)
                nextSequence = (records.maxOfOrNull { it.sequence } ?: 0L) + 1L
                pruneLocked()
            }
        }.onFailure {
            file.delete()
        }
    }

    private fun schedulePersist() {
        persistDirty = true
        if (!persistScheduled.compareAndSet(false, true)) return
        ioExecutor.execute {
            do {
                persistDirty = false
                persistLatest()
            } while (persistDirty)
            persistScheduled.set(false)
            if (persistDirty) schedulePersist()
        }
    }

    private fun persistLatest() {
        val lines = synchronized(records) {
            pruneLocked()
            records.map { it.toJson().toString() }
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeText(lines.joinToString(separator = "\n"), StandardCharsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
            temporary.delete()
        }
    }

    private fun pruneLocked() {
        val oldestAllowed = nowMillis() - maxAgeMs
        while (records.firstOrNull()?.timestampMs?.let { it < oldestAllowed } == true) {
            records.removeFirst()
        }
        while (records.size > maxEvents) {
            records.removeFirst()
        }
        while (records.size > 1 && encodedSizeLocked() > maxBytes) {
            records.removeFirst()
        }
    }

    private fun encodedSizeLocked(): Int = records.sumOf {
        it.toJson().toString().toByteArray(StandardCharsets.UTF_8).size + 1
    }

    private fun sanitizeDetails(details: Map<String, Any?>): Map<String, String> =
        buildMap {
            details.forEach { (rawKey, value) ->
                val key = normalizeKey(rawKey)
                if (key.isEmpty() || isForbiddenKey(key) || value == null) return@forEach
                put(key, value.toString().take(MAX_DETAIL_LENGTH))
            }
        }

    private fun normalizeKey(key: String): String =
        key.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')

    private fun isForbiddenKey(key: String): Boolean {
        if (key == "terminal_id" || key == "reader_serial") return false
        return key in FORBIDDEN_KEYS ||
            key.contains("identifier") ||
            key.contains("password") ||
            key.contains("invite_code") ||
            key.contains("order_id") ||
            key.endsWith("_rfid") ||
            key == "token" ||
            key.endsWith("_token")
    }

    private companion object {
        const val FILE_NAME = "capture-diagnostics.jsonl"
        const val MAX_TEXT_LENGTH = 80
        const val MAX_DETAIL_LENGTH = 240
        val FORBIDDEN_KEYS = setOf(
            "asset_rfid",
            "barcode",
            "barcode_data",
            "bin_id",
            "epc",
            "identifier",
            "invite_code",
            "order_id",
            "password",
            "pending_rfid",
            "rfid",
            "terminal_token",
            "token",
        )
    }
}
