package com.foxtv.app.core.plugin.aniyomi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class AniyomiLoadBoundaryStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stateStoreRoundTripsAndRestoresLastRecordAfterInterruptedPromotion() = runTest {
        val root = File(temporaryFolder.root, "load-state")
        val store = FileAniyomiLoadBoundaryStateStore(AniyomiLifecycleRoot.forTests(root))
        val record = activeRecord("a".repeat(64))

        store.write(record)
        assertEquals(record, store.read())

        val stateDirectory = File(root, "state")
        val base = File(stateDirectory, ANIYOMI_LOAD_STATE_FILE_NAME)
        val backup = File(stateDirectory, "$ANIYOMI_LOAD_STATE_FILE_NAME.bak")
        val next = File(stateDirectory, "$ANIYOMI_LOAD_STATE_FILE_NAME.next")
        base.copyTo(backup)
        base.writeText("corrupt promoted load record")
        next.writeText("incomplete load record")

        assertEquals(record, store.read())
        assertFalse(backup.exists())
        assertFalse(next.exists())
        assertEquals(record, store.read())
    }

    @Test
    fun malformedOversizedUnsupportedAndInvariantBreakingStateFailsClosed() = runTest {
        val cases = listOf(
            "malformed" to "{not-json".toByteArray(),
            "oversized" to ByteArray(64 * 1024 + 1),
            "unsupported-schema" to
                """{"schemaVersion":2,"phase":"DISABLED","desiredEnabled":false}""".toByteArray(),
            "invalid-phase" to
                """{"schemaVersion":1,"phase":"ACTIVE","desiredEnabled":true}""".toByteArray(),
        )

        cases.forEach { (name, bytes) ->
            val root = File(temporaryFolder.root, "load-state-$name")
            val stateDirectory = File(root, "state").apply { mkdirs() }
            val stateFile = File(stateDirectory, ANIYOMI_LOAD_STATE_FILE_NAME).apply {
                writeBytes(bytes)
            }
            val store = FileAniyomiLoadBoundaryStateStore(AniyomiLifecycleRoot.forTests(root))

            val failure = expectLoadBoundary { store.read() }

            assertEquals(name, AniyomiLoadBoundaryFailureCode.STATE_CORRUPT, failure.failure.code)
            assertTrue(name, stateFile.isFile)
            assertTrue(name, bytes.contentEquals(stateFile.readBytes()))
        }
    }

    @Test
    fun codeCacheRejectsSymbolicRootsAndGenerationsWithoutFollowingTargets() = runTest {
        val outsideRoot = File(temporaryFolder.root, "outside-cache-root").apply {
            mkdirs()
            File(this, "must-survive.txt").writeText("root-safe")
        }
        val symbolicRoot = File(temporaryFolder.root, "symbolic-cache-root")
        Files.createSymbolicLink(symbolicRoot.toPath(), outsideRoot.toPath())
        val rootStore = FileAniyomiCodeCacheStore(AniyomiCodeCacheRoot.forTests(symbolicRoot))

        val rootFailure = expectLoadBoundary { rootStore.recover() }

        assertEquals(AniyomiLoadBoundaryFailureCode.CACHE_IO, rootFailure.failure.code)
        assertEquals("root-safe", File(outsideRoot, "must-survive.txt").readText())
        assertTrue(Files.exists(symbolicRoot.toPath(), LinkOption.NOFOLLOW_LINKS))

        val cacheRoot = File(temporaryFolder.root, "generation-cache-root").apply { mkdirs() }
        val outsideGeneration = File(temporaryFolder.root, "outside-generation-cache").apply {
            mkdirs()
            File(this, "must-survive.txt").writeText("generation-safe")
        }
        val generationLink = File(cacheRoot, "b".repeat(64))
        Files.createSymbolicLink(generationLink.toPath(), outsideGeneration.toPath())
        val generationStore = FileAniyomiCodeCacheStore(AniyomiCodeCacheRoot.forTests(cacheRoot))

        val generationFailure = expectLoadBoundary { generationStore.recover() }

        assertEquals(AniyomiLoadBoundaryFailureCode.CACHE_IO, generationFailure.failure.code)
        assertEquals("generation-safe", File(outsideGeneration, "must-survive.txt").readText())
        assertTrue(Files.exists(generationLink.toPath(), LinkOption.NOFOLLOW_LINKS))

        val childRoot = File(temporaryFolder.root, "child-cache-root")
        val childStore = FileAniyomiCodeCacheStore(AniyomiCodeCacheRoot.forTests(childRoot))
        val generationId = "c".repeat(64)
        val generation = childStore.prepare(generationId)
        val outsideChild = File(temporaryFolder.root, "outside-child.txt").apply {
            writeText("child-safe")
        }
        val childLink = File(generation, "compiled-link")
        Files.createSymbolicLink(childLink.toPath(), outsideChild.toPath())

        childStore.cleanup(generationId)

        assertFalse(Files.exists(childLink.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(generation.exists())
        assertEquals("child-safe", outsideChild.readText())
    }

    @Test
    fun codeCacheFailsOnUnknownOrMalformedEntriesAndRecoversValidTreesIdempotently() = runTest {
        val unknownRoot = File(temporaryFolder.root, "unknown-cache").apply { mkdirs() }
        val unknown = File(unknownRoot, "unexpected-entry").apply { mkdirs() }
        val unknownStore = FileAniyomiCodeCacheStore(AniyomiCodeCacheRoot.forTests(unknownRoot))

        val unknownFailure = expectLoadBoundary { unknownStore.recover() }

        assertEquals(AniyomiLoadBoundaryFailureCode.CACHE_IO, unknownFailure.failure.code)
        assertTrue(unknown.isDirectory)

        val malformedRoot = File(temporaryFolder.root, "malformed-cache").apply { mkdirs() }
        val malformed = File(malformedRoot, "d".repeat(64)).apply { writeText("not-a-directory") }
        val malformedStore = FileAniyomiCodeCacheStore(AniyomiCodeCacheRoot.forTests(malformedRoot))

        val malformedFailure = expectLoadBoundary { malformedStore.recover() }

        assertEquals(AniyomiLoadBoundaryFailureCode.CACHE_IO, malformedFailure.failure.code)
        assertTrue(malformed.isFile)

        val validRoot = File(temporaryFolder.root, "valid-cache")
        val validStore = FileAniyomiCodeCacheStore(AniyomiCodeCacheRoot.forTests(validRoot))
        val generation = validStore.prepare("e".repeat(64))
        File(generation, "nested").apply {
            mkdirs()
            File(this, "compiled.bin").writeBytes(byteArrayOf(1, 2, 3))
        }

        validStore.recover()
        assertTrue(validRoot.listFiles().orEmpty().isEmpty())
        validStore.recover()
        assertTrue(validRoot.listFiles().orEmpty().isEmpty())
    }

    private fun activeRecord(generationId: String): AniyomiLoadBoundaryRecord {
        val generation = AniyomiLoadGenerationRef(
            generationId = generationId,
            policyId = "controlled-jellyfin",
            policyVersion = 1,
            factoryClassName = "eu.kanade.tachiyomi.animeextension.en.jellyfin.JellyfinFactory",
        )
        return AniyomiLoadBoundaryRecord(
            phase = AniyomiLoadBoundaryPhase.ACTIVE,
            desiredEnabled = true,
            sessionId = "00000000-0000-4000-8000-000000000001",
            target = generation,
            lastKnownGood = generation,
        )
    }

    private suspend fun expectLoadBoundary(
        block: suspend () -> Unit,
    ): AniyomiLoadBoundaryException {
        try {
            block()
        } catch (error: AniyomiLoadBoundaryException) {
            return error
        }
        throw AssertionError("Expected AniyomiLoadBoundaryException")
    }
}
