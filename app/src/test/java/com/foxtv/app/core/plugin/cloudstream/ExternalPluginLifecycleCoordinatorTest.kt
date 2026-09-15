package com.foxtv.app.core.plugin.cloudstream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExternalPluginLifecycleCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `metadata failure rolls back every activated artifact`() = runBlocking {
        val coordinator = ExternalPluginLifecycleCoordinator()
        val firstTarget = oldTarget("first", "old-first")
        val secondTarget = oldTarget("second", "old-second")
        val firstBefore = firstTarget.readBytes()
        val secondBefore = secondTarget.readBytes()
        val firstArtifact = validCs3("example.FirstPlugin")
        val secondArtifact = validCs3("example.SecondPlugin")
        val staged = listOf(
            stage(firstTarget, firstArtifact),
            stage(secondTarget, secondArtifact),
        )

        val failure = runCatching {
            coordinator.activateAndCommit(staged) {
                throw IOException("datastore commit failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertArrayEquals(firstBefore, firstTarget.readBytes())
        assertArrayEquals(secondBefore, secondTarget.readBytes())
        assertNoTransactionFiles(firstTarget.parentFile)
        assertNoTransactionFiles(secondTarget.parentFile)
    }

    @Test
    fun `cancellation before metadata commit restores old artifact and propagates`() = runBlocking {
        val coordinator = ExternalPluginLifecycleCoordinator()
        val target = oldTarget("cancel", "known-good")
        val before = target.readBytes()
        val artifact = validCs3("example.CancelledPlugin")

        val failure = runCatching {
            coordinator.activateAndCommit(listOf(stage(target, artifact))) {
                throw CancellationException("cancel refresh")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertArrayEquals(before, target.readBytes())
        assertNoTransactionFiles(target.parentFile)
    }

    @Test
    fun `successful metadata commit keeps all new artifacts and removes backups`() = runBlocking {
        val coordinator = ExternalPluginLifecycleCoordinator()
        val firstTarget = oldTarget("commit-first", "old-first")
        val secondTarget = oldTarget("commit-second", "old-second")
        val firstArtifact = validCs3("example.FirstPlugin")
        val secondArtifact = validCs3("example.SecondPlugin")

        val result = coordinator.activateAndCommit(
            listOf(stage(firstTarget, firstArtifact), stage(secondTarget, secondArtifact)),
        ) { "committed" }

        assertEquals("committed", result)
        assertArrayEquals(firstArtifact, firstTarget.readBytes())
        assertArrayEquals(secondArtifact, secondTarget.readBytes())
        assertNoTransactionFiles(firstTarget.parentFile)
        assertNoTransactionFiles(secondTarget.parentFile)
    }

    @Test
    fun `serialized lifecycle operations never overlap`() = runBlocking {
        val coordinator = ExternalPluginLifecycleCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondAttempted = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        val first = async {
            coordinator.serialized {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now, ::maxOf)
                firstEntered.complete(Unit)
                releaseFirst.await()
                active.decrementAndGet()
            }
        }
        firstEntered.await()

        val second = async {
            secondAttempted.complete(Unit)
            coordinator.serialized {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now, ::maxOf)
                secondEntered.complete(Unit)
                active.decrementAndGet()
            }
        }
        secondAttempted.await()
        yield()
        assertFalse(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered.isCompleted)
        assertEquals(1, maxActive.get())
    }

    private suspend fun stage(target: File, artifact: ByteArray): StagedExternalExtension =
        ExternalExtensionInstaller.stageAndValidate(
            targetFile = target,
            source = ByteArrayInputStream(artifact),
            declaredContentLength = artifact.size.toLong(),
            spec = ExternalExtensionArtifactSpec(
                expectedSize = artifact.size.toLong(),
                expectedSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(artifact)
                    .joinToString("") { "%02x".format(it) },
            ),
            loadabilityValidator = { true },
        )

    private fun oldTarget(name: String, content: String): File {
        val directory = temporaryFolder.newFolder("extensions-$name")
        return File(directory, "$name.cs3").apply { writeText(content) }
    }

    private fun validCs3(pluginClassName: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """{"name":"Provider","version":1,"pluginClassName":"$pluginClassName"}"""
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(
                ByteArray(128).also { dex ->
                    byteArrayOf(
                        'd'.code.toByte(),
                        'e'.code.toByte(),
                        'x'.code.toByte(),
                        '\n'.code.toByte(),
                        '0'.code.toByte(),
                        '3'.code.toByte(),
                        '5'.code.toByte(),
                        0,
                    ).copyInto(dex)
                },
            )
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun assertNoTransactionFiles(directory: File?) {
        assertFalse(
            requireNotNull(directory).listFiles().orEmpty().any {
                it.name.endsWith(".backup") || it.name.endsWith(".staged")
            },
        )
    }
}
