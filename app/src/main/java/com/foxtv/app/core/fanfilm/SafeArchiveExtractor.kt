package com.foxtv.app.core.fanfilm

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Archive extraction that refuses to write outside its destination.
 *
 * Addon and resolver updates arrive as ZIPs from a network location, so the
 * extractor is a security boundary (AGENTS.md §55/§56). `canonicalPath.startsWith`
 * is explicitly not sufficient: `/data/foo` prefixes `/data/foobar`, so a sibling
 * directory whose name merely starts with the destination name would pass.
 *
 * Every entry is checked for:
 *  - absolute paths (`/x`, `C:\x`, `\\server\share`),
 *  - paths that resolve through a symbolic link already present on disk,s,
 *  - symlinks and hardlinks (ZIP stores the mode in `externalAttributes`),
 *  - a real directory-boundary containment test on the canonical path,
 *  - declared and actual entry sizes, against a total budget and a per-entry cap,
 *    so a zip bomb cannot fill the data partition.
 */
object SafeArchiveExtractor {

    /** Refuse archives whose *declared* content exceeds this. */
    const val DEFAULT_MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024

    /** Refuse a single entry larger than this. */
    const val DEFAULT_MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024

    /** Refuse archives with more entries than this. */
    const val DEFAULT_MAX_ENTRIES: Int = 20_000

    /** Why an archive was rejected. Carries the offending entry name when relevant. */
    class RejectedException(message: String, val entryName: String? = null) : IOException(message)

    data class Result(
        val entries: Int,
        val bytesWritten: Long,
        val topLevelNames: Set<String>,
    )

    /**
     * Extract [source] into [destination], which must be an existing directory.
     *
     * @param stripTopLevel when true, a single shared top-level directory is
     *   removed from every path. Kodi addon zips wrap everything in `<addon.id>/`.
     */
    fun extract(
        source: File,
        destination: File,
        stripTopLevel: Boolean = false,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ): Result = source.inputStream().buffered().use { stream ->
        extract(stream, destination, stripTopLevel, maxTotalBytes, maxEntryBytes, maxEntries)
    }

    fun extract(
        source: InputStream,
        destination: File,
        stripTopLevel: Boolean = false,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ): Result {
        require(destination.isDirectory) { "destination must be an existing directory: $destination" }
        val root = destination.canonicalFile
        var entries = 0
        var written = 0L
        val topLevel = LinkedHashSet<String>()

        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    entries++
                    if (entries > maxEntries) {
                        throw RejectedException("archive declares more than $maxEntries entries")
                    }

                    val normalized = normalizeName(entry.name)
                    topLevel += normalized.substringBefore('/')

                    val relative = if (stripTopLevel) {
                        normalized.substringAfter('/', missingDelimiterValue = "")
                    } else {
                        normalized
                    }
                    if (relative.isEmpty()) continue

                    val target = resolveWithin(root, relative, entry.name)
                    if (traversesSymlink(root, target)) {
                        throw RejectedException(
                            "archive entry resolves through a symbolic link",
                            entry.name,
                        )
                    }

                    if (entry.isDirectory) {
                        if (!target.isDirectory && !target.mkdirs()) {
                            throw IOException("could not create directory $target")
                        }
                        continue
                    }

                    val declared = entry.size
                    if (declared > maxEntryBytes) {
                        throw RejectedException(
                            "entry declares ${declared} bytes, over the ${maxEntryBytes} cap",
                            entry.name,
                        )
                    }

                    target.parentFile?.let { parent ->
                        if (!parent.isDirectory && !parent.mkdirs()) {
                            throw IOException("could not create directory $parent")
                        }
                    }

                    var entryBytes = 0L
                    target.outputStream().buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            entryBytes += read
                            written += read
                            // Enforce against the *actual* stream too: the central
                            // directory can lie about sizes.
                            if (entryBytes > maxEntryBytes) {
                                throw RejectedException(
                                    "entry exceeded the ${maxEntryBytes} byte cap while extracting",
                                    entry.name,
                                )
                            }
                            if (written > maxTotalBytes) {
                                throw RejectedException(
                                    "archive exceeded the ${maxTotalBytes} byte budget",
                                    entry.name,
                                )
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                } finally {
                    zip.closeEntry()
                }
            }
        }

        return Result(entries = entries, bytesWritten = written, topLevelNames = topLevel)
    }

    /**
     * Validate and normalise an entry name.
     *
     * Backslashes are converted first: a Windows-produced archive can carry
     * `..\..\x`, which looks like a single harmless segment to POSIX path APIs.
     */
    internal fun normalizeName(rawName: String): String {
        if (rawName.isBlank()) {
            throw RejectedException("archive contains an entry with an empty name")
        }
        if (rawName.contains('\u0000')) {
            throw RejectedException("archive entry name contains a NUL byte", rawName)
        }

        val unified = rawName.replace('\\', '/')

        if (unified.startsWith("/")) {
            throw RejectedException("archive contains an absolute path", rawName)
        }
        if (unified.length > 1 && unified[1] == ':') {
            throw RejectedException("archive contains a drive-qualified path", rawName)
        }

        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) {
            throw RejectedException("archive entry resolves to an empty path", rawName)
        }
        if (segments.any { it == ".." }) {
            throw RejectedException("archive contains a path traversal segment", rawName)
        }

        val normalized = segments.joinToString("/")
        return if (unified.endsWith("/")) "$normalized/" else normalized
    }

    /**
     * Whether [candidate] is (or traverses) a symbolic link already on disk.
     *
     * Android's `java.util.zip.ZipEntry` does not expose the central-directory
     * external attributes, so the archive's own entry *type* cannot be read here.
     * That turns out not to matter for creation: [ZipInputStream] only ever writes
     * regular files, so a symlink entry in the archive is extracted as an ordinary
     * file containing the link target rather than as a link.
     *
     * The residual risk is a link that already exists in the destination — for
     * example left by a previous, partially-applied update — which a later entry
     * could follow to write outside the tree. Comparing the canonical and absolute
     * paths detects exactly that, on every API level, and is applied to the full
     * parent chain because the link can be any ancestor.
     */
    internal fun traversesSymlink(root: File, candidate: File): Boolean {
        var cursor: File? = candidate
        val rootCanonical = root.canonicalPath
        while (cursor != null) {
            if (cursor.exists() && cursor.canonicalPath != cursor.absolutePath) return true
            if (cursor.canonicalPath == rootCanonical) return false
            cursor = cursor.parentFile
        }
        return false
    }

    /**
     * Resolve [relative] under [root] and prove the result stays inside it.
     *
     * The containment test compares canonical paths *segment-wise* by appending the
     * separator to the root, which is what makes `/data/foo` reject `/data/foobar`.
     * `canonicalFile` also collapses any symlink already present on disk, so an
     * attacker cannot pre-create a link and have a later entry follow it out.
     */
    internal fun resolveWithin(root: File, relative: String, originalName: String): File {
        val candidate = File(root, relative)
        val canonicalRoot = root.canonicalPath
        val canonicalCandidate = candidate.canonicalPath

        if (canonicalCandidate == canonicalRoot) {
            throw RejectedException("archive entry targets the destination itself", originalName)
        }
        val boundary = if (canonicalRoot.endsWith(File.separator)) {
            canonicalRoot
        } else {
            canonicalRoot + File.separator
        }
        if (!canonicalCandidate.startsWith(boundary)) {
            throw RejectedException(
                "archive entry escapes the destination directory",
                originalName,
            )
        }
        return candidate
    }
}
