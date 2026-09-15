package com.foxtv.app.core.plugin.aniyomi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class AniyomiLifecycleStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stateStoreRoundTripsStrictRecordAndRestoresLastRecordAfterInterruptedPromotion() = runTest {
        val root = File(temporaryFolder.root, "lifecycle")
        val store = FileAniyomiLifecycleStateStore(AniyomiLifecycleRoot.forTests(root))
        val source = File(temporaryFolder.root, "identity.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val capability = AniyomiLifecycleTestFixtures.fakeCapability(source)
        val policy = AniyomiLifecycleTestFixtures.trustedPolicyFor(capability)
        val identity = AniyomiLifecycleTestFixtures.identity(source, policy)
        val record = selectedRecord(policy, identity)

        store.write(record)
        assertEquals(record, store.read())

        val stateDirectory = File(root, "state")
        val base = File(stateDirectory, "lifecycle.json")
        val backup = File(stateDirectory, "lifecycle.json.bak")
        val next = File(stateDirectory, "lifecycle.json.next")
        base.copyTo(backup)
        base.writeText("corrupt promoted record")
        next.writeText("incomplete next record")

        assertEquals(record, store.read())
        assertFalse(backup.exists())
        assertFalse(next.exists())
        assertEquals(record, store.read())
    }

    @Test
    fun malformedOrOversizedStateFailsClosedInsteadOfResettingSelection() = runTest {
        val root = File(temporaryFolder.root, "bad-state")
        val store = FileAniyomiLifecycleStateStore(AniyomiLifecycleRoot.forTests(root))
        val stateDirectory = File(root, "state").apply { mkdirs() }
        File(stateDirectory, "lifecycle.json").writeText("{not-json")

        val malformed = expectLifecycle { store.read() }
        assertEquals(AniyomiLifecycleFailureCode.STATE_CORRUPT, malformed.failure.code)
        assertTrue(File(stateDirectory, "lifecycle.json").exists())

        File(stateDirectory, "lifecycle.json").writeBytes(ByteArray(256 * 1024 + 1))
        val oversized = expectLifecycle { store.read() }
        assertEquals(AniyomiLifecycleFailureCode.STATE_CORRUPT, oversized.failure.code)
    }

    @Test
    fun resolverRejectsPrefixCollisionTraversalAbsoluteAndSeparatorStorageKeys() {
        val root = File(temporaryFolder.root, "safe-root").apply { mkdirs() }
        val paths = AniyomiStoragePaths(root)
        paths.root

        listOf("../escape", "/tmp/escape", "nested/value", "nested\\value", ".", "..").forEach { key ->
            val failure = expectLifecycleBlocking { paths.transactionDirectory(key, create = true) }
            assertEquals(key, AniyomiLifecycleFailureCode.UNSAFE_STORAGE_PATH, failure.failure.code)
        }

        val collision = File(root.parentFile, "${root.name}-collision/extension.apk")
        val collisionFailure = expectLifecycleBlocking { paths.requireContained(collision) }
        assertEquals(AniyomiLifecycleFailureCode.UNSAFE_STORAGE_PATH, collisionFailure.failure.code)
    }

    @Test
    fun symbolicRootParentCandidateSelectedAndPreviousAreRejected() = runTest {
        val realRoot = File(temporaryFolder.root, "real-root").apply { mkdirs() }
        val rootLink = File(temporaryFolder.root, "root-link")
        Files.createSymbolicLink(rootLink.toPath(), realRoot.toPath())
        val rootFailure = expectLifecycleBlocking { AniyomiStoragePaths(rootLink).root }
        assertEquals(AniyomiLifecycleFailureCode.UNSAFE_STORAGE_PATH, rootFailure.failure.code)

        val parentRoot = File(temporaryFolder.root, "parent-root").apply { mkdirs() }
        val outsideTransactions = File(temporaryFolder.root, "outside-transactions").apply { mkdirs() }
        Files.createSymbolicLink(
            File(parentRoot, "transactions").toPath(),
            outsideTransactions.toPath(),
        )
        val parentStore = FileAniyomiArtifactStore(
            AniyomiLifecycleRoot.forTests(parentRoot),
        )
        val parentFailure = expectLifecycle { parentStore.createTransaction(validTransactionId(1)) }
        assertEquals(AniyomiLifecycleFailureCode.UNSAFE_STORAGE_PATH, parentFailure.failure.code)

        val root = File(temporaryFolder.root, "leaf-root")
        val paths = AniyomiStoragePaths(root)
        val candidateTransaction = validTransactionId(2)
        paths.transactionDirectory(candidateTransaction, create = true)
        val outsideFile = File(temporaryFolder.root, "outside.apk").apply { writeText("outside") }
        Files.createSymbolicLink(
            File(paths.transactionDirectory(candidateTransaction, false), "candidate.apk").toPath(),
            outsideFile.toPath(),
        )
        val candidateFailure = expectLifecycleBlocking {
            paths.transactionCandidate(candidateTransaction, requireRegular = true)
        }
        assertEquals(AniyomiLifecycleFailureCode.UNSAFE_STORAGE_PATH, candidateFailure.failure.code)

        val selectedId = "a".repeat(64)
        val selectedDirectory = paths.generationDirectory(selectedId, create = true)
        Files.createSymbolicLink(
            File(selectedDirectory, ANIYOMI_APK_FILE_NAME).toPath(),
            outsideFile.toPath(),
        )
        val selectedFailure = expectLifecycleBlocking { paths.generationApk(selectedId, true) }
        assertEquals(AniyomiLifecycleFailureCode.UNSAFE_STORAGE_PATH, selectedFailure.failure.code)

        val previousId = "b".repeat(64)
        val outsideDirectory = File(temporaryFolder.root, "outside-generation").apply { mkdirs() }
        Files.createSymbolicLink(
            File(paths.generations(), previousId).toPath(),
            outsideDirectory.toPath(),
        )
        val previousFailure = expectLifecycleBlocking { paths.generationApk(previousId, true) }
        assertEquals(AniyomiLifecycleFailureCode.UNSAFE_STORAGE_PATH, previousFailure.failure.code)
        assertEquals("outside", outsideFile.readText())
    }

    @Test
    fun unknownSymbolicEntryIsQuarantinedAsEntryWithoutFollowingItsTarget() = runTest {
        val root = File(temporaryFolder.root, "quarantine-root")
        val store = FileAniyomiArtifactStore(AniyomiLifecycleRoot.forTests(root))
        val paths = AniyomiStoragePaths(root)
        paths.root
        val outside = File(temporaryFolder.root, "outside-tree").apply {
            mkdirs()
            File(this, "must-survive.txt").writeText("safe")
        }
        val unknownLink = File(root, "unknown-link")
        Files.createSymbolicLink(unknownLink.toPath(), outside.toPath())

        store.quarantineUnknownEntries(activeTransactionId = null)

        assertFalse(Files.exists(unknownLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("safe", File(outside, "must-survive.txt").readText())
        assertEquals(1, paths.quarantine().listFiles().orEmpty().size)
    }

    @Test
    fun cleanupRefusesCandidateSymlinkAndNeverDeletesOutsideTarget() = runTest {
        val root = File(temporaryFolder.root, "cleanup-root")
        val store = FileAniyomiArtifactStore(AniyomiLifecycleRoot.forTests(root))
        val transactionId = validTransactionId(3)
        store.createTransaction(transactionId)
        val outside = File(temporaryFolder.root, "outside-cleanup.apk").apply { writeText("retain") }
        val candidate = File(File(File(root, "transactions"), transactionId), "candidate.apk")
        Files.createSymbolicLink(candidate.toPath(), outside.toPath())

        val failure = expectLifecycle { store.cleanupTransaction(transactionId) }
        assertEquals(AniyomiLifecycleFailureCode.UNSAFE_STORAGE_PATH, failure.failure.code)
        assertEquals("retain", outside.readText())
        assertTrue(Files.exists(candidate.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun dynamicCodeCandidateAndPublishedGenerationRemainReadOnly() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "read-only-publication")
        val store = FileAniyomiArtifactStore(AniyomiLifecycleRoot.forTests(root))
        val transactionId = validTransactionId(40)
        store.createTransaction(transactionId)

        val candidate = store.copyCapabilityToTransaction(
            fixture.capability,
            transactionId,
            fixture.policy,
        )
        assertTrue(candidate.hasReadOnlyAniyomiCodePermissions())
        val candidateIdentity = AniyomiArtifactIdentity.fromInspection(
            fixture.capability,
            AniyomiStoredArtifactKind.TRANSACTION,
            transactionId,
            fixture.policy,
        )

        val published = store.publishGeneration(candidateIdentity)

        assertFalse(published.reusedExisting)
        assertTrue(published.file.hasReadOnlyAniyomiCodePermissions())
        assertEquals(
            published.file,
            store.resolve(candidateIdentity.asGeneration()),
        )
    }

    @Test
    fun writablePublishedGenerationIsRejectedBeforeReuseOrResolution() = runTest {
        val fixture = AniyomiLifecycleTestFixtures.realFixture(temporaryFolder.root)
        val root = File(temporaryFolder.root, "writable-generation")
        val store = FileAniyomiArtifactStore(AniyomiLifecycleRoot.forTests(root))
        val firstTransaction = validTransactionId(41)
        store.createTransaction(firstTransaction)
        store.copyCapabilityToTransaction(fixture.capability, firstTransaction, fixture.policy)
        val firstIdentity = AniyomiArtifactIdentity.fromInspection(
            fixture.capability,
            AniyomiStoredArtifactKind.TRANSACTION,
            firstTransaction,
            fixture.policy,
        )
        val published = store.publishGeneration(firstIdentity)
        assertTrue(published.file.setWritable(true))
        assertFalse(published.file.hasReadOnlyAniyomiCodePermissions())

        val resolveFailure = expectLifecycle { store.resolve(firstIdentity.asGeneration()) }
        assertEquals(
            AniyomiLifecycleFailureCode.IMMUTABLE_GENERATION_REQUIRED,
            resolveFailure.failure.code,
        )

        val secondTransaction = validTransactionId(42)
        store.createTransaction(secondTransaction)
        store.copyCapabilityToTransaction(fixture.capability, secondTransaction, fixture.policy)
        val secondIdentity = AniyomiArtifactIdentity.fromInspection(
            fixture.capability,
            AniyomiStoredArtifactKind.TRANSACTION,
            secondTransaction,
            fixture.policy,
        )
        val reuseFailure = expectLifecycle { store.publishGeneration(secondIdentity) }
        assertEquals(
            AniyomiLifecycleFailureCode.IMMUTABLE_GENERATION_REQUIRED,
            reuseFailure.failure.code,
        )
    }

    private fun selectedRecord(
        policy: AniyomiTrustedPolicy,
        identity: AniyomiArtifactIdentity,
    ): AniyomiLifecycleRecord = AniyomiLifecycleRecord(
        policyId = policy.id,
        policyVersion = policy.version,
        phase = AniyomiLifecyclePhase.SELECTED,
        selected = identity,
    )

    private fun validTransactionId(value: Int): String =
        "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private suspend fun expectLifecycle(block: suspend () -> Unit): AniyomiLifecycleException {
        var captured: AniyomiLifecycleException? = null
        try {
            block()
        } catch (error: AniyomiLifecycleException) {
            captured = error
        }
        assertNotNull("Expected AniyomiLifecycleException", captured)
        return requireNotNull(captured)
    }

    private fun expectLifecycleBlocking(block: () -> Unit): AniyomiLifecycleException {
        var captured: AniyomiLifecycleException? = null
        try {
            block()
        } catch (error: AniyomiLifecycleException) {
            captured = error
        }
        assertNotNull("Expected AniyomiLifecycleException", captured)
        return requireNotNull(captured)
    }
}
