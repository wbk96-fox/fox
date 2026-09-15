package com.foxtv.app.core.plugin.aniyomi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AniyomiLifecycleCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stagePerformsFullInspectionAfterCopyAndImmediatelyBeforeDurableSelection() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "lifecycle")
        val state = fileState(root)
        val artifacts = fileArtifacts(root)
        val inspector = CountingInspector(fixture.inspector)
        val coordinator = coordinator(fixture.policy, inspector, artifacts, state)

        val selected = assertSelected(coordinator.stageAndSelect(fixture.capability))

        assertEquals(3, inspector.calls.get())
        assertEquals(fixture.policy.policy.expectedSha256, selected.identity.generationId)
        assertFalse(selected.identity.runtimeCompatibilityVerified)
        assertFalse(selected.report.runtimeCompatibilityVerified)
        val record = state.read()
        assertEquals(AniyomiLifecyclePhase.SELECTED, record?.phase)
        assertEquals(selected.identity, record?.selected)
        assertNull(record?.previous)
        assertTrue(File(root, selected.identity.relativePath).isFile)
        assertTrue(File(root, "transactions").listFiles().orEmpty().isEmpty())

        val firstRecovery = assertSelected(coordinator.recover())
        val secondRecovery = assertSelected(coordinator.recover())
        assertEquals(selected.identity, firstRecovery.identity)
        assertEquals(firstRecovery.identity, secondRecovery.identity)
        assertTrue(firstRecovery.recovered)
        assertEquals(5, inspector.calls.get())
    }

    @Test
    fun sourceMutationAfterCapabilityIssuanceFailsCopyIntegrityAndLeavesNoSelection() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        fixture.source.appendBytes(byteArrayOf(0x42))
        val root = File(temporaryFolder.root, "mutated-source")
        val state = fileState(root)
        val coordinator = coordinator(
            fixture.policy,
            fixture.inspector,
            fileArtifacts(root),
            state,
        )

        val failed = assertFailed(coordinator.stageAndSelect(fixture.capability))

        assertEquals(AniyomiLifecycleFailureCode.COPY_INTEGRITY_MISMATCH, failed.failure.code)
        assertNull(state.read())
        assertTrue(File(root, "transactions").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun mutationOfPublishedBytesBeforeSelectionIsCaughtByImmediateFullReinspection() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "mutated-generation")
        val state = fileState(root)
        val mutating = object : AniyomiStaticApkInspector {
            private var calls = 0
            override fun inspect(apk: File, policy: AniyomiApkPolicy): AniyomiApkInspectionResult {
                calls += 1
                if (calls == 3) {
                    check(apk.setWritable(true))
                    apk.appendBytes(byteArrayOf(0x7f))
                }
                return fixture.inspector.inspect(apk, policy)
            }
        }
        val coordinator = coordinator(fixture.policy, mutating, fileArtifacts(root), state)

        val failed = assertFailed(coordinator.stageAndSelect(fixture.capability))

        assertEquals(AniyomiLifecycleFailureCode.INSPECTION_REJECTED, failed.failure.code)
        assertEquals(AniyomiApkRejectionCode.SIZE_MISMATCH, failed.failure.inspectionFailures.single().code)
        assertNull(state.read())
        assertTrue(File(root, "generations").listFiles().orEmpty().isEmpty())

        val retried = assertSelected(coordinator.stageAndSelect(fixture.capability))
        assertEquals(fixture.policy.policy.expectedSha256, retried.identity.generationId)
    }

    @Test
    fun lifecyclePropagatesRealSignerRejectionFromCompleteInspector() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val rejectingInspector = AniyomiExtensionApkInspector(
            AniyomiPackageArchiveReader {
                AniyomiApkTestFixtures.validPackageFacts(
                    fixture.policy.policy,
                    certificateSha256 = setOf("f".repeat(64)),
                )
            },
        )
        val root = File(temporaryFolder.root, "bad-signer")
        val state = fileState(root)
        val coordinator = coordinator(fixture.policy, rejectingInspector, fileArtifacts(root), state)

        val failed = assertFailed(coordinator.stageAndSelect(fixture.capability))

        assertEquals(AniyomiLifecycleFailureCode.INSPECTION_REJECTED, failed.failure.code)
        assertEquals(AniyomiApkRejectionCode.CERTIFICATE_MISMATCH, failed.failure.inspectionFailures.single().code)
        assertNull(state.read())
    }

    @Test
    fun metadataCommitFailureRestoresPreviouslySelectedPointer() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "metadata-failure")
        val delegateState = fileState(root)
        val artifacts = fileArtifacts(root)
        val initialCoordinator = coordinator(fixture.policy, fixture.inspector, artifacts, delegateState)
        val baseline = assertSelected(initialCoordinator.stageAndSelect(fixture.capability))
        val failingState = object : AniyomiLifecycleStateStore by delegateState {
            private var failed = false
            override suspend fun write(record: AniyomiLifecycleRecord?) {
                if (!failed && record?.phase == AniyomiLifecyclePhase.CLEANUP_PENDING) {
                    failed = true
                    throw AniyomiLifecycleException(
                        AniyomiLifecycleFailure(
                            AniyomiLifecycleFailureCode.STATE_PERSISTENCE,
                            "injected metadata commit failure",
                        ),
                    )
                }
                delegateState.write(record)
            }
        }
        val coordinator = coordinator(fixture.policy, fixture.inspector, artifacts, failingState)

        val failed = assertFailed(coordinator.stageAndSelect(fixture.capability))

        assertEquals(AniyomiLifecycleFailureCode.STATE_PERSISTENCE, failed.failure.code)
        val durable = delegateState.read()
        assertEquals(AniyomiLifecyclePhase.SELECTED, durable?.phase)
        assertEquals(baseline.identity, durable?.selected)
    }

    @Test
    fun cancellationPropagatesAndCleansPreparedTransactionWithoutPublishingSuccess() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "cancelled")
        val state = fileState(root)
        val delegate = fileArtifacts(root)
        val started = CompletableDeferred<Unit>()
        val blockingStore = object : AniyomiArtifactStore by delegate {
            override suspend fun copyCapabilityToTransaction(
                source: InspectedAniyomiApk,
                transactionId: String,
                policy: AniyomiTrustedPolicy,
            ): File {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val coordinator = coordinator(fixture.policy, fixture.inspector, blockingStore, state)
        val operation = launch { coordinator.stageAndSelect(fixture.capability) }
        started.await()

        operation.cancelAndJoin()

        assertTrue(operation.isCancelled)
        assertNull(state.read())
        assertTrue(File(root, "transactions").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun stageAndConcurrentRecoveryShareOneMutex() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "serialized")
        val delegateState = fileState(root)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val trackingState = object : AniyomiLifecycleStateStore by delegateState {
            override suspend fun read(): AniyomiLifecycleRecord? = tracked(active, maxActive) {
                delay(5)
                delegateState.read()
            }

            override suspend fun write(record: AniyomiLifecycleRecord?) = tracked(active, maxActive) {
                delay(5)
                delegateState.write(record)
            }
        }
        val coordinator = coordinator(
            fixture.policy,
            fixture.inspector,
            fileArtifacts(root),
            trackingState,
        )

        val staged = async { coordinator.stageAndSelect(fixture.capability) }
        val recovered = async { coordinator.recover() }
        assertSelected(staged.await())
        assertSelected(recovered.await())

        assertEquals(1, maxActive.get())
    }

    @Test
    fun everyJournalPhaseRecoversAndSecondRecoveryIsIdempotent() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "phase-recovery")
        val state = fileState(root)
        val artifacts = fileArtifacts(root)
        val coordinator = coordinator(fixture.policy, fixture.inspector, artifacts, state)
        val selected = assertSelected(coordinator.stageAndSelect(fixture.capability)).identity

        AniyomiLifecyclePhase.entries.forEachIndexed { index, phase ->
            val transactionId = validTransactionId(index + 10)
            val needsTransaction = phase == AniyomiLifecyclePhase.PREPARING ||
                phase == AniyomiLifecyclePhase.PREPARED ||
                phase == AniyomiLifecyclePhase.CLEANUP_PENDING
            val transactionCandidate = if (needsTransaction) {
                artifacts.createTransaction(transactionId)
                artifacts.copyCapabilityToTransaction(fixture.capability, transactionId, fixture.policy)
                AniyomiArtifactIdentity.fromInspection(
                    fixture.capability,
                    AniyomiStoredArtifactKind.TRANSACTION,
                    transactionId,
                    fixture.policy,
                )
            } else {
                null
            }
            val record = when (phase) {
                AniyomiLifecyclePhase.PREPARING,
                AniyomiLifecyclePhase.PREPARED,
                -> AniyomiLifecycleRecord(
                    policyId = fixture.policy.id,
                    policyVersion = fixture.policy.version,
                    phase = phase,
                    transactionId = transactionId,
                    selected = selected,
                    candidate = requireNotNull(transactionCandidate),
                )

                AniyomiLifecyclePhase.SELECTING -> AniyomiLifecycleRecord(
                    policyId = fixture.policy.id,
                    policyVersion = fixture.policy.version,
                    phase = phase,
                    selected = selected,
                    candidate = selected,
                )

                AniyomiLifecyclePhase.SELECTED -> AniyomiLifecycleRecord(
                    policyId = fixture.policy.id,
                    policyVersion = fixture.policy.version,
                    phase = phase,
                    selected = selected,
                )

                AniyomiLifecyclePhase.ROLLBACK_REQUIRED -> AniyomiLifecycleRecord(
                    policyId = fixture.policy.id,
                    policyVersion = fixture.policy.version,
                    phase = phase,
                    selected = selected,
                    previous = selected,
                )

                AniyomiLifecyclePhase.CLEANUP_PENDING -> AniyomiLifecycleRecord(
                    policyId = fixture.policy.id,
                    policyVersion = fixture.policy.version,
                    phase = phase,
                    transactionId = transactionId,
                    selected = selected,
                )
            }
            state.write(record)

            val first = assertSelected(coordinator.recover())
            val durableAfterFirst = state.read()
            val second = assertSelected(coordinator.recover())
            val durableAfterSecond = state.read()

            assertEquals("phase=$phase", selected.generationId, first.identity.generationId)
            assertEquals("phase=$phase", first.identity, second.identity)
            assertEquals("phase=$phase", durableAfterFirst, durableAfterSecond)
            assertEquals("phase=$phase", AniyomiLifecyclePhase.SELECTED, durableAfterSecond?.phase)
            assertTrue("phase=$phase", File(root, "transactions").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun missingSelectedRollsBackToFullyInspectedPreviousAndInvalidPreviousIsDropped() = runTest {
        val root = File(temporaryFolder.root, "missing-selected")
        val state = fileState(root)
        val artifacts = fileArtifacts(root)
        val selectedFile = createGeneration(root, "selected bytes")
        val previousFile = createGeneration(root, "previous bytes")
        val selectedCapability = AniyomiLifecycleTestFixtures.fakeCapability(selectedFile)
        val previousCapability = AniyomiLifecycleTestFixtures.fakeCapability(previousFile)
        val policy = AniyomiLifecycleTestFixtures.trustedPolicyFor(selectedCapability)
        val previousPolicy = AniyomiLifecycleTestFixtures.trustedPolicyFor(
            previousCapability,
            version = 2,
        )
        val selected = AniyomiLifecycleTestFixtures.identity(selectedFile, policy)
        val previous = AniyomiLifecycleTestFixtures.identity(previousFile, previousPolicy)
        state.write(selectedRecord(policy, selected, previous))
        assertTrue(selectedFile.delete())
        val coordinator = coordinator(
            policy,
            AniyomiLifecycleTestFixtures.fakeInspector(),
            artifacts,
            state,
            additionalPolicies = listOf(previousPolicy),
        )

        val rolledBack = assertSelected(coordinator.recover())

        assertEquals(previous.generationId, rolledBack.identity.generationId)
        assertNull(state.read()?.previous)

        val invalidPreviousFile = createGeneration(root, "invalid previous")
        val invalidCapability = AniyomiLifecycleTestFixtures.fakeCapability(invalidPreviousFile)
        val invalidPolicy = AniyomiLifecycleTestFixtures.trustedPolicyFor(
            invalidCapability,
            version = 3,
        )
        val invalidPrevious = AniyomiLifecycleTestFixtures.identity(invalidPreviousFile, invalidPolicy)
        assertTrue(invalidPreviousFile.setWritable(true))
        invalidPreviousFile.appendText("mutation")
        state.write(selectedRecord(previousPolicy, rolledBack.identity, invalidPrevious))
        val validationCoordinator = coordinator(
            previousPolicy,
            AniyomiLifecycleTestFixtures.fakeInspector(),
            artifacts,
            state,
            additionalPolicies = listOf(policy, invalidPolicy),
        )
        val retained = assertSelected(validationCoordinator.recover())
        assertEquals(rolledBack.identity, retained.identity)
        assertNull(state.read()?.previous)
    }

    @Test
    fun missingSelectedAndPreviousFailsClosedAndClearsDurablePointer() = runTest {
        val root = File(temporaryFolder.root, "missing-both")
        val state = fileState(root)
        val selectedFile = createGeneration(root, "selected")
        val previousFile = createGeneration(root, "previous")
        val selectedCapability = AniyomiLifecycleTestFixtures.fakeCapability(selectedFile)
        val previousCapability = AniyomiLifecycleTestFixtures.fakeCapability(previousFile)
        val policy = AniyomiLifecycleTestFixtures.trustedPolicyFor(selectedCapability)
        val previousPolicy = AniyomiLifecycleTestFixtures.trustedPolicyFor(
            previousCapability,
            version = 2,
        )
        val selected = AniyomiLifecycleTestFixtures.identity(selectedFile, policy)
        val previous = AniyomiLifecycleTestFixtures.identity(previousFile, previousPolicy)
        state.write(selectedRecord(policy, selected, previous))
        assertTrue(selectedFile.delete())
        assertTrue(previousFile.delete())
        val coordinator = coordinator(
            policy,
            AniyomiLifecycleTestFixtures.fakeInspector(),
            fileArtifacts(root),
            state,
            additionalPolicies = listOf(previousPolicy),
        )

        val failed = assertFailed(coordinator.recover())

        assertEquals(AniyomiLifecycleFailureCode.RECOVERY_FAILED, failed.failure.code)
        assertNull(state.read())
    }

    @Test
    fun cleanupFailureLeavesDurableCleanupPendingAndRecoveryRetriesIt() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "cleanup-retry")
        val state = fileState(root)
        val delegate = fileArtifacts(root)
        val failingStore = object : AniyomiArtifactStore by delegate {
            private var shouldFail = true
            override suspend fun cleanupTransaction(transactionId: String) {
                if (shouldFail) {
                    shouldFail = false
                    throw AniyomiLifecycleException(
                        AniyomiLifecycleFailure(
                            AniyomiLifecycleFailureCode.CLEANUP_FAILED,
                            "injected cleanup failure",
                        ),
                    )
                }
                delegate.cleanupTransaction(transactionId)
            }
        }
        val coordinator = coordinator(fixture.policy, fixture.inspector, failingStore, state)

        val failed = assertFailed(coordinator.stageAndSelect(fixture.capability))
        assertEquals(AniyomiLifecycleFailureCode.CLEANUP_FAILED, failed.failure.code)
        assertEquals(AniyomiLifecyclePhase.CLEANUP_PENDING, state.read()?.phase)

        val recovered = assertSelected(coordinator.recover())
        assertFalse(recovered.identity.runtimeCompatibilityVerified)
        assertEquals(AniyomiLifecyclePhase.SELECTED, state.read()?.phase)
        assertTrue(File(root, "transactions").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun preSelectionCleanupFailureKeepsRecoverableTransactionJournal() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "preselection-cleanup")
        val state = fileState(root)
        val delegate = fileArtifacts(root)
        val failingStore = object : AniyomiArtifactStore by delegate {
            private var failCleanup = true
            override suspend fun cleanupTransaction(transactionId: String) {
                if (failCleanup) {
                    failCleanup = false
                    throw AniyomiLifecycleException(
                        AniyomiLifecycleFailure(
                            AniyomiLifecycleFailureCode.CLEANUP_FAILED,
                            "injected pre-selection cleanup failure",
                        ),
                    )
                }
                delegate.cleanupTransaction(transactionId)
            }
        }
        val rejectingInspector = AniyomiStaticApkInspector { _, _ ->
            AniyomiApkInspectionResult.Rejected(
                listOf(
                    AniyomiApkInspectionFailure(
                        AniyomiApkInspectionStage.SIGNATURE,
                        AniyomiApkRejectionCode.SIGNATURE_INVALID,
                        "injected rejection",
                    ),
                ),
            )
        }
        val coordinator = coordinator(fixture.policy, rejectingInspector, failingStore, state)

        assertFailed(coordinator.stageAndSelect(fixture.capability))
        val interrupted = requireNotNull(state.read())
        assertEquals(AniyomiLifecyclePhase.PREPARING, interrupted.phase)
        assertNotNull(interrupted.transactionId)
        assertTrue(File(root, "transactions/${interrupted.transactionId}").isDirectory)

        val fresh = coordinator(fixture.policy, fixture.inspector, delegate, fileState(root))
        assertEquals(AniyomiLifecycleOutcome.Empty, fresh.recover())
        assertNull(state.read())
        assertTrue(File(root, "transactions").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationInterruptsFullInspectionAndRetainsNoFalseSelection() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "inspection-cancel")
        val state = fileState(root)
        val started = CompletableDeferred<Unit>()
        val interrupted = CompletableDeferred<Unit>()
        val blockingInspector = AniyomiStaticApkInspector { _, _ ->
            started.complete(Unit)
            try {
                while (true) {
                    ensureAniyomiInspectionActive()
                    Thread.sleep(1_000)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } finally {
                interrupted.complete(Unit)
            }
        }
        val coordinator = coordinator(
            fixture.policy,
            blockingInspector,
            fileArtifacts(root),
            state,
        )
        val operation = launch { coordinator.stageAndSelect(fixture.capability) }
        started.await()

        operation.cancelAndJoin()
        interrupted.await()

        assertTrue(operation.isCancelled)
        assertNull(state.read())
        assertTrue(File(root, "transactions").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun realStageTransitionsRecoverWithFreshInstancesAfterSimulatedProcessDeath() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val crashPhases = listOf(
            AniyomiLifecyclePhase.PREPARING,
            AniyomiLifecyclePhase.PREPARED,
            AniyomiLifecyclePhase.SELECTING,
            AniyomiLifecyclePhase.CLEANUP_PENDING,
            AniyomiLifecyclePhase.SELECTED,
        )

        crashPhases.forEach { crashPhase ->
            val root = File(temporaryFolder.root, "crash-${crashPhase.name.lowercase()}")
            val durableState = fileState(root)
            val crashingState = CrashAfterPhaseStateStore(durableState, crashPhase)
            val coordinator = coordinator(
                fixture.policy,
                fixture.inspector,
                fileArtifacts(root),
                crashingState,
            )
            var crashed = false
            try {
                coordinator.stageAndSelect(fixture.capability)
            } catch (_: SimulatedProcessDeath) {
                crashed = true
            }
            assertTrue("phase=$crashPhase", crashed)

            val fresh = coordinator(
                fixture.policy,
                fixture.inspector,
                fileArtifacts(root),
                fileState(root),
            )
            val first = fresh.recover()
            if (crashPhase == AniyomiLifecyclePhase.PREPARING) {
                assertEquals(AniyomiLifecycleOutcome.Empty, first)
            } else {
                assertSelected(first)
            }
            val firstState = fileState(root).read()
            val firstTree = treeSnapshot(root)
            val second = fresh.recover()
            if (first is AniyomiLifecycleOutcome.Selected) {
                assertEquals(first.identity, assertSelected(second).identity)
            } else {
                assertEquals(first, second)
            }
            assertEquals("phase=$crashPhase", firstState, fileState(root).read())
            assertEquals("phase=$crashPhase", firstTree, treeSnapshot(root))
        }
    }

    @Test
    fun crashAfterGenerationPublicationBeforeSelectingJournalRecoversBaselineAndAllowsRetry() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "publication-crash")
        val state = fileState(root)
        val delegate = fileArtifacts(root)
        val crashingArtifacts = object : AniyomiArtifactStore by delegate {
            private var crash = true
            override suspend fun publishGeneration(
                candidate: AniyomiArtifactIdentity,
            ): PublishedAniyomiGeneration {
                val publication = delegate.publishGeneration(candidate)
                if (crash) {
                    crash = false
                    throw SimulatedProcessDeath()
                }
                return publication
            }
        }
        val crashing = coordinator(fixture.policy, fixture.inspector, crashingArtifacts, state)
        try {
            crashing.stageAndSelect(fixture.capability)
            throw AssertionError("Expected simulated process death")
        } catch (_: SimulatedProcessDeath) {
            Unit
        }
        assertEquals(AniyomiLifecyclePhase.PREPARED, state.read()?.phase)
        assertTrue(
            File(root, "generations/${fixture.policy.policy.expectedSha256}/$ANIYOMI_APK_FILE_NAME").isFile,
        )

        val fresh = coordinator(fixture.policy, fixture.inspector, delegate, fileState(root))
        assertEquals(AniyomiLifecycleOutcome.Empty, fresh.recover())
        val stableTree = treeSnapshot(root)
        assertEquals(AniyomiLifecycleOutcome.Empty, fresh.recover())
        assertEquals(stableTree, treeSnapshot(root))

        val selected = assertSelected(fresh.stageAndSelect(fixture.capability))
        assertEquals(fixture.policy.policy.expectedSha256, selected.identity.generationId)
    }

    @Test
    fun cleanupPendingWithRejectedSelectedCleansTransactionBeforeRollback() = runTest {
        val root = File(temporaryFolder.root, "cleanup-before-rollback")
        val state = fileState(root)
        val artifacts = fileArtifacts(root)
        val selectedFile = createGeneration(root, "cleanup-selected")
        val previousFile = createGeneration(root, "cleanup-previous")
        val selectedCapability = AniyomiLifecycleTestFixtures.fakeCapability(selectedFile)
        val previousCapability = AniyomiLifecycleTestFixtures.fakeCapability(previousFile)
        val selectedPolicy = AniyomiLifecycleTestFixtures.trustedPolicyFor(selectedCapability)
        val previousPolicy = AniyomiLifecycleTestFixtures.trustedPolicyFor(previousCapability, version = 2)
        val selected = AniyomiLifecycleTestFixtures.identity(selectedFile, selectedPolicy)
        val previous = AniyomiLifecycleTestFixtures.identity(previousFile, previousPolicy)
        val transactionId = validTransactionId(90)
        artifacts.createTransaction(transactionId)
        File(root, "transactions/$transactionId/candidate.apk").writeText("leftover")
        state.write(
            AniyomiLifecycleRecord(
                policyId = selectedPolicy.id,
                policyVersion = selectedPolicy.version,
                phase = AniyomiLifecyclePhase.CLEANUP_PENDING,
                transactionId = transactionId,
                selected = selected,
                previous = previous,
            ),
        )
        assertTrue(selectedFile.delete())
        val coordinator = coordinator(
            selectedPolicy,
            AniyomiLifecycleTestFixtures.fakeInspector(),
            artifacts,
            state,
            additionalPolicies = listOf(previousPolicy),
        )

        val recovered = assertSelected(coordinator.recover())
        assertEquals(previous.generationId, recovered.identity.generationId)
        assertFalse(File(root, "transactions/$transactionId").exists())
        val snapshot = treeSnapshot(root)
        assertEquals(recovered.identity, assertSelected(coordinator.recover()).identity)
        assertEquals(snapshot, treeSnapshot(root))
    }

    @Test
    fun rollbackRequiredTransitionSurvivesProcessDeathAndRecoversIdempotently() = runTest {
        val root = File(temporaryFolder.root, "rollback-crash")
        val durableState = fileState(root)
        val selectedFile = createGeneration(root, "rollback-selected")
        val previousFile = createGeneration(root, "rollback-previous")
        val selectedCapability = AniyomiLifecycleTestFixtures.fakeCapability(selectedFile)
        val previousCapability = AniyomiLifecycleTestFixtures.fakeCapability(previousFile)
        val selectedPolicy = AniyomiLifecycleTestFixtures.trustedPolicyFor(selectedCapability)
        val previousPolicy = AniyomiLifecycleTestFixtures.trustedPolicyFor(previousCapability, version = 2)
        val selected = AniyomiLifecycleTestFixtures.identity(selectedFile, selectedPolicy)
        val previous = AniyomiLifecycleTestFixtures.identity(previousFile, previousPolicy)
        durableState.write(selectedRecord(selectedPolicy, selected, previous))
        assertTrue(selectedFile.delete())
        val crashing = coordinator(
            selectedPolicy,
            AniyomiLifecycleTestFixtures.fakeInspector(),
            fileArtifacts(root),
            CrashAfterPhaseStateStore(durableState, AniyomiLifecyclePhase.ROLLBACK_REQUIRED),
            additionalPolicies = listOf(previousPolicy),
        )
        try {
            crashing.recover()
            throw AssertionError("Expected simulated process death")
        } catch (_: SimulatedProcessDeath) {
            Unit
        }
        assertEquals(AniyomiLifecyclePhase.ROLLBACK_REQUIRED, durableState.read()?.phase)

        val fresh = coordinator(
            selectedPolicy,
            AniyomiLifecycleTestFixtures.fakeInspector(),
            fileArtifacts(root),
            fileState(root),
            additionalPolicies = listOf(previousPolicy),
        )
        val first = assertSelected(fresh.recover())
        val firstTree = treeSnapshot(root)
        assertEquals(previous.generationId, first.identity.generationId)
        assertEquals(first.identity, assertSelected(fresh.recover()).identity)
        assertEquals(firstTree, treeSnapshot(root))
    }

    @Test
    fun explicitRollbackUsesTwoHostOwnedExactHashPoliciesAndReinspectsPreviousTwice() = runTest {
        val root = File(temporaryFolder.root, "explicit-rollback")
        val sourceDirectory = File(temporaryFolder.root, "rollback-sources").apply { mkdirs() }
        val selectedSource = AniyomiApkTestFixtures.writeApk(sourceDirectory, name = "selected.apk")
        val previousSource = AniyomiApkTestFixtures.writeApk(
            sourceDirectory,
            name = "previous.apk",
            extraEntries = mapOf("res/raw/revision.txt" to "previous".toByteArray()),
        )
        val selectedApkPolicy = AniyomiApkTestFixtures.policyFor(selectedSource)
        val previousApkPolicy = AniyomiApkTestFixtures.policyFor(previousSource)
        val realInspector = AniyomiExtensionApkInspector(
            AniyomiPackageArchiveReader {
                AniyomiApkTestFixtures.validPackageFacts(selectedApkPolicy)
            },
        )
        val selectedCapability = AniyomiLifecycleTestFixtures.accepted(
            realInspector.inspect(selectedSource, selectedApkPolicy),
        )
        val previousCapability = AniyomiLifecycleTestFixtures.accepted(
            realInspector.inspect(previousSource, previousApkPolicy),
        )
        val selectedPolicy = AniyomiTrustedPolicy("test-jellyfin", 1, selectedApkPolicy)
        val previousPolicy = AniyomiTrustedPolicy("test-jellyfin", 2, previousApkPolicy)
        val selectedFile = installGeneration(root, selectedCapability)
        val previousFile = installGeneration(root, previousCapability)
        val selected = AniyomiArtifactIdentity.fromInspection(
            selectedCapability,
            AniyomiStoredArtifactKind.GENERATION,
            transactionId = null,
            selectedPolicy,
        )
        val previous = AniyomiArtifactIdentity.fromInspection(
            previousCapability,
            AniyomiStoredArtifactKind.GENERATION,
            transactionId = null,
            previousPolicy,
        )
        val state = fileState(root)
        state.write(selectedRecord(selectedPolicy, selected, previous))
        val inspector = CountingInspector(realInspector)
        val coordinator = coordinator(
            selectedPolicy,
            inspector,
            fileArtifacts(root),
            state,
            additionalPolicies = listOf(previousPolicy),
        )

        val rolledBack = assertSelected(coordinator.rollback())

        assertEquals(previous.generationId, rolledBack.identity.generationId)
        assertEquals(selected.generationId, state.read()?.previous?.generationId)
        assertEquals(previousPolicy.version, state.read()?.policyVersion)
        assertEquals(4, inspector.calls.get())
        assertEquals(1, inspector.inspectedPaths.count { it == selectedFile.canonicalPath })
        assertEquals(3, inspector.inspectedPaths.count { it == previousFile.canonicalPath })
        assertFalse(rolledBack.report.runtimeCompatibilityVerified)
    }

    private fun coordinator(
        policy: AniyomiTrustedPolicy,
        inspector: AniyomiStaticApkInspector,
        artifacts: AniyomiArtifactStore,
        state: AniyomiLifecycleStateStore,
        additionalPolicies: List<AniyomiTrustedPolicy> = emptyList(),
        selectionBarrier: AniyomiSelectionChangeBarrier =
            AniyomiSelectionChangeBarrier { _, _ -> Unit },
    ): AniyomiLifecycleCoordinator = AniyomiLifecycleCoordinator(
        inspector = inspector,
        policyProvider = AniyomiLifecycleTestFixtures.policyRegistry(policy, additionalPolicies),
        artifacts = artifacts,
        state = state,
        selectionBarrier = selectionBarrier,
    )

    private fun fileState(root: File): FileAniyomiLifecycleStateStore =
        FileAniyomiLifecycleStateStore(AniyomiLifecycleRoot.forTests(root))

    private fun fileArtifacts(root: File): FileAniyomiArtifactStore =
        FileAniyomiArtifactStore(AniyomiLifecycleRoot.forTests(root))

    private fun selectedRecord(
        policy: AniyomiTrustedPolicy,
        selected: AniyomiArtifactIdentity,
        previous: AniyomiArtifactIdentity? = null,
    ): AniyomiLifecycleRecord = AniyomiLifecycleRecord(
        policyId = policy.id,
        policyVersion = policy.version,
        phase = AniyomiLifecyclePhase.SELECTED,
        selected = selected,
        previous = previous,
    )

    private fun installGeneration(root: File, capability: InspectedAniyomiApk): File {
        val destination = File(
            root,
            "generations/${capability.report.sha256}/$ANIYOMI_APK_FILE_NAME",
        )
        requireNotNull(destination.parentFile).mkdirs()
        capability.file.copyTo(destination)
        check(destination.setReadOnly() && destination.hasReadOnlyAniyomiCodePermissions())
        return destination.canonicalFile
    }

    private fun createGeneration(root: File, content: String): File {
        val staging = File(temporaryFolder.root, "seed-${content.hashCode()}.apk").apply {
            writeText(content)
        }
        val capability = AniyomiLifecycleTestFixtures.fakeCapability(staging)
        val generation = File(
            root,
            "generations/${capability.report.sha256}/$ANIYOMI_APK_FILE_NAME",
        )
        requireNotNull(generation.parentFile).mkdirs()
        staging.copyTo(generation)
        check(generation.setReadOnly() && generation.hasReadOnlyAniyomiCodePermissions())
        return generation.canonicalFile
    }

    private fun assertSelected(outcome: AniyomiLifecycleOutcome): AniyomiLifecycleOutcome.Selected {
        assertTrue("Expected selected outcome, got $outcome", outcome is AniyomiLifecycleOutcome.Selected)
        return outcome as AniyomiLifecycleOutcome.Selected
    }

    private fun assertFailed(outcome: AniyomiLifecycleOutcome): AniyomiLifecycleOutcome.Failed {
        assertTrue("Expected failed outcome, got $outcome", outcome is AniyomiLifecycleOutcome.Failed)
        return outcome as AniyomiLifecycleOutcome.Failed
    }

    private fun validTransactionId(value: Int): String =
        "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private suspend fun <T> tracked(
        active: AtomicInteger,
        maxActive: AtomicInteger,
        block: suspend () -> T,
    ): T {
        val current = active.incrementAndGet()
        maxActive.updateAndGet { previous -> maxOf(previous, current) }
        return try {
            block()
        } finally {
            active.decrementAndGet()
        }
    }

    private fun treeSnapshot(root: File): List<String> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .map { entry ->
                val relative = entry.relativeTo(root).path.ifEmpty { "." }
                if (entry.isDirectory) {
                    "D:$relative"
                } else {
                    "F:$relative:${entry.length()}:${entry.readBytes().contentHashCode()}"
                }
            }
            .sorted()
            .toList()
    }

    private class CrashAfterPhaseStateStore(
        private val delegate: AniyomiLifecycleStateStore,
        private val phase: AniyomiLifecyclePhase,
    ) : AniyomiLifecycleStateStore by delegate {
        private val crashed = AtomicBoolean(false)

        override suspend fun write(record: AniyomiLifecycleRecord?) {
            delegate.write(record)
            if (record?.phase == phase && crashed.compareAndSet(false, true)) {
                throw SimulatedProcessDeath()
            }
        }
    }

    private class SimulatedProcessDeath : Error("simulated process death")

    private class CountingInspector(
        private val delegate: AniyomiStaticApkInspector,
    ) : AniyomiStaticApkInspector {
        val calls = AtomicInteger(0)
        val inspectedPaths = CopyOnWriteArrayList<String>()

        override fun inspect(apk: File, policy: AniyomiApkPolicy): AniyomiApkInspectionResult {
            calls.incrementAndGet()
            inspectedPaths += apk.canonicalPath
            return delegate.inspect(apk, policy)
        }
    }
}
