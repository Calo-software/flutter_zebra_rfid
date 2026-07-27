package nz.calo.flutter_zebra_rfid.capture

internal sealed interface RfidLifecycleRequest {
    data class Start(val generation: Long) : RfidLifecycleRequest
    data class Joined(val generation: Long) : RfidLifecycleRequest
}

internal enum class RfidLifecycleCompletion {
    ADOPT_SESSION,
    TERMINATE_STALE_SESSION,
    IGNORE_FAILURE,
    RETRY_ALLOWED,
}

/**
 * Serializes the logical ownership of blocking Zebra RFID lifecycle calls.
 *
 * A timeout is diagnostic only: it cannot make a Zebra SDK call cancellable.
 * The operation remains active until its native completion is reconciled.
 */
internal class RfidLifecycleGate {
    private var nextGeneration = 0L
    private var selectedHardwareIdentity: String? = null
    private var activeGeneration: Long? = null
    private var activeHardwareIdentity: String? = null
    private var invalidatedGenerations = mutableSetOf<Long>()
    private var cleanupGeneration: Long? = null
    private var timedOutGenerations = mutableSetOf<Long>()

    @Synchronized
    fun select(hardwareIdentity: String) {
        if (selectedHardwareIdentity == hardwareIdentity) return
        activeGeneration?.let(invalidatedGenerations::add)
        selectedHardwareIdentity = hardwareIdentity
    }

    @Synchronized
    fun request(hardwareIdentity: String): RfidLifecycleRequest {
        if (selectedHardwareIdentity != hardwareIdentity) {
            select(hardwareIdentity)
        }
        val current = activeGeneration ?: cleanupGeneration
        if (current != null) return RfidLifecycleRequest.Joined(current)

        val generation = ++nextGeneration
        activeGeneration = generation
        activeHardwareIdentity = hardwareIdentity
        return RfidLifecycleRequest.Start(generation)
    }

    @Synchronized
    fun invalidate(generation: Long? = activeGeneration) {
        generation?.let(invalidatedGenerations::add)
    }

    @Synchronized
    fun recordTimeout(generation: Long) {
        if (activeGeneration == generation) {
            timedOutGenerations.add(generation)
        }
    }

    @Synchronized
    fun completeSuccess(
        generation: Long,
        hardwareIdentity: String,
    ): RfidLifecycleCompletion {
        val mayAdopt = activeGeneration == generation &&
            !invalidatedGenerations.contains(generation) &&
            selectedHardwareIdentity == hardwareIdentity &&
            activeHardwareIdentity == hardwareIdentity

        activeGeneration = null
        activeHardwareIdentity = null
        timedOutGenerations.remove(generation)

        return if (mayAdopt) {
            invalidatedGenerations.remove(generation)
            RfidLifecycleCompletion.ADOPT_SESSION
        } else {
            cleanupGeneration = generation
            RfidLifecycleCompletion.TERMINATE_STALE_SESSION
        }
    }

    @Synchronized
    fun completeFailure(generation: Long): RfidLifecycleCompletion {
        if (activeGeneration != generation) {
            return RfidLifecycleCompletion.IGNORE_FAILURE
        }
        activeGeneration = null
        activeHardwareIdentity = null
        invalidatedGenerations.remove(generation)
        timedOutGenerations.remove(generation)
        return RfidLifecycleCompletion.RETRY_ALLOWED
    }

    @Synchronized
    fun completeTermination(generation: Long) {
        if (cleanupGeneration == generation) {
            cleanupGeneration = null
        }
        invalidatedGenerations.remove(generation)
        timedOutGenerations.remove(generation)
    }

    @Synchronized
    fun invalidateSelection() {
        activeGeneration?.let(invalidatedGenerations::add)
        selectedHardwareIdentity = null
    }

    @Synchronized
    fun hasActiveOperation(): Boolean =
        activeGeneration != null || cleanupGeneration != null
}
