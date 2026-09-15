package com.foxtv.app.core.plugin.aniyomi

import eu.kanade.tachiyomi.animesource.AniyomiLoadTestProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AniyomiLoadBoundaryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validSelectedGenerationLoadsThroughExactRealIsolationBoundary() = runTest {
        val artifact = controlled("valid", ControlledAniyomiFactoryBehavior.SUCCESS)
        val environment = environment(File(temporaryFolder.root, "valid-env"), listOf(artifact))
        val selected = environment.stage(artifact)

        val activated = assertActivated(environment.loadCoordinator.enableAndActivate())

        assertEquals(selected.identity.generationId, activated.generation.generationId)
        assertFalse(activated.reusedExisting)
        assertEquals(1, environment.loaders.openCount.get())
        assertEquals(
            File(environment.root, selected.identity.relativePath).canonicalFile,
            environment.loaders.openedSources.single(),
        )
        val activeMetadata = requireNotNull(environment.boundary.activeMetadata())
        assertEquals(activated.sessionId, activeMetadata.sessionId)
        assertEquals(activated.generation, activeMetadata.generation)
        assertTrue(activeMetadata.reusedExisting)
        val durable = environment.loadState.read()
        assertEquals(AniyomiLoadBoundaryPhase.ACTIVE, durable?.phase)
        assertEquals(activated.generation, durable?.target)
        assertFalse(selected.identity.runtimeCompatibilityVerified)
        assertTrue(
            File(environment.codeCacheRoot, activated.generation.generationId).isDirectory,
        )
    }

    @Test
    fun duplicateActivationReusesSingleOwnerAndDoesNotCreateSecondLoader() = runTest {
        val artifact = controlled("duplicate", ControlledAniyomiFactoryBehavior.SUCCESS)
        val environment = environment(File(temporaryFolder.root, "duplicate-env"), listOf(artifact))
        environment.stage(artifact)
        environment.loadCoordinator.enable()

        val first = assertActivated(environment.loadCoordinator.activateSelected())
        val second = assertActivated(environment.loadCoordinator.activateSelected())

        assertEquals(first.sessionId, second.sessionId)
        assertTrue(second.reusedExisting)
        assertEquals(1, environment.loaders.openCount.get())
        assertEquals(1, environment.loaders.handles.size)
    }

    @Test
    fun disabledExtensionCannotOpenLoaderAndDisablePreventsNewInstances() = runTest {
        val artifact = controlled("disabled", ControlledAniyomiFactoryBehavior.SUCCESS)
        val environment = environment(File(temporaryFolder.root, "disabled-env"), listOf(artifact))
        environment.stage(artifact)

        val disabled = assertLoadFailed(environment.loadCoordinator.activateSelected())
        assertEquals(AniyomiLoadBoundaryFailureCode.DISABLED, disabled.failure.code)
        assertEquals(0, environment.loaders.openCount.get())

        assertActivated(environment.loadCoordinator.enableAndActivate())
        val firstHandle = environment.loaders.handles.single()
        assertEquals(AniyomiLoadBoundaryOutcome.Disabled, environment.loadCoordinator.disable())
        assertTrue(firstHandle.isReleased)
        assertNull(environment.boundary.activeMetadata())
        assertEquals(AniyomiLoadBoundaryPhase.DISABLED, environment.loadState.read()?.phase)

        val afterDisable = assertLoadFailed(environment.loadCoordinator.activateSelected())
        assertEquals(AniyomiLoadBoundaryFailureCode.DISABLED, afterDisable.failure.code)
        assertEquals(1, environment.loaders.openCount.get())
    }

    @Test
    fun releaseDropsFactoryAndLoaderThenAllowsFreshOwnedSession() = runTest {
        val artifact = controlled("release", ControlledAniyomiFactoryBehavior.SUCCESS)
        val environment = environment(File(temporaryFolder.root, "release-env"), listOf(artifact))
        environment.stage(artifact)
        val first = assertActivated(environment.loadCoordinator.enableAndActivate())
        val firstHandle = environment.loaders.handles.single()

        assertEquals(
            AniyomiLoadBoundaryOutcome.Released(enabled = true),
            environment.loadCoordinator.release(),
        )
        assertTrue(firstHandle.isReleased)
        assertNull(environment.boundary.activeMetadata())
        assertEquals(AniyomiLoadBoundaryPhase.ENABLED_IDLE, environment.loadState.read()?.phase)
        assertFalse(File(environment.codeCacheRoot, first.generation.generationId).exists())

        val second = assertActivated(environment.loadCoordinator.activateSelected())
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(2, environment.loaders.openCount.get())
    }

    @Test
    fun childCannotSeeAmbientFoxTvClasspathButStillLoadsParentHostContract() = runTest {
        val artifact = controlled("isolation", ControlledAniyomiFactoryBehavior.CLASSPATH_ISOLATION)
        val environment = environment(File(temporaryFolder.root, "isolation-env"), listOf(artifact))
        environment.stage(artifact)

        val activated = assertActivated(environment.loadCoordinator.enableAndActivate())

        assertEquals(artifact.policy.policy.expectedSha256, activated.generation.generationId)
        assertEquals(1, environment.loaders.openCount.get())
    }

    @Test
    fun missingChildClassFailsClosedAndReleasesPartialLoaderAndCache() = runTest {
        val artifact = controlled("missing", ControlledAniyomiFactoryBehavior.CLASS_MISSING)
        val environment = environment(File(temporaryFolder.root, "missing-env"), listOf(artifact))
        environment.stage(artifact)

        val failed = assertLoadFailed(environment.loadCoordinator.enableAndActivate())

        assertEquals(AniyomiLoadBoundaryFailureCode.CLASS_NOT_FOUND, failed.failure.code)
        assertNull(environment.boundary.activeMetadata())
        assertTrue(environment.loaders.handles.single().isReleased)
        assertFalse(File(environment.codeCacheRoot, artifact.policy.policy.expectedSha256).exists())
        val durable = environment.loadState.read()
        assertEquals(AniyomiLoadBoundaryPhase.ENABLED_IDLE, durable?.phase)
        assertEquals(artifact.policy.policy.expectedSha256, durable?.failedGeneration?.generationId)
    }

    @Test
    fun constructorFailureAndMissingConstructorAreTypedAndNeverPublishSession() = runTest {
        val cases = listOf(
            ControlledAniyomiFactoryBehavior.CONSTRUCTOR_FAILURE to
                AniyomiLoadBoundaryFailureCode.CONSTRUCTOR_FAILED,
            ControlledAniyomiFactoryBehavior.PRIVATE_ONLY_CONSTRUCTOR to
                AniyomiLoadBoundaryFailureCode.CONSTRUCTOR_MISSING,
        )
        cases.forEachIndexed { index, (behavior, expectedCode) ->
            val artifact = controlled("constructor-$index", behavior)
            val environment = environment(
                File(temporaryFolder.root, "constructor-env-$index"),
                listOf(artifact),
            )
            environment.stage(artifact)

            val failed = assertLoadFailed(environment.loadCoordinator.enableAndActivate())

            assertEquals(expectedCode, failed.failure.code)
            assertNull(environment.boundary.activeMetadata())
            assertTrue(environment.loaders.handles.single().isReleased)
        }
    }

    @Test
    fun staticInitializationAndTypeMismatchFailuresRemainDistinct() = runTest {
        val cases = listOf(
            ControlledAniyomiFactoryBehavior.INITIALIZATION_FAILURE to
                AniyomiLoadBoundaryFailureCode.INITIALIZATION_FAILED,
            ControlledAniyomiFactoryBehavior.TYPE_MISMATCH to
                AniyomiLoadBoundaryFailureCode.TYPE_MISMATCH,
        )
        cases.forEachIndexed { index, (behavior, expectedCode) ->
            val artifact = controlled("initialization-$index", behavior)
            val environment = environment(
                File(temporaryFolder.root, "initialization-env-$index"),
                listOf(artifact),
            )
            environment.stage(artifact)

            val failed = assertLoadFailed(environment.loadCoordinator.enableAndActivate())

            assertEquals(expectedCode, failed.failure.code)
            assertNull(environment.boundary.activeMetadata())
            assertTrue(environment.loaders.handles.single().isReleased)
        }
    }

    @Test
    fun cancellationDuringRealChildConstructionInterruptsAndCleansWithoutActivePublication() = runTest {
        AniyomiLoadTestProbe.reset()
        val artifact = controlled("cancel", ControlledAniyomiFactoryBehavior.CANCELLATION)
        val environment = environment(File(temporaryFolder.root, "cancel-env"), listOf(artifact))
        environment.stage(artifact)
        environment.loadCoordinator.enable()
        val operation = launch(Dispatchers.Default) {
            environment.loadCoordinator.activateSelected()
        }
        assertTrue(withContext(Dispatchers.IO) { AniyomiLoadTestProbe.awaitEntered() })

        operation.cancel()
        operation.join()

        assertTrue(operation.isCancelled)
        assertEquals(1, AniyomiLoadTestProbe.interruptionCount())
        assertNull(environment.boundary.activeMetadata())
        assertTrue(environment.loaders.handles.single().isReleased)
        assertEquals(AniyomiLoadBoundaryPhase.ENABLED_IDLE, environment.loadState.read()?.phase)
        assertFalse(File(environment.codeCacheRoot, artifact.policy.policy.expectedSha256).exists())
    }

    @Test
    fun wrongPolicyTupleIsRejectedBeforeAnyClassLoaderIsOpened() = runTest {
        val artifact = controlled("wrong-policy", ControlledAniyomiFactoryBehavior.SUCCESS)
        val environment = environment(File(temporaryFolder.root, "wrong-policy-env"), listOf(artifact))
        environment.stage(artifact)
        environment.policies.remove(artifact.policy)

        val failed = assertLoadFailed(environment.loadCoordinator.enableAndActivate())

        assertEquals(AniyomiLoadBoundaryFailureCode.M2_LIFECYCLE_REJECTED, failed.failure.code)
        assertEquals(0, environment.loaders.openCount.get())
        assertNull(environment.boundary.activeMetadata())
    }

    @Test
    fun wrongSignerOnFreshReinspectionIsRejectedBeforeLoad() = runTest {
        val artifact = controlled("wrong-signer", ControlledAniyomiFactoryBehavior.SUCCESS)
        val switchable = SwitchableInspector(inspectorFor(listOf(artifact)))
        val environment = environment(
            File(temporaryFolder.root, "wrong-signer-env"),
            listOf(artifact),
            inspector = switchable,
        )
        environment.stage(artifact)
        switchable.delegate = AniyomiExtensionApkInspector(
            AniyomiPackageArchiveReader {
                AniyomiApkTestFixtures.validPackageFacts(
                    artifact.policy.policy,
                    certificateSha256 = setOf("f".repeat(64)),
                )
            },
        )

        val failed = assertLoadFailed(environment.loadCoordinator.enableAndActivate())

        assertEquals(AniyomiLoadBoundaryFailureCode.M2_LIFECYCLE_REJECTED, failed.failure.code)
        assertEquals(0, environment.loaders.openCount.get())
        assertNull(environment.boundary.activeMetadata())
    }

    @Test
    fun wrongGenerationRedirectIsRejectedByExactM2IdentityBeforeLoad() = runTest {
        val selectedArtifact = controlled("redirect-selected", ControlledAniyomiFactoryBehavior.SUCCESS)
        val wrongArtifact = controlled("redirect-wrong", ControlledAniyomiFactoryBehavior.CLASSPATH_ISOLATION, 2)
        val redirect = RedirectingArtifactStoreHolder(wrongArtifact.source)
        val environment = environment(
            File(temporaryFolder.root, "redirect-env"),
            listOf(selectedArtifact, wrongArtifact),
            storeTransform = redirect::wrap,
        )
        environment.stage(selectedArtifact)
        redirect.enabled.set(true)

        val failed = assertLoadFailed(environment.loadCoordinator.enableAndActivate())

        assertEquals(AniyomiLoadBoundaryFailureCode.M2_LIFECYCLE_REJECTED, failed.failure.code)
        assertEquals(0, environment.loaders.openCount.get())
        assertNull(environment.boundary.activeMetadata())
    }

    @Test
    fun wrongShaAndPoisonedSelectedBytesCannotReachClassLoader() = runTest {
        val artifact = controlled("poisoned", ControlledAniyomiFactoryBehavior.SUCCESS)
        val environment = environment(File(temporaryFolder.root, "poisoned-env"), listOf(artifact))
        val selected = environment.stage(artifact)
        val generation = File(environment.root, selected.identity.relativePath)
        assertTrue(generation.setWritable(true))
        generation.appendBytes(byteArrayOf(0x51))

        val failed = assertLoadFailed(environment.loadCoordinator.enableAndActivate())

        assertEquals(AniyomiLoadBoundaryFailureCode.M2_LIFECYCLE_REJECTED, failed.failure.code)
        assertEquals(0, environment.loaders.openCount.get())
        assertNull(environment.boundary.activeMetadata())
        assertFalse(generation.exists())
    }

    @Test
    fun traversalAndSymlinkEscapesAreRejectedByRealStorageBoundaries() = runTest {
        val cache = FileAniyomiCodeCacheStore(
            AniyomiCodeCacheRoot.forTests(File(temporaryFolder.root, "traversal-cache")),
        )
        val traversal = expectLoadBoundaryException { cache.prepare("../escape") }
        assertEquals(AniyomiLoadBoundaryFailureCode.CACHE_IO, traversal.failure.code)
        assertFalse(File(temporaryFolder.root, "escape").exists())

        val artifact = controlled("symlink", ControlledAniyomiFactoryBehavior.SUCCESS)
        val environment = environment(File(temporaryFolder.root, "symlink-env"), listOf(artifact))
        val selected = environment.stage(artifact)
        val generation = File(environment.root, selected.identity.relativePath)
        val outside = File(temporaryFolder.root, "outside-extension.apk")
        generation.copyTo(outside)
        assertTrue(generation.delete())
        Files.createSymbolicLink(generation.toPath(), outside.toPath())

        val failed = assertLoadFailed(environment.loadCoordinator.enableAndActivate())

        assertEquals(AniyomiLoadBoundaryFailureCode.M2_LIFECYCLE_REJECTED, failed.failure.code)
        assertEquals(0, environment.loaders.openCount.get())
        assertTrue(outside.isFile)
    }

    @Test
    fun selectedPointerRemainsPinnedWhileExactGenerationCrossesTheM2ToM3Handoff() = runTest {
        val firstArtifact = controlled("pinned-first", ControlledAniyomiFactoryBehavior.SUCCESS, 1)
        val secondArtifact = controlled("pinned-second", ControlledAniyomiFactoryBehavior.CLASSPATH_ISOLATION, 2)
        val loaderEntered = CountDownLatch(1)
        val allowLoader = CountDownLatch(1)
        val updateAttempted = CountDownLatch(1)
        val environment = environment(
            root = File(temporaryFolder.root, "pinned-handoff-env"),
            artifacts = listOf(firstArtifact, secondArtifact),
            classLoaderTransform = { delegate ->
                BlockingAniyomiClassLoaderFactory(delegate, loaderEntered, allowLoader)
            },
        )
        val firstSelected = environment.stage(firstArtifact)
        environment.loadCoordinator.enable()
        val activation = async(Dispatchers.Default) {
            environment.loadCoordinator.activateSelected()
        }
        assertTrue(withContext(Dispatchers.IO) { loaderEntered.await(10, TimeUnit.SECONDS) })
        val update = async(Dispatchers.Default) {
            updateAttempted.countDown()
            environment.stageOutcome(secondArtifact)
        }
        assertTrue(withContext(Dispatchers.IO) { updateAttempted.await(10, TimeUnit.SECONDS) })

        try {
            assertFalse(update.isCompleted)
            assertEquals(
                firstSelected.identity.generationId,
                environment.lifecycleState.read()?.selected?.generationId,
            )
        } finally {
            allowLoader.countDown()
        }

        val firstActivated = assertActivated(activation.await())
        val secondSelected = assertSelected(update.await())
        assertEquals(firstSelected.identity.generationId, firstActivated.generation.generationId)
        assertEquals(
            secondSelected.identity.generationId,
            environment.lifecycleState.read()?.selected?.generationId,
        )
        assertTrue(environment.loaders.handles.first().isReleased)
        assertNull(environment.boundary.activeMetadata())

        val secondActivated = assertActivated(environment.loadCoordinator.activateSelected())
        assertEquals(secondSelected.identity.generationId, secondActivated.generation.generationId)
    }

    @Test
    fun sameShaDifferentPolicySelectionReleasesOldOwnerAndActivatesExactNewPolicy() = runTest {
        val firstArtifact = controlled("same-sha-policy", ControlledAniyomiFactoryBehavior.SUCCESS, 1)
        val secondArtifact = firstArtifact.copy(
            policy = firstArtifact.policy.copy(version = 2),
        )
        val environment = environment(
            File(temporaryFolder.root, "same-sha-policy-env"),
            listOf(firstArtifact, secondArtifact),
        )
        val firstSelected = environment.stage(firstArtifact)
        val firstActivated = assertActivated(environment.loadCoordinator.enableAndActivate())
        val firstHandle = environment.loaders.handles.single()

        val secondSelected = environment.stage(secondArtifact)

        assertEquals(firstSelected.identity.generationId, secondSelected.identity.generationId)
        assertNotEquals(firstSelected.identity.policyVersion, secondSelected.identity.policyVersion)
        assertTrue(firstHandle.isReleased)
        assertNull(environment.boundary.activeMetadata())
        assertEquals(1, environment.loaders.openCount.get())
        assertEquals(
            AniyomiLoadGenerationRef.from(secondSelected.identity),
            environment.lifecycleState.read()?.selected?.let { AniyomiLoadGenerationRef.from(it) },
        )

        val secondActivated = assertActivated(environment.loadCoordinator.activateSelected())

        assertNotEquals(firstActivated.sessionId, secondActivated.sessionId)
        assertEquals(AniyomiLoadGenerationRef.from(secondSelected.identity), secondActivated.generation)
        assertEquals(2, environment.loaders.openCount.get())
    }

    @Test
    fun rollbackTargetRemainsPinnedWhileConcurrentUpdaterWaitsForExactActivation() = runTest {
        val good = controlled("pinned-rollback-good", ControlledAniyomiFactoryBehavior.SUCCESS, 1)
        val failing = controlled(
            "pinned-rollback-failing",
            ControlledAniyomiFactoryBehavior.CONSTRUCTOR_FAILURE,
            2,
        )
        val updateArtifact = controlled(
            "pinned-rollback-update",
            ControlledAniyomiFactoryBehavior.CLASSPATH_ISOLATION,
            3,
        )
        val rollbackLoaderEntered = CountDownLatch(1)
        val allowRollbackLoader = CountDownLatch(1)
        val updateAttempted = CountDownLatch(1)
        val openOrdinal = java.util.concurrent.atomic.AtomicInteger(0)
        val environment = environment(
            root = File(temporaryFolder.root, "pinned-rollback-env"),
            artifacts = listOf(good, failing, updateArtifact),
            classLoaderTransform = { delegate ->
                object : AniyomiClassLoaderFactory {
                    override fun open(
                        sourceApk: File,
                        legacyOptimizedDirectory: File,
                        ownership: AniyomiClassOwnershipPolicy,
                    ): AniyomiOwnedClassLoaderHandle {
                        if (openOrdinal.incrementAndGet() == 3) {
                            rollbackLoaderEntered.countDown()
                            check(allowRollbackLoader.await(10, TimeUnit.SECONDS)) {
                                "Timed out waiting to finish the pinned rollback activation"
                            }
                        }
                        return delegate.open(sourceApk, legacyOptimizedDirectory, ownership)
                    }
                }
            },
        )
        val goodSelected = environment.stage(good)
        assertActivated(environment.loadCoordinator.enableAndActivate())
        val failingSelected = environment.stage(failing)
        val activationFailure = assertLoadFailed(environment.loadCoordinator.activateSelected())
        assertEquals(AniyomiLoadBoundaryFailureCode.CONSTRUCTOR_FAILED, activationFailure.failure.code)
        assertEquals(
            AniyomiLoadGenerationRef.from(failingSelected.identity),
            environment.lifecycleState.read()?.selected?.let { AniyomiLoadGenerationRef.from(it) },
        )

        val rollback = async(Dispatchers.Default) {
            environment.loadCoordinator.rollbackAfterFailedActivation()
        }
        assertTrue(withContext(Dispatchers.IO) {
            rollbackLoaderEntered.await(10, TimeUnit.SECONDS)
        })
        val update = async(Dispatchers.Default) {
            updateAttempted.countDown()
            environment.stageOutcome(updateArtifact)
        }
        assertTrue(withContext(Dispatchers.IO) { updateAttempted.await(10, TimeUnit.SECONDS) })

        try {
            assertFalse(update.isCompleted)
            assertEquals(
                AniyomiLoadGenerationRef.from(goodSelected.identity),
                environment.lifecycleState.read()?.selected?.let { AniyomiLoadGenerationRef.from(it) },
            )
        } finally {
            allowRollbackLoader.countDown()
        }

        val rolledBack = assertActivated(rollback.await())
        val updated = assertSelected(update.await())

        assertEquals(AniyomiLoadGenerationRef.from(goodSelected.identity), rolledBack.generation)
        assertEquals(
            AniyomiLoadGenerationRef.from(updated.identity),
            environment.lifecycleState.read()?.selected?.let { AniyomiLoadGenerationRef.from(it) },
        )
        assertEquals(3, environment.loaders.openCount.get())
        assertTrue(environment.loaders.handles.last().isReleased)
        assertNull(environment.boundary.activeMetadata())
    }

    @Test
    fun successfulRetryWinsConcurrentRollbackWithoutBeingRevertedToLastKnownGood() = runTest {
        val good = controlled("retry-wins-good", ControlledAniyomiFactoryBehavior.SUCCESS, 1)
        val retriable = controlled("retry-wins-selected", ControlledAniyomiFactoryBehavior.SUCCESS, 2)
        val retryLoaderEntered = CountDownLatch(1)
        val allowRetryLoader = CountDownLatch(1)
        val rollbackAttempted = CountDownLatch(1)
        val openOrdinal = java.util.concurrent.atomic.AtomicInteger(0)
        val environment = environment(
            root = File(temporaryFolder.root, "retry-wins-rollback-env"),
            artifacts = listOf(good, retriable),
            classLoaderTransform = { delegate ->
                object : AniyomiClassLoaderFactory {
                    override fun open(
                        sourceApk: File,
                        legacyOptimizedDirectory: File,
                        ownership: AniyomiClassOwnershipPolicy,
                    ): AniyomiOwnedClassLoaderHandle {
                        return when (openOrdinal.incrementAndGet()) {
                            2 -> error("injected first activation failure")
                            3 -> {
                                retryLoaderEntered.countDown()
                                check(allowRetryLoader.await(10, TimeUnit.SECONDS)) {
                                    "Timed out waiting to publish the successful retry"
                                }
                                delegate.open(sourceApk, legacyOptimizedDirectory, ownership)
                            }

                            else -> delegate.open(sourceApk, legacyOptimizedDirectory, ownership)
                        }
                    }
                }
            },
        )
        environment.stage(good)
        assertActivated(environment.loadCoordinator.enableAndActivate())
        val retriableSelected = environment.stage(retriable)
        val initialFailure = assertLoadFailed(environment.loadCoordinator.activateSelected())
        assertEquals(AniyomiLoadBoundaryFailureCode.INITIALIZATION_FAILED, initialFailure.failure.code)

        val retry = async(Dispatchers.Default) {
            environment.loadCoordinator.activateSelected()
        }
        assertTrue(withContext(Dispatchers.IO) { retryLoaderEntered.await(10, TimeUnit.SECONDS) })
        val rollback = async(Dispatchers.Default) {
            rollbackAttempted.countDown()
            environment.loadCoordinator.rollbackAfterFailedActivation()
        }
        assertTrue(withContext(Dispatchers.IO) { rollbackAttempted.await(10, TimeUnit.SECONDS) })

        try {
            assertFalse(rollback.isCompleted)
        } finally {
            allowRetryLoader.countDown()
        }

        val retried = assertActivated(retry.await())
        val rejectedRollback = assertLoadFailed(rollback.await())

        assertEquals(AniyomiLoadBoundaryFailureCode.ROLLBACK_REJECTED, rejectedRollback.failure.code)
        assertEquals(AniyomiLoadGenerationRef.from(retriableSelected.identity), retried.generation)
        assertEquals(retried.sessionId, environment.boundary.activeMetadata()?.sessionId)
        assertEquals(
            AniyomiLoadGenerationRef.from(retriableSelected.identity),
            environment.lifecycleState.read()?.selected?.let { AniyomiLoadGenerationRef.from(it) },
        )
        assertEquals(2, environment.loaders.openCount.get())
        assertFalse(environment.loaders.handles.last().isReleased)
    }

    @Test
    fun disableWinsConcurrentRollbackWithoutChangingTheM2Pointer() = runTest {
        data class DisableReadGate(
            val entered: kotlinx.coroutines.CompletableDeferred<Unit> =
                kotlinx.coroutines.CompletableDeferred(),
            val proceed: kotlinx.coroutines.CompletableDeferred<Unit> =
                kotlinx.coroutines.CompletableDeferred(),
        )

        class BlockingDisableReadStateStore(
            private val delegate: AniyomiLoadBoundaryStateStore,
        ) : AniyomiLoadBoundaryStateStore by delegate {
            private val pending =
                java.util.concurrent.atomic.AtomicReference<DisableReadGate?>(null)

            fun blockNextRead(): DisableReadGate = DisableReadGate().also { gate ->
                check(pending.compareAndSet(null, gate)) { "A controlled read is already pending" }
            }

            override suspend fun read(): AniyomiLoadBoundaryRecord? {
                val gate = pending.get()
                if (gate != null && pending.compareAndSet(gate, null)) {
                    gate.entered.complete(Unit)
                    gate.proceed.await()
                }
                return delegate.read()
            }
        }

        val good = controlled("disable-wins-good", ControlledAniyomiFactoryBehavior.SUCCESS, 1)
        val failing = controlled("disable-wins-selected", ControlledAniyomiFactoryBehavior.SUCCESS, 2)
        val openOrdinal = java.util.concurrent.atomic.AtomicInteger(0)
        lateinit var blockingState: BlockingDisableReadStateStore
        val environment = environment(
            root = File(temporaryFolder.root, "disable-wins-rollback-env"),
            artifacts = listOf(good, failing),
            loadStateTransform = { state ->
                BlockingDisableReadStateStore(state).also { blockingState = it }
            },
            classLoaderTransform = { delegate ->
                object : AniyomiClassLoaderFactory {
                    override fun open(
                        sourceApk: File,
                        legacyOptimizedDirectory: File,
                        ownership: AniyomiClassOwnershipPolicy,
                    ): AniyomiOwnedClassLoaderHandle {
                        if (openOrdinal.incrementAndGet() == 2) {
                            error("injected activation failure before disable race")
                        }
                        return delegate.open(sourceApk, legacyOptimizedDirectory, ownership)
                    }
                }
            },
        )
        environment.stage(good)
        assertActivated(environment.loadCoordinator.enableAndActivate())
        val failedSelected = environment.stage(failing)
        val activationFailure = assertLoadFailed(environment.loadCoordinator.activateSelected())
        assertEquals(AniyomiLoadBoundaryFailureCode.INITIALIZATION_FAILED, activationFailure.failure.code)
        val disableRead = blockingState.blockNextRead()

        val disable = async(Dispatchers.Default) { environment.loadCoordinator.disable() }
        disableRead.entered.await()
        val rollbackAttempted = CountDownLatch(1)
        val rollback = async(Dispatchers.Default) {
            rollbackAttempted.countDown()
            environment.loadCoordinator.rollbackAfterFailedActivation()
        }
        assertTrue(withContext(Dispatchers.IO) { rollbackAttempted.await(10, TimeUnit.SECONDS) })

        assertFalse(rollback.isCompleted)
        disableRead.proceed.complete(Unit)

        assertEquals(AniyomiLoadBoundaryOutcome.Disabled, disable.await())
        val rejectedRollback = assertLoadFailed(rollback.await())

        assertEquals(AniyomiLoadBoundaryFailureCode.ROLLBACK_REJECTED, rejectedRollback.failure.code)
        assertEquals(AniyomiLoadBoundaryPhase.DISABLED, environment.loadState.read()?.phase)
        assertEquals(
            AniyomiLoadGenerationRef.from(failedSelected.identity),
            environment.lifecycleState.read()?.selected?.let { AniyomiLoadGenerationRef.from(it) },
        )
        assertNull(environment.boundary.activeMetadata())
    }

    @Test
    fun activationWaitsForStartupRecoveryAndPreservesExactLiveOwner() = runTest {
        data class ReadGate(
            val entered: kotlinx.coroutines.CompletableDeferred<Unit> =
                kotlinx.coroutines.CompletableDeferred(),
            val proceed: kotlinx.coroutines.CompletableDeferred<Unit> =
                kotlinx.coroutines.CompletableDeferred(),
        )

        class BlockingNextReadStateStore(
            private val delegate: AniyomiLoadBoundaryStateStore,
        ) : AniyomiLoadBoundaryStateStore by delegate {
            private val pending = java.util.concurrent.atomic.AtomicReference<ReadGate?>(null)

            fun blockNextRead(): ReadGate = ReadGate().also { gate ->
                check(pending.compareAndSet(null, gate)) { "A controlled read is already pending" }
            }

            override suspend fun read(): AniyomiLoadBoundaryRecord? {
                val gate = pending.get()
                if (gate != null && pending.compareAndSet(gate, null)) {
                    gate.entered.complete(Unit)
                    gate.proceed.await()
                }
                return delegate.read()
            }
        }

        val artifact = controlled("startup-live-owner", ControlledAniyomiFactoryBehavior.SUCCESS)
        lateinit var blockingState: BlockingNextReadStateStore
        val environment = environment(
            root = File(temporaryFolder.root, "startup-live-owner-env"),
            artifacts = listOf(artifact),
            loadStateTransform = { state ->
                BlockingNextReadStateStore(state).also { blockingState = it }
            },
        )
        environment.stage(artifact)
        val initial = assertActivated(environment.loadCoordinator.enableAndActivate())
        val initialHandle = environment.loaders.handles.single()
        val readGate = blockingState.blockNextRead()
        val startup = AniyomiLifecycleStartup(environment.lifecycle, environment.boundary, this)
        val startupAwareCoordinator = AniyomiLoadCoordinator(
            environment.lifecycle,
            environment.boundary,
            startup,
        )

        val activation = async { startupAwareCoordinator.activateSelected() }
        readGate.entered.await()

        assertFalse(activation.isCompleted)
        assertEquals(1, environment.loaders.openCount.get())
        assertFalse(initialHandle.isReleased)
        readGate.proceed.complete(Unit)

        val reactivated = assertActivated(activation.await())

        assertEquals(initial.sessionId, reactivated.sessionId)
        assertTrue(reactivated.reusedExisting)
        assertEquals(1, environment.loaders.openCount.get())
        assertFalse(initialHandle.isReleased)
        assertEquals(initial.generation, environment.boundary.activeMetadata()?.generation)
    }

    @Test
    fun selectedUpdateReleasesOldOwnerBeforePointerSwapAndRequiresExplicitNewActivation() = runTest {
        val firstArtifact = controlled("stale-first", ControlledAniyomiFactoryBehavior.SUCCESS, 1)
        val secondArtifact = controlled("stale-second", ControlledAniyomiFactoryBehavior.CLASSPATH_ISOLATION, 2)
        val environment = environment(
            File(temporaryFolder.root, "stale-env"),
            listOf(firstArtifact, secondArtifact),
        )
        val firstSelected = environment.stage(firstArtifact)
        assertActivated(environment.loadCoordinator.enableAndActivate())
        val oldHandle = environment.loaders.handles.single()

        val secondSelected = environment.stage(secondArtifact)

        assertNotEquals(firstSelected.identity.generationId, secondSelected.identity.generationId)
        assertTrue(oldHandle.isReleased)
        assertNull(environment.boundary.activeMetadata())
        assertEquals(1, environment.loaders.openCount.get())
        assertEquals(secondSelected.identity.generationId, environment.lifecycleState.read()?.selected?.generationId)
        assertEquals(firstSelected.identity.generationId, environment.lifecycleState.read()?.previous?.generationId)

        val activated = assertActivated(environment.loadCoordinator.activateSelected())
        assertEquals(secondSelected.identity.generationId, activated.generation.generationId)
        assertEquals(2, environment.loaders.openCount.get())
    }

    @Test
    fun failedActivationNeverUsesPreviousUntilExplicitExactRollback() = runTest {
        val good = controlled("rollback-good", ControlledAniyomiFactoryBehavior.SUCCESS, 1)
        val failing = controlled("rollback-failing", ControlledAniyomiFactoryBehavior.CONSTRUCTOR_FAILURE, 2)
        val environment = environment(
            File(temporaryFolder.root, "rollback-env"),
            listOf(good, failing),
        )
        val goodSelected = environment.stage(good)
        assertActivated(environment.loadCoordinator.enableAndActivate())
        val goodHandle = environment.loaders.handles.single()
        val failingSelected = environment.stage(failing)
        assertTrue(goodHandle.isReleased)

        val activationFailure = assertLoadFailed(environment.loadCoordinator.activateSelected())
        assertEquals(AniyomiLoadBoundaryFailureCode.CONSTRUCTOR_FAILED, activationFailure.failure.code)
        assertNull(environment.boundary.activeMetadata())
        assertEquals(2, environment.loaders.openCount.get())
        assertEquals(failingSelected.identity.generationId, environment.lifecycleState.read()?.selected?.generationId)
        assertEquals(goodSelected.identity.generationId, environment.lifecycleState.read()?.previous?.generationId)

        val rolledBack = assertActivated(environment.loadCoordinator.rollbackAfterFailedActivation())

        assertEquals(goodSelected.identity.generationId, rolledBack.generation.generationId)
        assertEquals(goodSelected.identity.generationId, environment.lifecycleState.read()?.selected?.generationId)
        assertEquals(failingSelected.identity.generationId, environment.lifecycleState.read()?.previous?.generationId)
        assertEquals(3, environment.loaders.openCount.get())
    }

    @Test
    fun releaseFailureBlocksM2PointerChangeAndLeavesNoStaleInMemoryOwner() = runTest {
        val firstArtifact = controlled("release-failure-first", ControlledAniyomiFactoryBehavior.SUCCESS, 1)
        val secondArtifact = controlled("release-failure-second", ControlledAniyomiFactoryBehavior.CLASSPATH_ISOLATION, 2)
        val failingCache = ToggleFailingCodeCacheStore(
            FileAniyomiCodeCacheStore(
                AniyomiCodeCacheRoot.forTests(File(temporaryFolder.root, "release-failure-cache")),
            ),
        )
        val environment = environment(
            root = File(temporaryFolder.root, "release-failure-env"),
            artifacts = listOf(firstArtifact, secondArtifact),
            codeCacheOverride = failingCache,
        )
        val first = environment.stage(firstArtifact)
        assertActivated(environment.loadCoordinator.enableAndActivate())
        failingCache.failCleanup.set(true)

        val failed = assertLifecycleFailed(environment.stageOutcome(secondArtifact))

        assertEquals(AniyomiLifecycleFailureCode.LOAD_BOUNDARY_RELEASE_FAILED, failed.failure.code)
        assertEquals(first.identity.generationId, environment.lifecycleState.read()?.selected?.generationId)
        assertNull(environment.boundary.activeMetadata())
        assertTrue(environment.loaders.handles.single().isReleased)
    }

    @Test
    fun crashAroundLoadPhasesRecoversWithFreshInstancesWithoutAutoLoading() = runTest {
        listOf(
            AniyomiLoadBoundaryPhase.ACTIVATING,
            AniyomiLoadBoundaryPhase.ACTIVE,
            AniyomiLoadBoundaryPhase.RELEASE_PENDING,
        ).forEachIndexed { index, crashPhase ->
            val artifact = controlled("crash-$index", ControlledAniyomiFactoryBehavior.SUCCESS)
            val root = File(temporaryFolder.root, "crash-${crashPhase.name.lowercase()}")
            lateinit var durable: FileAniyomiLoadBoundaryStateStore
            val crashingEnvironment = environment(
                root = root,
                artifacts = listOf(artifact),
                loadStateTransform = { base ->
                    durable = base as FileAniyomiLoadBoundaryStateStore
                    CrashAfterLoadPhaseStateStore(base, crashPhase)
                },
            )
            crashingEnvironment.stage(artifact)
            crashingEnvironment.loadCoordinator.enable()
            if (crashPhase == AniyomiLoadBoundaryPhase.RELEASE_PENDING) {
                assertActivated(crashingEnvironment.loadCoordinator.activateSelected())
                expectProcessDeath { crashingEnvironment.loadCoordinator.release() }
            } else {
                expectProcessDeath { crashingEnvironment.loadCoordinator.activateSelected() }
            }
            assertEquals(crashPhase, durable.read()?.phase)

            val fresh = environment(root, listOf(artifact))
            val recovered = fresh.boundary.recoverAfterProcessDeath()

            assertEquals(AniyomiLoadBoundaryOutcome.Released(enabled = true), recovered)
            assertEquals(AniyomiLoadBoundaryPhase.ENABLED_IDLE, fresh.loadState.read()?.phase)
            assertNull(fresh.boundary.activeMetadata())
            assertEquals(0, fresh.loaders.openCount.get())
            assertTrue(fresh.codeCacheRoot.listFiles().orEmpty().isEmpty())
            val firstState = fresh.loadState.read()
            assertEquals(
                AniyomiLoadBoundaryOutcome.Released(enabled = true),
                fresh.boundary.recoverAfterProcessDeath(),
            )
            assertEquals(firstState, fresh.loadState.read())
        }
    }

    @Test
    fun parentOwnedDuplicateDexDefinitionIsRejectedBeforeM3() {
        val root = File(temporaryFolder.root, "duplicate-parent").apply { mkdirs() }
        val source = AniyomiApkTestFixtures.writeApk(
            root,
            dex = AniyomiApkTestFixtures.buildDex(
                additionalDefinedClasses = setOf("java.lang.String"),
            ),
        )
        val policy = AniyomiApkTestFixtures.policyFor(source)
        val inspector = AniyomiExtensionApkInspector(
            AniyomiPackageArchiveReader { AniyomiApkTestFixtures.validPackageFacts(policy) },
        )

        val rejected = inspector.inspect(source, policy) as AniyomiApkInspectionResult.Rejected

        assertEquals(AniyomiApkRejectionCode.HOST_CLASS_SHADOWING, rejected.failures.single().code)
    }

    private fun controlled(
        name: String,
        behavior: ControlledAniyomiFactoryBehavior,
        policyVersion: Int = 1,
    ): ControlledAniyomiArtifact = AniyomiLoadBoundaryTestFixtures.controlledArtifact(
        directory = File(temporaryFolder.root, "controlled-$name"),
        behavior = behavior,
        name = "$name.apk",
        policyVersion = policyVersion,
    )

    private fun environment(
        root: File,
        artifacts: List<ControlledAniyomiArtifact>,
        inspector: AniyomiStaticApkInspector = inspectorFor(artifacts),
        storeTransform: (FileAniyomiArtifactStore) -> AniyomiArtifactStore = { it },
        loadStateTransform: (AniyomiLoadBoundaryStateStore) -> AniyomiLoadBoundaryStateStore = { it },
        codeCacheOverride: AniyomiCodeCacheStore? = null,
        classLoaderTransform: (JvmAniyomiClassLoaderFactory) -> AniyomiClassLoaderFactory = { it },
    ): TestEnvironment {
        root.mkdirs()
        val lifecycleRoot = AniyomiLifecycleRoot.forTests(root)
        val lifecycleState = FileAniyomiLifecycleStateStore(lifecycleRoot)
        val fileArtifacts = FileAniyomiArtifactStore(lifecycleRoot)
        val artifactStore = storeTransform(fileArtifacts)
        val baseLoadState = FileAniyomiLoadBoundaryStateStore(lifecycleRoot)
        val loadState = loadStateTransform(baseLoadState)
        val codeCacheRoot = File(root, "code-cache")
        val cache = codeCacheOverride ?: FileAniyomiCodeCacheStore(
            AniyomiCodeCacheRoot.forTests(codeCacheRoot),
        )
        val loaders = JvmAniyomiClassLoaderFactory()
        val policies = MutablePolicyRegistry(artifacts.map(ControlledAniyomiArtifact::policy))
        val boundary = AniyomiLoadBoundary(
            inspector = inspector,
            artifacts = artifactStore,
            state = loadState,
            legacyDexCache = cache,
            classLoaders = classLoaderTransform(loaders),
            hostRuntime = AniyomiLoadBoundaryTestFixtures.initializedHostRuntime(),
        )
        val lifecycle = AniyomiLifecycleCoordinator(
            inspector = inspector,
            policyProvider = policies,
            artifacts = artifactStore,
            state = lifecycleState,
            selectionBarrier = boundary,
        )
        return TestEnvironment(
            root = root,
            codeCacheRoot = codeCacheRoot,
            lifecycleState = lifecycleState,
            loadState = loadState,
            loaders = loaders,
            policies = policies,
            boundary = boundary,
            lifecycle = lifecycle,
            loadCoordinator = AniyomiLoadCoordinator(
                lifecycle,
                boundary,
                AniyomiStartupGate { null },
            ),
        )
    }

    private fun inspectorFor(
        artifacts: List<ControlledAniyomiArtifact>,
    ): AniyomiExtensionApkInspector {
        val policiesBySha = artifacts.associate { it.policy.policy.expectedSha256 to it.policy.policy }
        return AniyomiExtensionApkInspector(
            AniyomiPackageArchiveReader { file ->
                val sha = AniyomiApkTestFixtures.sha256(file.readBytes())
                val policy = requireNotNull(policiesBySha[sha]) {
                    "No controlled package facts exist for $sha"
                }
                AniyomiApkTestFixtures.validPackageFacts(policy)
            },
        )
    }

    private fun assertActivated(
        outcome: AniyomiLoadBoundaryOutcome,
    ): AniyomiLoadBoundaryOutcome.Activated {
        assertTrue("Expected activated outcome, got $outcome", outcome is AniyomiLoadBoundaryOutcome.Activated)
        return outcome as AniyomiLoadBoundaryOutcome.Activated
    }

    private fun assertLoadFailed(
        outcome: AniyomiLoadBoundaryOutcome,
    ): AniyomiLoadBoundaryOutcome.Failed {
        assertTrue("Expected load failure, got $outcome", outcome is AniyomiLoadBoundaryOutcome.Failed)
        return outcome as AniyomiLoadBoundaryOutcome.Failed
    }

    private fun assertSelected(
        outcome: AniyomiLifecycleOutcome,
    ): AniyomiLifecycleOutcome.Selected {
        assertTrue("Expected selected outcome, got $outcome", outcome is AniyomiLifecycleOutcome.Selected)
        return outcome as AniyomiLifecycleOutcome.Selected
    }

    private fun assertLifecycleFailed(
        outcome: AniyomiLifecycleOutcome,
    ): AniyomiLifecycleOutcome.Failed {
        assertTrue("Expected lifecycle failure, got $outcome", outcome is AniyomiLifecycleOutcome.Failed)
        return outcome as AniyomiLifecycleOutcome.Failed
    }

    private suspend fun expectLoadBoundaryException(
        block: suspend () -> Unit,
    ): AniyomiLoadBoundaryException {
        try {
            block()
        } catch (error: AniyomiLoadBoundaryException) {
            return error
        }
        throw AssertionError("Expected AniyomiLoadBoundaryException")
    }

    private suspend fun expectProcessDeath(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: SimulatedLoadProcessDeath) {
            return
        }
        throw AssertionError("Expected simulated process death")
    }

    private data class TestEnvironment(
        val root: File,
        val codeCacheRoot: File,
        val lifecycleState: AniyomiLifecycleStateStore,
        val loadState: AniyomiLoadBoundaryStateStore,
        val loaders: JvmAniyomiClassLoaderFactory,
        val policies: MutablePolicyRegistry,
        val boundary: AniyomiLoadBoundary,
        val lifecycle: AniyomiLifecycleCoordinator,
        val loadCoordinator: AniyomiLoadCoordinator,
    ) {
        suspend fun stage(artifact: ControlledAniyomiArtifact): AniyomiLifecycleOutcome.Selected =
            assertSelected(stageOutcome(artifact))

        suspend fun stageOutcome(artifact: ControlledAniyomiArtifact): AniyomiLifecycleOutcome {
            policies.setCurrent(artifact.policy)
            return lifecycle.stageAndSelect(artifact.capability)
        }

        private fun assertSelected(
            outcome: AniyomiLifecycleOutcome,
        ): AniyomiLifecycleOutcome.Selected {
            check(outcome is AniyomiLifecycleOutcome.Selected) { "Expected selected outcome, got $outcome" }
            return outcome
        }
    }

    private class MutablePolicyRegistry(
        policies: List<AniyomiTrustedPolicy>,
    ) : AniyomiTrustedPolicyProvider {
        private val policies = policies.associateByTo(linkedMapOf()) {
            Triple(it.id, it.version, it.policy.expectedSha256)
        }
        private var current = policies.first()

        override fun current(): AniyomiTrustedPolicy = current

        override fun find(
            policyId: String,
            policyVersion: Int,
            generationId: String,
        ): AniyomiTrustedPolicy? = policies[Triple(policyId, policyVersion, generationId)]

        fun setCurrent(policy: AniyomiTrustedPolicy) {
            check(find(policy.id, policy.version, policy.policy.expectedSha256) === policy)
            current = policy
        }

        fun remove(policy: AniyomiTrustedPolicy) {
            policies.remove(Triple(policy.id, policy.version, policy.policy.expectedSha256))
        }
    }

    private class SwitchableInspector(
        @Volatile var delegate: AniyomiStaticApkInspector,
    ) : AniyomiStaticApkInspector {
        override fun inspect(
            apk: File,
            policy: AniyomiApkPolicy,
        ): AniyomiApkInspectionResult = delegate.inspect(apk, policy)
    }

    private class RedirectingArtifactStoreHolder(
        private val wrongFile: File,
    ) {
        val enabled = AtomicBoolean(false)

        fun wrap(delegate: FileAniyomiArtifactStore): AniyomiArtifactStore =
            object : AniyomiArtifactStore by delegate {
                override suspend fun resolve(artifact: AniyomiArtifactIdentity): File =
                    if (enabled.get()) wrongFile else delegate.resolve(artifact)
            }
    }

    private class BlockingAniyomiClassLoaderFactory(
        private val delegate: AniyomiClassLoaderFactory,
        private val entered: CountDownLatch,
        private val proceed: CountDownLatch,
    ) : AniyomiClassLoaderFactory {
        override fun open(
            sourceApk: File,
            legacyOptimizedDirectory: File,
            ownership: AniyomiClassOwnershipPolicy,
        ): AniyomiOwnedClassLoaderHandle {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting to continue the controlled M2-to-M3 handoff"
            }
            return delegate.open(sourceApk, legacyOptimizedDirectory, ownership)
        }
    }

    private class ToggleFailingCodeCacheStore(
        private val delegate: AniyomiCodeCacheStore,
    ) : AniyomiCodeCacheStore by delegate {
        val failCleanup = AtomicBoolean(false)

        override suspend fun cleanup(generationId: String) {
            if (failCleanup.get()) {
                throw AniyomiLoadBoundaryException(
                    AniyomiLoadBoundaryFailure(
                        AniyomiLoadBoundaryFailureCode.CACHE_IO,
                        "injected controlled cache cleanup failure",
                    ),
                )
            }
            delegate.cleanup(generationId)
        }
    }

    private class CrashAfterLoadPhaseStateStore(
        private val delegate: AniyomiLoadBoundaryStateStore,
        private val crashPhase: AniyomiLoadBoundaryPhase,
    ) : AniyomiLoadBoundaryStateStore by delegate {
        private var armed = true

        override suspend fun write(record: AniyomiLoadBoundaryRecord) {
            delegate.write(record)
            if (armed && record.phase == crashPhase) {
                armed = false
                throw SimulatedLoadProcessDeath()
            }
        }
    }

    private class SimulatedLoadProcessDeath : Error()
}
