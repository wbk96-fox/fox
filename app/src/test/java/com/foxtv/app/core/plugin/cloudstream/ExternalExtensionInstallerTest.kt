package com.foxtv.app.core.plugin.cloudstream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExternalExtensionInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `valid artifact replaces old file only after all validation succeeds`() = runBlocking {
        val target = targetWithOldContent()
        val artifact = validCs3()

        val installed = ExternalExtensionInstaller.install(
            targetFile = target,
            source = ByteArrayInputStream(artifact),
            declaredContentLength = artifact.size.toLong(),
            spec = specFor(artifact),
            loadabilityValidator = { true },
        )

        assertArrayEquals(artifact, installed.readBytes())
        val parent = requireNotNull(target.parentFile)
        assertFalse(parent.listFiles().orEmpty().any { it.name.endsWith(".staged") })
        assertFalse(parent.listFiles().orEmpty().any { it.name.endsWith(".backup") })
    }

    @Test
    fun `staged activation retains backup until explicit metadata commit`() = runBlocking {
        val target = targetWithOldContent()
        val oldBytes = target.readBytes()
        val artifact = validCs3()
        val staged = ExternalExtensionInstaller.stageAndValidate(
            targetFile = target,
            source = ByteArrayInputStream(artifact),
            declaredContentLength = artifact.size.toLong(),
            spec = specFor(artifact),
            loadabilityValidator = { true },
        )

        assertArrayEquals(oldBytes, target.readBytes())
        val activation = staged.activateRetainingBackup()
        assertArrayEquals(artifact, target.readBytes())
        assertTrue(requireNotNull(activation.backupFile).exists())

        activation.commit()
        staged.close()
        assertFalse(requireNotNull(target.parentFile).listFiles().orEmpty().any {
            it.name.endsWith(".backup") || it.name.endsWith(".staged")
        })
    }

    @Test
    fun `hash mismatch preserves previous active artifact`() = runBlocking {
        val target = targetWithOldContent()
        val before = target.readBytes()
        val artifact = validCs3()

        val failure = runCatching {
            ExternalExtensionInstaller.install(
                target,
                ByteArrayInputStream(artifact),
                artifact.size.toLong(),
                specFor(artifact).copy(expectedSha256 = "0".repeat(64)),
                loadabilityValidator = { true },
            )
        }

        assertTrue(failure.exceptionOrNull() is ExternalExtensionInstallException)
        assertArrayEquals(before, target.readBytes())
    }

    @Test
    fun `size mismatch preserves previous active artifact`() = runBlocking {
        val target = targetWithOldContent()
        val before = target.readBytes()
        val artifact = validCs3()

        val failure = runCatching {
            ExternalExtensionInstaller.install(
                target,
                ByteArrayInputStream(artifact),
                artifact.size.toLong(),
                specFor(artifact).copy(expectedSize = artifact.size + 1L),
                loadabilityValidator = { true },
            )
        }

        assertTrue(failure.exceptionOrNull() is ExternalExtensionInstallException)
        assertArrayEquals(before, target.readBytes())
    }

    @Test
    fun `malformed cs3 never reaches activation and preserves old file`() = runBlocking {
        val target = targetWithOldContent()
        val before = target.readBytes()
        val malformed = zip(
            "manifest.json" to manifestBytes(),
            "classes.dex" to "not-a-dex".toByteArray(),
        )
        var loadabilityCalled = false

        val failure = runCatching {
            ExternalExtensionInstaller.install(
                target,
                ByteArrayInputStream(malformed),
                malformed.size.toLong(),
                specFor(malformed),
                loadabilityValidator = {
                    loadabilityCalled = true
                    true
                },
            )
        }

        assertTrue(failure.exceptionOrNull() is ExternalExtensionInstallException)
        assertFalse(loadabilityCalled)
        assertArrayEquals(before, target.readBytes())
    }

    @Test
    fun `failed staged class load preserves old file`() = runBlocking {
        val target = targetWithOldContent()
        val before = target.readBytes()
        val artifact = validCs3()

        val failure = runCatching {
            ExternalExtensionInstaller.install(
                target,
                ByteArrayInputStream(artifact),
                artifact.size.toLong(),
                specFor(artifact),
                loadabilityValidator = { false },
            )
        }

        assertTrue(failure.exceptionOrNull() is ExternalExtensionInstallException)
        assertArrayEquals(before, target.readBytes())
    }

    @Test
    fun `Android APK is rejected instead of being treated as cs3`() = runBlocking {
        val target = File(temporaryFolder.newFolder("apk-target"), "Provider.cs3")
        val apk = zip(
            "AndroidManifest.xml" to "binary-manifest".toByteArray(),
            "manifest.json" to manifestBytes(),
            "classes.dex" to validDex(),
        )

        val failure = runCatching {
            ExternalExtensionInstaller.install(
                target,
                ByteArrayInputStream(apk),
                apk.size.toLong(),
                specFor(apk),
                loadabilityValidator = { true },
            )
        }

        assertTrue(failure.exceptionOrNull() is ExternalExtensionInstallException)
        assertFalse(target.exists())
    }

    private fun targetWithOldContent(): File {
        val directory = temporaryFolder.newFolder("extensions-${System.nanoTime()}")
        return File(directory, "Provider.cs3").apply { writeText("known-good-old-version") }
    }

    private fun validCs3(): ByteArray = zip(
        "manifest.json" to manifestBytes(),
        "classes.dex" to validDex(),
    )

    private fun manifestBytes(): ByteArray =
        """{"name":"Provider","version":1,"pluginClassName":"example.ProviderPlugin"}"""
            .toByteArray()

    private fun validDex(): ByteArray = ByteArray(128).also { dex ->
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
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun specFor(bytes: ByteArray) = ExternalExtensionArtifactSpec(
        expectedSize = bytes.size.toLong(),
        expectedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) },
    )
}
