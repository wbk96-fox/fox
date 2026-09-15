package com.foxtv.app.core.fanfilm

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Extraction is a security boundary: addon and resolver updates are downloaded archives.
 * These tests assert the rejections rather than the happy path, because a silent
 * acceptance is what would actually hurt.
 */
class SafeArchiveExtractorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content)
                out.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun extract(bytes: ByteArray, destination: File, stripTopLevel: Boolean = false) =
        SafeArchiveExtractor.extract(
            source = bytes.inputStream(),
            destination = destination,
            stripTopLevel = stripTopLevel,
        )

    @Test
    fun `extracts a well formed addon package`() {
        val destination = temporaryFolder.newFolder("out")
        val result = extract(
            zip(
                "plugin.video.fanfilm/addon.xml" to "<addon/>".toByteArray(),
                "plugin.video.fanfilm/lib/main.py" to "print(1)".toByteArray(),
            ),
            destination,
        )

        assertEquals(setOf("plugin.video.fanfilm"), result.topLevelNames)
        assertTrue(File(destination, "plugin.video.fanfilm/addon.xml").isFile)
        assertTrue(File(destination, "plugin.video.fanfilm/lib/main.py").isFile)
    }

    @Test
    fun `strips the top level directory when asked`() {
        val destination = temporaryFolder.newFolder("out")
        extract(
            zip("script.module.resolveurl/lib/resolveurl/__init__.py" to "x".toByteArray()),
            destination,
            stripTopLevel = true,
        )
        assertTrue(File(destination, "lib/resolveurl/__init__.py").isFile)
    }

    @Test
    fun `rejects a parent directory traversal`() {
        val destination = temporaryFolder.newFolder("out")
        val failure = runCatching {
            extract(zip("../escaped.txt" to "x".toByteArray()), destination)
        }.exceptionOrNull()

        assertTrue(failure is SafeArchiveExtractor.RejectedException)
        assertFalse(File(destination.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `rejects a traversal hidden in a deeper path`() {
        val destination = temporaryFolder.newFolder("out")
        val failure = runCatching {
            extract(zip("addon/lib/../../../escaped.txt" to "x".toByteArray()), destination)
        }.exceptionOrNull()
        assertTrue(failure is SafeArchiveExtractor.RejectedException)
    }

    @Test
    fun `rejects a backslash separated traversal produced on Windows`() {
        // POSIX path APIs treat "..\..\x" as one harmless segment, so the separator has to
        // be normalised before the traversal check, not after.
        val destination = temporaryFolder.newFolder("out")
        val failure = runCatching {
            extract(zip("""..\..\escaped.txt""" to "x".toByteArray()), destination)
        }.exceptionOrNull()
        assertTrue(failure is SafeArchiveExtractor.RejectedException)
    }

    @Test
    fun `rejects an absolute path`() {
        val destination = temporaryFolder.newFolder("out")
        val failure = runCatching {
            extract(zip("/etc/passwd" to "x".toByteArray()), destination)
        }.exceptionOrNull()
        assertTrue(failure is SafeArchiveExtractor.RejectedException)
    }

    @Test
    fun `rejects a drive qualified path`() {
        val destination = temporaryFolder.newFolder("out")
        val failure = runCatching {
            extract(zip("""C:\windows\system32\evil.dll""" to "x".toByteArray()), destination)
        }.exceptionOrNull()
        assertTrue(failure is SafeArchiveExtractor.RejectedException)
    }

    @Test
    fun `rejects an entry that exceeds the total budget`() {
        val destination = temporaryFolder.newFolder("out")
        val payload = ByteArray(4096) { 'a'.code.toByte() }
        val failure = runCatching {
            SafeArchiveExtractor.extract(
                source = zip("addon/big.bin" to payload).inputStream(),
                destination = destination,
                maxTotalBytes = 1024,
            )
        }.exceptionOrNull()
        assertTrue(failure is SafeArchiveExtractor.RejectedException)
    }

    @Test
    fun `rejects an entry that exceeds the per entry cap while streaming`() {
        // The central directory can understate a size, so the cap must also be enforced
        // against the bytes actually read.
        val destination = temporaryFolder.newFolder("out")
        val payload = ByteArray(8192) { 'b'.code.toByte() }
        val failure = runCatching {
            SafeArchiveExtractor.extract(
                source = zip("addon/big.bin" to payload).inputStream(),
                destination = destination,
                maxEntryBytes = 2048,
            )
        }.exceptionOrNull()
        assertTrue(failure is SafeArchiveExtractor.RejectedException)
    }

    @Test
    fun `rejects an archive with too many entries`() {
        val destination = temporaryFolder.newFolder("out")
        val entries = (0 until 50).map { "addon/f$it.txt" to "x".toByteArray() }
        val failure = runCatching {
            SafeArchiveExtractor.extract(
                source = zip(*entries.toTypedArray()).inputStream(),
                destination = destination,
                maxEntries = 10,
            )
        }.exceptionOrNull()
        assertTrue(failure is SafeArchiveExtractor.RejectedException)
    }

    @Test
    fun `containment check distinguishes a sibling with a shared prefix`() {
        // The bug a bare startsWith() has: "/tmp/foo" is a prefix of "/tmp/foobar", so a
        // sibling directory would pass a naive containment test.
        val root = temporaryFolder.newFolder("foo")
        val sibling = temporaryFolder.newFolder("foobar")
        File(sibling, "x.txt").writeText("x")

        val failure = runCatching {
            SafeArchiveExtractor.resolveWithin(root, "../foobar/x.txt", "../foobar/x.txt")
        }.exceptionOrNull()
        assertTrue(failure is SafeArchiveExtractor.RejectedException)
    }

    @Test
    fun `normalises redundant path segments`() {
        assertEquals("addon/lib/x.py", SafeArchiveExtractor.normalizeName("./addon//lib/x.py"))
        assertEquals("addon/lib/", SafeArchiveExtractor.normalizeName("addon/lib/"))
    }

    @Test
    fun `rejects an empty or NUL bearing name`() {
        assertTrue(
            runCatching { SafeArchiveExtractor.normalizeName("  ") }
                .exceptionOrNull() is SafeArchiveExtractor.RejectedException
        )
        assertTrue(
            runCatching { SafeArchiveExtractor.normalizeName("addon/x\u0000.py") }
                .exceptionOrNull() is SafeArchiveExtractor.RejectedException
        )
    }
}
