package com.foxtv.app.core.plugin.aniyomi

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serialized, static-only Aniyomi artifact lifecycle.
 *
 * A selected generation is data for a future separately-gated loader. This class never installs,
 * opens in a VM, reflects over, constructs, or executes extension code.
 */
@Singleton
internal class AniyomiLifecycleCoordinator @Inject constructor(
    private val inspector: AniyomiStaticApkInspector,
    private val policyProvider: AniyomiTrustedPolicyProvider,
    private val artifacts: AniyomiArtifactStore,
    private val state: AniyomiLifecycleStateStore,
    private val selectionBarrier: AniyomiSelectionChangeBarrier,
) {
    private val mutex = Mutex()

    suspend fun stageAndSelect(source: InspectedAniyomiApk): AniyomiLifecycleOutcome =
        mutex.withLock {
            lifecycleOutcome {
                val recovery = recoverLocked()
                if (recovery is AniyomiLifecycleOutcome.Failed) return@lifecycleOutcome recovery
                stageAndSelectLocked(source)
            }
        }

    suspend fun recover(): AniyomiLifecycleOutcome = mutex.withLock {
        lifecycleOutcome { recoverLocked() }
    }

    suspend fun rollback(): AniyomiLifecycleOutcome = mutex.withLock {
        lifecycleOutcome {
            val recovery = recoverLocked()
            if (recovery is AniyomiLifecycleOutcome.Failed) return@lifecycleOutcome recovery
            val record = state.read()
                ?: throw lifecycleFailure(
                    AniyomiLifecycleFailureCode.NO_ROLLBACK_CANDIDATE,
                    "No Aniyomi generation is selected",
                )
            val previous = record.previous
                ?: throw lifecycleFailure(
                    AniyomiLifecycleFailureCode.NO_ROLLBACK_CANDIDATE,
                    "No previous Aniyomi generation is available for rollback",
                )
            performRollback(record, previous, retainDisplaced = true)
        }
    }

    /**
     * Activates only the freshly reinspected selected generation while the M2 mutex pins its
     * pointer. The ephemeral capability cannot be returned or persisted by a caller.
     */
    internal suspend fun activateSelected(
        boundary: AniyomiLoadBoundary,
    ): AniyomiLoadBoundaryOutcome = mutex.withLock {
        val selectedIdentity = recoverSelectedIdentityForLoadLocked()
        activateIdentityLocked(selectedIdentity, boundary)
    }

    /**
     * Acquires M2 first, then has M3 atomically validate and retain the exact failed/LKG pair while
     * the pointer is swapped and that exact rollback target is activated. Retry, disable, updater,
     * and rollback therefore have one deterministic lock order and cannot consume stale authority.
     */
    internal suspend fun rollbackFailedActivationAndActivate(
        boundary: AniyomiLoadBoundary,
    ): AniyomiLoadBoundaryOutcome = mutex.withLock {
        when (val recovery = recoverLocked()) {
            is AniyomiLifecycleOutcome.Failed -> throw AniyomiLifecycleException(recovery.failure)
            AniyomiLifecycleOutcome.Empty -> throw lifecycleFailure(
                AniyomiLifecycleFailureCode.NO_ROLLBACK_CANDIDATE,
                "No Aniyomi selection exists for activation rollback",
            )

            is AniyomiLifecycleOutcome.Selected -> Unit
        }
        val record = state.read()
            ?: throw lifecycleFailure(
                AniyomiLifecycleFailureCode.NO_ROLLBACK_CANDIDATE,
                "No Aniyomi selection exists for activation rollback",
            )
        val selected = record.selected
            ?: throw lifecycleFailure(
                AniyomiLifecycleFailureCode.NO_ROLLBACK_CANDIDATE,
                "M2 has no failed selected generation for activation rollback",
            )
        val previous = record.previous
            ?: throw lifecycleFailure(
                AniyomiLifecycleFailureCode.NO_ROLLBACK_CANDIDATE,
                "M2 has no last-known-good generation for activation rollback",
            )
        val request = AniyomiActivationRollbackRequest(
            failedGeneration = selected.loadReference(),
            lastKnownGood = previous.loadReference(),
        )
        boundary.consumeRollbackAndActivate(request) {
            val rolledBack = performRollback(
                record = record,
                rollbackCandidate = previous,
                retainDisplaced = true,
                selectionChangeAlreadyAuthorized = true,
            )
            check(rolledBack is AniyomiLifecycleOutcome.Selected)
            if (rolledBack.identity.loadReference() != request.lastKnownGood) {
                throw lifecycleFailure(
                    AniyomiLifecycleFailureCode.STATE_CORRUPT,
                    "Explicit Aniyomi rollback produced a different generation identity",
                )
            }
            selectedGenerationLocked(rolledBack.identity)
        }
    }

    private suspend fun recoverSelectedIdentityForLoadLocked(): AniyomiArtifactIdentity {
        when (val recovery = recoverLocked()) {
            is AniyomiLifecycleOutcome.Failed -> throw AniyomiLifecycleException(recovery.failure)
            AniyomiLifecycleOutcome.Empty -> throw lifecycleFailure(
                AniyomiLifecycleFailureCode.NO_ROLLBACK_CANDIDATE,
                "No Aniyomi generation is selected for loading",
            )

            is AniyomiLifecycleOutcome.Selected -> Unit
        }
        return state.read()?.selected
            ?: throw lifecycleFailure(
                AniyomiLifecycleFailureCode.NO_ROLLBACK_CANDIDATE,
                "No durable Aniyomi selection exists for loading",
            )
    }

    private suspend fun activateIdentityLocked(
        selectedIdentity: AniyomiArtifactIdentity,
        boundary: AniyomiLoadBoundary,
    ): AniyomiLoadBoundaryOutcome = boundary.activate(
        selectedGenerationLocked(selectedIdentity),
    )

    private suspend fun selectedGenerationLocked(
        selectedIdentity: AniyomiArtifactIdentity,
    ): AniyomiSelectedGeneration {
        val inspected = inspectStored(selectedIdentity)
        val policy = trustedPolicyFor(inspected.identity)
        return AniyomiSelectedGeneration(
            file = inspected.artifact.file,
            identity = inspected.identity,
            report = inspected.artifact.report,
            policy = policy,
        )
    }

    private suspend fun stageAndSelectLocked(
        source: InspectedAniyomiApk,
    ): AniyomiLifecycleOutcome {
        val policy = policyProvider.current()
        verifyIncomingCapability(source, policy)
        val baseline = state.read()
        verifyRecordPolicies(baseline)
        val transactionId = UUID.randomUUID().toString()
        val provisional = AniyomiArtifactIdentity.fromInspection(
            source,
            AniyomiStoredArtifactKind.TRANSACTION,
            transactionId,
            policy,
        )
        var selectionCommitted = false
        artifacts.createTransaction(transactionId)
        return try {
            state.write(
                AniyomiLifecycleRecord(
                    policyId = policy.id,
                    policyVersion = policy.version,
                    phase = AniyomiLifecyclePhase.PREPARING,
                    transactionId = transactionId,
                    selected = baseline?.selected,
                    previous = baseline?.previous,
                    candidate = provisional,
                ),
            )
            val copied = artifacts.copyCapabilityToTransaction(source, transactionId, policy)
            val inspectedCandidate = inspectFile(
                copied,
                AniyomiStoredArtifactKind.TRANSACTION,
                transactionId,
                policy,
            )
            state.write(
                AniyomiLifecycleRecord(
                    policyId = policy.id,
                    policyVersion = policy.version,
                    phase = AniyomiLifecyclePhase.PREPARED,
                    transactionId = transactionId,
                    selected = baseline?.selected,
                    previous = baseline?.previous,
                    candidate = inspectedCandidate.identity,
                ),
            )

            val published = publishAndInspect(inspectedCandidate.identity, policy)
            val selecting = AniyomiLifecycleRecord(
                policyId = policy.id,
                policyVersion = policy.version,
                phase = AniyomiLifecyclePhase.SELECTING,
                transactionId = transactionId,
                selected = baseline?.selected,
                previous = baseline?.previous,
                candidate = published.identity,
            )
            state.write(selecting)

            val immediatelyReinspected = try {
                inspectStored(published.identity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AniyomiLifecycleException) {
                quarantineWithFailure(published.identity, "rejected-before-selection", error)
                throw error
            }
            val previous = if (
                baseline?.selected?.loadReference() == immediatelyReinspected.identity.loadReference()
            ) {
                baseline.previous
            } else {
                baseline?.selected
            }
            val cleanupPending = AniyomiLifecycleRecord(
                policyId = policy.id,
                policyVersion = policy.version,
                phase = AniyomiLifecyclePhase.CLEANUP_PENDING,
                transactionId = transactionId,
                selected = immediatelyReinspected.identity,
                previous = previous,
            )
            commitSelectionTransition(baseline?.selected, cleanupPending)
            selectionCommitted = true
            withContext(NonCancellable) {
                artifacts.cleanupTransaction(transactionId)
                state.write(cleanupPending.selectedRecord())
            }
            AniyomiLifecycleOutcome.Selected(
                immediatelyReinspected.identity,
                immediatelyReinspected.artifact.report,
                recovered = false,
            )
        } catch (cancelled: CancellationException) {
            if (!selectionCommitted) {
                withContext(NonCancellable) {
                    restoreBaselineAfterCleanup(baseline, transactionId)
                        ?.let(cancelled::addSuppressed)
                }
            }
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            if (!selectionCommitted) {
                withContext(NonCancellable) {
                    restoreBaselineAfterCleanup(baseline, transactionId)
                        ?.let(error::addSuppressed)
                }
            }
            throw error
        } catch (error: Exception) {
            val wrapped = lifecycleFailure(
                AniyomiLifecycleFailureCode.STORAGE_IO,
                "Aniyomi staging failed closed",
                error,
            )
            if (!selectionCommitted) {
                withContext(NonCancellable) {
                    restoreBaselineAfterCleanup(baseline, transactionId)
                        ?.let(wrapped::addSuppressed)
                }
            }
            throw wrapped
        }
    }

    private suspend fun recoverLocked(): AniyomiLifecycleOutcome {
        val record = state.read()
        verifyRecordPolicies(record)
        artifacts.quarantineUnknownEntries(record?.transactionId)
        if (record == null) return AniyomiLifecycleOutcome.Empty

        return when (record.phase) {
            AniyomiLifecyclePhase.PREPARING -> {
                cleanupForRecovery(record.transactionId)
                restoreKnownSelection(record.selected, record.previous)
            }

            AniyomiLifecyclePhase.PREPARED -> recoverPrepared(record)
            AniyomiLifecyclePhase.SELECTING -> recoverSelecting(record)
            AniyomiLifecyclePhase.CLEANUP_PENDING -> recoverCleanupPending(record)
            AniyomiLifecyclePhase.ROLLBACK_REQUIRED -> {
                val previous = requireNotNull(record.previous)
                performRollback(record, previous, retainDisplaced = false)
            }

            AniyomiLifecyclePhase.SELECTED -> recoverSelected(record)
        }
    }

    private suspend fun recoverPrepared(
        record: AniyomiLifecycleRecord,
    ): AniyomiLifecycleOutcome {
        val candidateIdentity = requireNotNull(record.candidate)
        val candidate = validateOrNull(candidateIdentity)
        if (candidate == null) {
            quarantineQuietly(candidateIdentity, "rejected-prepared-candidate")
            cleanupForRecovery(record.transactionId)
            return restoreKnownSelection(record.selected, record.previous)
        }
        val candidatePolicy = trustedPolicyFor(candidate.identity)
        val published = publishAndInspect(candidate.identity, candidatePolicy)
        val selecting = record.copy(
            phase = AniyomiLifecyclePhase.SELECTING,
            candidate = published.identity,
        )
        state.write(selecting)
        return completeRecoveredSelection(selecting, published.identity)
    }

    private suspend fun recoverSelecting(
        record: AniyomiLifecycleRecord,
    ): AniyomiLifecycleOutcome {
        val candidateIdentity = requireNotNull(record.candidate)
        val candidate = validateOrNull(candidateIdentity)
        if (candidate == null) {
            quarantineQuietly(candidateIdentity, "rejected-selecting-candidate")
            cleanupForRecovery(record.transactionId)
            return restoreKnownSelection(record.selected, record.previous)
        }
        return completeRecoveredSelection(record, candidate.identity)
    }

    private suspend fun completeRecoveredSelection(
        selecting: AniyomiLifecycleRecord,
        candidate: AniyomiArtifactIdentity,
    ): AniyomiLifecycleOutcome {
        val current = validateOrNull(selecting.selected)
        val previous = validateOrNull(selecting.previous)
        val immediatelyReinspected = try {
            inspectStored(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            quarantineWithFailure(candidate, "rejected-before-recovered-selection", error)
            throw error
        }
        val displaced = if (
            current?.identity?.loadReference() == immediatelyReinspected.identity.loadReference()
        ) {
            previous?.identity
        } else {
            current?.identity
        }
        val cleanupPending = AniyomiLifecycleRecord(
            policyId = immediatelyReinspected.identity.policyId,
            policyVersion = immediatelyReinspected.identity.policyVersion,
            phase = AniyomiLifecyclePhase.CLEANUP_PENDING,
            transactionId = selecting.transactionId,
            selected = immediatelyReinspected.identity,
            previous = displaced,
        )
        commitSelectionTransition(selecting.selected, cleanupPending)
        withContext(NonCancellable) {
            cleanupPending.transactionId?.let { artifacts.cleanupTransaction(it) }
            state.write(cleanupPending.selectedRecord())
        }
        return AniyomiLifecycleOutcome.Selected(
            immediatelyReinspected.identity,
            immediatelyReinspected.artifact.report,
            recovered = true,
        )
    }

    private suspend fun recoverCleanupPending(
        record: AniyomiLifecycleRecord,
    ): AniyomiLifecycleOutcome {
        val selected = validateOrNull(record.selected)
        if (selected == null) {
            // Never discard the only durable transaction reference before known cleanup succeeds.
            cleanupForRecovery(record.transactionId)
            return recoverMissingSelected(record)
        }
        val previous = validateOrNull(record.previous)
        if (record.previous != null && previous == null) {
            quarantineQuietly(record.previous, "rejected-previous-generation")
        }
        withContext(NonCancellable) {
            record.transactionId?.let { artifacts.cleanupTransaction(it) }
            state.write(
                record.copy(
                    phase = AniyomiLifecyclePhase.SELECTED,
                    transactionId = null,
                    selected = selected.identity,
                    previous = previous?.identity,
                    candidate = null,
                ),
            )
        }
        return AniyomiLifecycleOutcome.Selected(selected.identity, selected.artifact.report, recovered = true)
    }

    private suspend fun recoverSelected(
        record: AniyomiLifecycleRecord,
    ): AniyomiLifecycleOutcome {
        val selected = validateOrNull(record.selected)
        if (selected == null) return recoverMissingSelected(record)
        val previous = validateOrNull(record.previous)
        if (record.previous != null && previous == null) {
            quarantineQuietly(record.previous, "rejected-previous-generation")
            state.write(record.copy(previous = null))
        }
        return AniyomiLifecycleOutcome.Selected(selected.identity, selected.artifact.report, recovered = true)
    }

    private suspend fun recoverMissingSelected(
        record: AniyomiLifecycleRecord,
    ): AniyomiLifecycleOutcome {
        record.selected?.let { quarantineQuietly(it, "rejected-selected-generation") }
        val previous = validateOrNull(record.previous)
        if (previous != null) {
            val rollbackRequired = AniyomiLifecycleRecord(
                policyId = previous.identity.policyId,
                policyVersion = previous.identity.policyVersion,
                phase = AniyomiLifecyclePhase.ROLLBACK_REQUIRED,
                selected = record.selected,
                previous = previous.identity,
            )
            state.write(rollbackRequired)
            return performRollback(
                rollbackRequired,
                previous.identity,
                retainDisplaced = false,
            )
        }
        record.previous?.let { quarantineQuietly(it, "rejected-previous-generation") }
        commitSelectionTransition(record.selected, null)
        throw lifecycleFailure(
            AniyomiLifecycleFailureCode.RECOVERY_FAILED,
            "Neither selected nor previous Aniyomi generation passed full recovery inspection",
        )
    }

    private suspend fun restoreKnownSelection(
        selectedIdentity: AniyomiArtifactIdentity?,
        previousIdentity: AniyomiArtifactIdentity?,
    ): AniyomiLifecycleOutcome {
        val selected = validateOrNull(selectedIdentity)
        val previous = validateOrNull(previousIdentity)
        if (selected != null) {
            val normalized = AniyomiLifecycleRecord(
                policyId = selected.identity.policyId,
                policyVersion = selected.identity.policyVersion,
                phase = AniyomiLifecyclePhase.SELECTED,
                selected = selected.identity,
                previous = previous?.identity,
            )
            state.write(normalized)
            return AniyomiLifecycleOutcome.Selected(selected.identity, selected.artifact.report, recovered = true)
        }
        selectedIdentity?.let { quarantineQuietly(it, "rejected-selected-generation") }
        if (previous != null) {
            val rollbackRequired = AniyomiLifecycleRecord(
                policyId = previous.identity.policyId,
                policyVersion = previous.identity.policyVersion,
                phase = AniyomiLifecyclePhase.ROLLBACK_REQUIRED,
                selected = selectedIdentity,
                previous = previous.identity,
            )
            state.write(rollbackRequired)
            return performRollback(
                rollbackRequired,
                previous.identity,
                retainDisplaced = false,
            )
        }
        previousIdentity?.let { quarantineQuietly(it, "rejected-previous-generation") }
        commitSelectionTransition(selectedIdentity, null)
        return AniyomiLifecycleOutcome.Empty
    }

    private suspend fun performRollback(
        record: AniyomiLifecycleRecord,
        rollbackCandidate: AniyomiArtifactIdentity,
        retainDisplaced: Boolean,
        selectionChangeAlreadyAuthorized: Boolean = false,
    ): AniyomiLifecycleOutcome {
        val inspected = inspectStored(rollbackCandidate)
        state.write(
            AniyomiLifecycleRecord(
                policyId = inspected.identity.policyId,
                policyVersion = inspected.identity.policyVersion,
                phase = AniyomiLifecyclePhase.SELECTING,
                selected = record.selected,
                previous = rollbackCandidate,
                candidate = inspected.identity,
            ),
        )
        val immediatelyReinspected = inspectStored(inspected.identity)
        val cleanupPending = AniyomiLifecycleRecord(
            policyId = immediatelyReinspected.identity.policyId,
            policyVersion = immediatelyReinspected.identity.policyVersion,
            phase = AniyomiLifecyclePhase.CLEANUP_PENDING,
            selected = immediatelyReinspected.identity,
            previous = record.selected.takeIf { retainDisplaced },
        )
        if (selectionChangeAlreadyAuthorized) {
            state.write(cleanupPending)
        } else {
            commitSelectionTransition(record.selected, cleanupPending)
        }
        state.write(cleanupPending.selectedRecord())
        return AniyomiLifecycleOutcome.Selected(
            immediatelyReinspected.identity,
            immediatelyReinspected.artifact.report,
            recovered = true,
        )
    }

    private suspend fun publishAndInspect(
        candidate: AniyomiArtifactIdentity,
        policy: AniyomiTrustedPolicy,
    ): ValidatedArtifact {
        val generationIdentity = candidate.asGeneration()
        val firstPublication = try {
            artifacts.publishGeneration(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            if (!quarantineWithFailure(generationIdentity, "poisoned-generation-slot", error)) {
                throw error
            }
            artifacts.publishGeneration(candidate)
        }
        return try {
            inspectFile(
                firstPublication.file,
                AniyomiStoredArtifactKind.GENERATION,
                transactionId = null,
                policy = policy,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            quarantineWithFailure(generationIdentity, "rejected-published-generation", error)
            if (!firstPublication.reusedExisting) throw error
            val replacement = artifacts.publishGeneration(candidate)
            try {
                inspectFile(
                    replacement.file,
                    AniyomiStoredArtifactKind.GENERATION,
                    transactionId = null,
                    policy = policy,
                )
            } catch (retryCancelled: CancellationException) {
                throw retryCancelled
            } catch (retryError: AniyomiLifecycleException) {
                quarantineWithFailure(
                    generationIdentity,
                    "rejected-republished-generation",
                    retryError,
                )
                throw retryError
            }
        }
    }

    private suspend fun inspectFile(
        file: File,
        kind: AniyomiStoredArtifactKind,
        transactionId: String?,
        policy: AniyomiTrustedPolicy,
    ): ValidatedArtifact {
        val artifact = runInterruptible(Dispatchers.IO) {
            when (val result = inspector.inspect(file, policy.policy)) {
                is AniyomiApkInspectionResult.Accepted -> result.artifact
                is AniyomiApkInspectionResult.Rejected -> throw inspectionRejected(result.failures)
            }
        }
        if (artifact.report.runtimeCompatibilityVerified) {
            throw lifecycleFailure(
                AniyomiLifecycleFailureCode.INVALID_CAPABILITY,
                "Static Aniyomi inspection unexpectedly claimed runtime compatibility",
            )
        }
        return ValidatedArtifact(
            AniyomiArtifactIdentity.fromInspection(artifact, kind, transactionId, policy),
            artifact,
        )
    }

    private suspend fun inspectStored(
        expected: AniyomiArtifactIdentity,
    ): ValidatedArtifact {
        val policy = trustedPolicyFor(expected)
        val file = artifacts.resolve(expected)
        val inspected = inspectFile(file, expected.kind, expected.transactionId, policy)
        if (inspected.identity != expected) {
            throw lifecycleFailure(
                AniyomiLifecycleFailureCode.STATE_CORRUPT,
                "Persisted Aniyomi artifact identity does not match fully reinspected bytes",
            )
        }
        return inspected
    }

    private suspend fun validateOrNull(
        identity: AniyomiArtifactIdentity?,
    ): ValidatedArtifact? {
        if (identity == null) return null
        return try {
            inspectStored(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AniyomiLifecycleException) {
            null
        }
    }

    private fun verifyIncomingCapability(
        source: InspectedAniyomiApk,
        policy: AniyomiTrustedPolicy,
    ) {
        val report = source.report
        val trusted = policy.policy
        val signer = report.signature.certificateSha256.singleOrNull()
        if (report.runtimeCompatibilityVerified ||
            report.sizeBytes != trusted.expectedSizeBytes ||
            report.sha256 != trusted.expectedSha256 ||
            report.manifest.packageName != trusted.expectedPackageName ||
            report.manifest.versionCode != trusted.expectedVersionCode ||
            report.manifest.versionName != trusted.expectedVersionName ||
            report.manifest.factoryClassName != trusted.expectedFactoryClassName ||
            signer != trusted.expectedCertificateSha256
        ) {
            throw lifecycleFailure(
                AniyomiLifecycleFailureCode.INVALID_CAPABILITY,
                "Aniyomi capability does not match the current host-owned policy",
            )
        }
    }

    private fun verifyRecordPolicies(record: AniyomiLifecycleRecord?) {
        listOfNotNull(record?.selected, record?.previous, record?.candidate)
            .forEach(::trustedPolicyFor)
    }

    private fun trustedPolicyFor(identity: AniyomiArtifactIdentity): AniyomiTrustedPolicy {
        if (identity.runtimeCompatibilityVerified) {
            throw lifecycleFailure(
                AniyomiLifecycleFailureCode.POLICY_MISMATCH,
                "Static Aniyomi identity cannot claim runtime compatibility",
            )
        }
        val policy = policyProvider.find(
            identity.policyId,
            identity.policyVersion,
            identity.generationId,
        ) ?: throw lifecycleFailure(
            AniyomiLifecycleFailureCode.POLICY_MISMATCH,
            "Persisted Aniyomi generation is not present in the host-owned policy registry",
        )
        verifyIdentityAgainstPolicy(identity, policy)
        return policy
    }

    private fun verifyIdentityAgainstPolicy(
        identity: AniyomiArtifactIdentity,
        policy: AniyomiTrustedPolicy,
    ) {
        val trusted = policy.policy
        if (identity.policyId != policy.id || identity.policyVersion != policy.version ||
            identity.sha256 != trusted.expectedSha256 ||
            identity.sizeBytes != trusted.expectedSizeBytes ||
            identity.packageName != trusted.expectedPackageName ||
            identity.versionCode != trusted.expectedVersionCode ||
            identity.versionName != trusted.expectedVersionName ||
            identity.factoryClassName != trusted.expectedFactoryClassName ||
            identity.certificateSha256 != trusted.expectedCertificateSha256
        ) {
            throw lifecycleFailure(
                AniyomiLifecycleFailureCode.POLICY_MISMATCH,
                "Persisted Aniyomi identity does not match its host-owned policy",
            )
        }
    }

    private suspend fun cleanupForRecovery(transactionId: String?) {
        if (transactionId == null) return
        try {
            artifacts.cleanupTransaction(transactionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            throw lifecycleFailure(
                AniyomiLifecycleFailureCode.CLEANUP_FAILED,
                "Could not clean a known Aniyomi recovery transaction",
                error,
            )
        }
    }

    private suspend fun restoreBaselineAfterCleanup(
        baseline: AniyomiLifecycleRecord?,
        transactionId: String,
    ): AniyomiLifecycleException? {
        try {
            artifacts.cleanupTransaction(transactionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            // Keep the current durable phase and transaction ID for startup recovery.
            return error
        } catch (error: Exception) {
            return lifecycleFailure(
                AniyomiLifecycleFailureCode.CLEANUP_FAILED,
                "Could not clean failed Aniyomi staging transaction",
                error,
            )
        }
        return try {
            state.write(baseline)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            // The current phase remains durable and recovery can normalize it.
            error
        } catch (error: Exception) {
            lifecycleFailure(
                AniyomiLifecycleFailureCode.STATE_PERSISTENCE,
                "Could not restore the previous Aniyomi lifecycle record",
                error,
            )
        }
    }

    private suspend fun quarantineWithFailure(
        identity: AniyomiArtifactIdentity,
        reason: String,
        original: AniyomiLifecycleException,
    ): Boolean = try {
        artifacts.quarantine(identity, reason)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (quarantineError: AniyomiLifecycleException) {
        original.addSuppressed(quarantineError)
        false
    }

    private suspend fun quarantineQuietly(identity: AniyomiArtifactIdentity, reason: String) {
        try {
            artifacts.quarantine(identity, reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            throw lifecycleFailure(
                AniyomiLifecycleFailureCode.RECOVERY_FAILED,
                "Rejected Aniyomi artifact could not be quarantined safely",
                error,
            )
        }
    }

    private suspend fun commitSelectionTransition(
        current: AniyomiArtifactIdentity?,
        next: AniyomiLifecycleRecord?,
    ) {
        val currentSelection = current?.loadReference()
        val nextSelection = next?.selected?.loadReference()
        if (currentSelection != nextSelection) {
            try {
                selectionBarrier.beforeSelectionChange(currentSelection, nextSelection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AniyomiLoadBoundaryException) {
                throw lifecycleFailure(
                    AniyomiLifecycleFailureCode.LOAD_BOUNDARY_RELEASE_FAILED,
                    "M3 could not release the selected Aniyomi identity before pointer change",
                    error,
                )
            }
        }
        state.write(next)
    }

    private fun AniyomiArtifactIdentity.loadReference(): AniyomiLoadGenerationRef =
        AniyomiLoadGenerationRef.from(this)

    private suspend fun lifecycleOutcome(
        block: suspend () -> AniyomiLifecycleOutcome,
    ): AniyomiLifecycleOutcome = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: AniyomiLifecycleException) {
        AniyomiLifecycleOutcome.Failed(error.failure)
    } catch (error: Exception) {
        AniyomiLifecycleOutcome.Failed(
            AniyomiLifecycleFailure(
                AniyomiLifecycleFailureCode.RECOVERY_FAILED,
                "Aniyomi lifecycle failed closed",
                cause = error,
            ),
        )
    }

    private data class ValidatedArtifact(
        val identity: AniyomiArtifactIdentity,
        val artifact: InspectedAniyomiApk,
    )
}

@Singleton
internal class AniyomiLifecycleStartup internal constructor(
    private val coordinator: AniyomiLifecycleCoordinator,
    private val loadBoundary: AniyomiLoadBoundary,
    private val scope: CoroutineScope,
) : AniyomiStartupGate {
    @Inject
    internal constructor(
        coordinator: AniyomiLifecycleCoordinator,
        loadBoundary: AniyomiLoadBoundary,
    ) : this(
        coordinator,
        loadBoundary,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val started = AtomicBoolean(false)
    private val completed = CompletableDeferred<AniyomiLoadBoundaryFailure?>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var fatalError: Error? = null
            val failure = try {
                recoverAll()
            } catch (cancelled: CancellationException) {
                AniyomiLoadBoundaryFailure(
                    AniyomiLoadBoundaryFailureCode.RECOVERY_FAILED,
                    "Aniyomi startup recovery was cancelled",
                    cancelled,
                )
            } catch (error: Exception) {
                AniyomiLoadBoundaryFailure(
                    AniyomiLoadBoundaryFailureCode.RECOVERY_FAILED,
                    "Aniyomi startup recovery failed unexpectedly",
                    error,
                )
            } catch (error: Error) {
                fatalError = error
                AniyomiLoadBoundaryFailure(
                    AniyomiLoadBoundaryFailureCode.RECOVERY_FAILED,
                    "Aniyomi startup recovery terminated with a fatal error",
                    error,
                )
            }
            completed.complete(failure)
            failure?.let {
                Log.e(TAG, "Aniyomi startup recovery failed: ${it.code}", it.cause)
            }
            fatalError?.let { throw it }
        }
    }

    override suspend fun awaitReady(): AniyomiLoadBoundaryFailure? {
        start()
        return completed.await()
    }

    private suspend fun recoverAll(): AniyomiLoadBoundaryFailure? {
        when (val loadRecovery = loadBoundary.recoverAfterProcessDeath()) {
            is AniyomiLoadBoundaryOutcome.Failed -> return loadRecovery.failure
            AniyomiLoadBoundaryOutcome.Disabled,
            is AniyomiLoadBoundaryOutcome.Enabled,
            is AniyomiLoadBoundaryOutcome.Released,
            is AniyomiLoadBoundaryOutcome.Activated,
            -> Unit
        }
        return when (val outcome = coordinator.recover()) {
            is AniyomiLifecycleOutcome.Failed -> AniyomiLoadBoundaryFailure(
                AniyomiLoadBoundaryFailureCode.M2_LIFECYCLE_REJECTED,
                "Static Aniyomi lifecycle startup recovery failed",
                AniyomiLifecycleException(outcome.failure),
            )

            AniyomiLifecycleOutcome.Empty,
            is AniyomiLifecycleOutcome.Selected,
            -> null
        }
    }

    private companion object {
        const val TAG = "AniyomiLifecycle"
    }
}

private fun inspectionRejected(
    failures: List<AniyomiApkInspectionFailure>,
): AniyomiLifecycleException = AniyomiLifecycleException(
    AniyomiLifecycleFailure(
        AniyomiLifecycleFailureCode.INSPECTION_REJECTED,
        "Aniyomi artifact failed full static reinspection",
        inspectionFailures = failures,
    ),
)

private fun lifecycleFailure(
    code: AniyomiLifecycleFailureCode,
    message: String,
    cause: Throwable? = null,
): AniyomiLifecycleException = AniyomiLifecycleException(
    AniyomiLifecycleFailure(code, message, cause = cause),
)
