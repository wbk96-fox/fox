package com.foxtv.app.core.plugin.cloudstream

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes external-extension lifecycle mutations and provides the artifact/metadata commit
 * boundary. Network staging may happen inside [serialized], while active file swaps are retained
 * until [metadataCommit] succeeds.
 */
@Singleton
class ExternalPluginLifecycleCoordinator @Inject constructor() {
    private val lifecycleMutex = Mutex()

    suspend fun <T> serialized(block: suspend () -> T): T = lifecycleMutex.withLock {
        block()
    }

    /**
     * Activates every staged artifact as one generation. Any failure or cancellation before the
     * metadata commit returns restores all previous files in reverse activation order.
     */
    internal suspend fun <T> activateAndCommit(
        stagedArtifacts: List<StagedExternalExtension>,
        metadataCommit: suspend () -> T,
    ): T {
        val activations = mutableListOf<ExternalExtensionActivation>()
        var metadataCommitted = false
        try {
            currentCoroutineContext().ensureActive()
            stagedArtifacts.forEach { staged ->
                currentCoroutineContext().ensureActive()
                activations += staged.activateRetainingBackup()
            }
            currentCoroutineContext().ensureActive()

            // Cancellation is checked immediately before this short commit point. Once the
            // DataStore edit starts it is completed non-cancellably, so there is no ambiguous
            // state where metadata committed but the transaction still attempts file rollback.
            val result = withContext(NonCancellable) {
                metadataCommit().also { metadataCommitted = true }
            }

            // From this point repository metadata references the new generation. Finalization must
            // not be interrupted; failed backup deletion leaves only an inert recovery file.
            withContext(NonCancellable) {
                activations.forEach(ExternalExtensionActivation::commit)
            }
            return result
        } catch (failure: Throwable) {
            if (!metadataCommitted) {
                withContext(NonCancellable) {
                    activations.asReversed().forEach { activation ->
                        try {
                            activation.rollback()
                        } catch (rollbackFailure: Throwable) {
                            failure.addSuppressed(rollbackFailure)
                        }
                    }
                }
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                stagedArtifacts.forEach { staged ->
                    try {
                        staged.close()
                    } catch (_: Throwable) {
                        // close only removes an unactivated private staging file
                    }
                }
            }
        }
    }
}
