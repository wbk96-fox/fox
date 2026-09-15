package com.foxtv.app.core.plugin.cloudstream

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

internal data class ExternalExtensionArtifactSpec(
    val expectedSize: Long,
    val expectedSha256: String,
)

internal class ExternalExtensionInstallException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A fully downloaded and validated extension which has not changed the active artifact yet.
 * Closing an unactivated candidate removes only its private same-directory staging file.
 */
internal class StagedExternalExtension internal constructor(
    internal val targetFile: File,
    internal val stagedFile: File,
) : Closeable {
    private var consumed = false

    @Synchronized
    fun activateRetainingBackup(): ExternalExtensionActivation {
        check(!consumed) { "Staged extension has already been consumed" }
        val activation = ExternalExtensionInstaller.activateRetainingBackup(stagedFile, targetFile)
        consumed = true
        return activation
    }

    @Synchronized
    override fun close() {
        if (!consumed && stagedFile.exists()) {
            stagedFile.setWritable(true)
            stagedFile.delete()
        }
        consumed = true
    }
}

/**
 * An active file swap whose previous version is retained until the metadata commit succeeds.
 * [rollback] is valid only before [commit].
 */
internal class ExternalExtensionActivation internal constructor(
    internal val targetFile: File,
    internal val backupFile: File?,
) {
    private enum class State { ACTIVE, COMMITTED, ROLLED_BACK }

    private var state = State.ACTIVE

    /** Finalizes the swap. A failed backup cleanup is harmless and is retried on future staging. */
    @Synchronized
    fun commit(): Boolean {
        if (state != State.ACTIVE) return state == State.COMMITTED
        val backupRemoved = backupFile?.let { backup ->
            if (!backup.exists()) {
                true
            } else {
                backup.setWritable(true)
                backup.delete()
            }
        } ?: true
        state = State.COMMITTED
        return backupRemoved
    }

    /** Restores the exact previous target, or removes the newly-created target when none existed. */
    @Synchronized
    fun rollback() {
        if (state == State.ROLLED_BACK) return
        check(state == State.ACTIVE) { "Committed extension activation cannot be rolled back" }

        var failure: Throwable? = null
        try {
            if (targetFile.exists()) {
                targetFile.setWritable(true)
                if (!targetFile.delete()) {
                    throw ExternalExtensionInstallException("Could not remove activated extension during rollback")
                }
            }
            if (backupFile != null) {
                if (!backupFile.exists()) {
                    throw ExternalExtensionInstallException("Extension backup is missing during rollback")
                }
                ExternalExtensionInstaller.moveFile(backupFile, targetFile)
                if (!targetFile.setReadOnly() && targetFile.canWrite()) {
                    throw ExternalExtensionInstallException("Could not make restored extension read-only")
                }
            }
        } catch (rollbackFailure: Throwable) {
            failure = rollbackFailure
        } finally {
            state = State.ROLLED_BACK
        }
        if (failure != null) throw failure
    }
}

/**
 * Installs Cloudstream .cs3 files through same-directory staging. Downloaded bytes are validated
 * before activation, while an activation retains the previous target until its caller commits the
 * corresponding repository metadata.
 */
internal object ExternalExtensionInstaller {
    private const val BUFFER_SIZE = 32 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 256
    private const val MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 64L * 1024L
    private val dexEntryPattern = Regex("^classes(?:[2-9][0-9]*)?\\.dex$")
    private val pluginClassPattern = Regex("\\\"pluginClassName\\\"\\s*:\\s*\\\"[^\\\"]+\\\"")

    /** Downloads and validates an artifact without touching [targetFile]. */
    suspend fun stageAndValidate(
        targetFile: File,
        source: InputStream,
        declaredContentLength: Long?,
        spec: ExternalExtensionArtifactSpec,
        loadabilityValidator: (File) -> Boolean,
    ): StagedExternalExtension {
        validateSpec(spec)
        val parent = targetFile.parentFile
            ?: throw ExternalExtensionInstallException("Extension target has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw ExternalExtensionInstallException("Could not create extension directory")
        }
        if (!parent.isDirectory) {
            throw ExternalExtensionInstallException("Extension parent is not a directory")
        }

        val normalizedLength = declaredContentLength?.takeIf { it >= 0L }
        if (normalizedLength != null && normalizedLength != spec.expectedSize) {
            throw ExternalExtensionInstallException("HTTP Content-Length does not match fileSize")
        }

        removeAbandonedBackups(targetFile)
        val staged = File.createTempFile(".${targetFile.name}.", ".staged", parent)
        var success = false
        try {
            writeAndVerify(staged, source, spec)
            validateArchive(staged)
            if (!staged.setReadOnly() && staged.canWrite()) {
                throw ExternalExtensionInstallException("Could not make staged DEX read-only")
            }
            val loadable = try {
                loadabilityValidator(staged)
            } catch (e: Exception) {
                throw ExternalExtensionInstallException("Staged extension failed class-load validation", e)
            } catch (e: LinkageError) {
                throw ExternalExtensionInstallException("Staged extension has incompatible linkage", e)
            }
            if (!loadable) {
                throw ExternalExtensionInstallException("Staged extension contains no loadable plugin")
            }
            currentCoroutineContext().ensureActive()
            success = true
            return StagedExternalExtension(targetFile = targetFile, stagedFile = staged)
        } finally {
            if (!success && staged.exists()) {
                staged.setWritable(true)
                staged.delete()
            }
        }
    }

    /** Compatibility wrapper for callers which do not need a wider metadata transaction. */
    suspend fun install(
        targetFile: File,
        source: InputStream,
        declaredContentLength: Long?,
        spec: ExternalExtensionArtifactSpec,
        loadabilityValidator: (File) -> Boolean,
    ): File {
        val staged = stageAndValidate(
            targetFile = targetFile,
            source = source,
            declaredContentLength = declaredContentLength,
            spec = spec,
            loadabilityValidator = loadabilityValidator,
        )
        var activation: ExternalExtensionActivation? = null
        try {
            currentCoroutineContext().ensureActive()
            activation = staged.activateRetainingBackup()
            activation.commit()
            return targetFile
        } catch (failure: Throwable) {
            if (activation != null) {
                try {
                    activation.rollback()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        } finally {
            staged.close()
        }
    }

    internal fun activateRetainingBackup(
        staged: File,
        target: File,
    ): ExternalExtensionActivation {
        val backup = if (target.exists()) {
            File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.backup").also {
                moveFile(target, it)
            }
        } else {
            null
        }

        try {
            moveFile(staged, target)
            if (!target.isFile || target.length() <= 0L) {
                throw ExternalExtensionInstallException("Activated extension is missing")
            }
            return ExternalExtensionActivation(targetFile = target, backupFile = backup)
        } catch (activationFailure: Throwable) {
            if (target.exists()) {
                target.setWritable(true)
                target.delete()
            }
            if (backup?.exists() == true) {
                try {
                    moveFile(backup, target)
                    target.setReadOnly()
                } catch (rollbackFailure: Throwable) {
                    activationFailure.addSuppressed(rollbackFailure)
                }
            }
            throw ExternalExtensionInstallException(
                "Extension activation failed; rollback attempted",
                activationFailure,
            )
        }
    }

    private fun validateSpec(spec: ExternalExtensionArtifactSpec) {
        if (spec.expectedSize !in 1..MAX_EXTERNAL_EXTENSION_BYTES) {
            throw ExternalExtensionInstallException("Expected extension size is outside the supported range")
        }
        if (!Regex("^[0-9a-f]{64}$").matches(spec.expectedSha256)) {
            throw ExternalExtensionInstallException("Expected SHA-256 is invalid")
        }
    }

    private suspend fun writeAndVerify(
        staged: File,
        source: InputStream,
        spec: ExternalExtensionArtifactSpec,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileOutputStream(staged).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > spec.expectedSize || total > MAX_EXTERNAL_EXTENSION_BYTES) {
                    throw ExternalExtensionInstallException("Extension exceeds its declared size")
                }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            output.flush()
            output.fd.sync()
        }

        if (total != spec.expectedSize) {
            throw ExternalExtensionInstallException("Downloaded size does not match fileSize")
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualHash.equals(spec.expectedSha256, ignoreCase = false)) {
            throw ExternalExtensionInstallException("Downloaded SHA-256 does not match fileHash")
        }
    }

    private fun validateArchive(file: File) {
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                if (entries.isEmpty() || entries.size > MAX_ARCHIVE_ENTRIES) {
                    throw ExternalExtensionInstallException("Invalid .cs3 entry count")
                }

                val names = mutableSetOf<String>()
                var totalUncompressed = 0L
                entries.forEach { entry ->
                    validateEntryName(entry.name)
                    if (!names.add(entry.name)) {
                        throw ExternalExtensionInstallException("Duplicate .cs3 entry: ${entry.name}")
                    }
                    if (entry.size < 0L) {
                        throw ExternalExtensionInstallException("Unknown uncompressed entry size")
                    }
                    totalUncompressed += entry.size
                    if (totalUncompressed > MAX_UNCOMPRESSED_BYTES) {
                        throw ExternalExtensionInstallException("Uncompressed .cs3 exceeds the safety budget")
                    }
                }

                if (names.contains("AndroidManifest.xml")) {
                    throw ExternalExtensionInstallException("Android APK files are not Cloudstream .cs3 plugins")
                }

                val manifest = zip.getEntry("manifest.json")
                    ?: throw ExternalExtensionInstallException(".cs3 manifest.json is missing")
                if (manifest.size !in 1..MAX_MANIFEST_BYTES) {
                    throw ExternalExtensionInstallException(".cs3 manifest.json has an invalid size")
                }
                val manifestText = zip.getInputStream(manifest).bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (!pluginClassPattern.containsMatchIn(manifestText)) {
                    throw ExternalExtensionInstallException(".cs3 manifest has no pluginClassName")
                }

                val primaryDex = zip.getEntry("classes.dex")
                    ?: throw ExternalExtensionInstallException(".cs3 classes.dex is missing")
                entries.filter { dexEntryPattern.matches(it.name) }.forEach { dexEntry ->
                    if (dexEntry.size < 8L) {
                        throw ExternalExtensionInstallException("DEX entry is truncated: ${dexEntry.name}")
                    }
                    val magic = ByteArray(8)
                    val count = zip.getInputStream(dexEntry).use { it.read(magic) }
                    val validMagic = count == magic.size &&
                        magic[0] == 'd'.code.toByte() &&
                        magic[1] == 'e'.code.toByte() &&
                        magic[2] == 'x'.code.toByte() &&
                        magic[3] == '\n'.code.toByte() &&
                        magic.sliceArray(4..6).all { it.toInt().toChar().isDigit() } &&
                        magic[7] == 0.toByte()
                    if (!validMagic) {
                        throw ExternalExtensionInstallException("Invalid DEX magic: ${dexEntry.name}")
                    }
                }
                if (!dexEntryPattern.matches(primaryDex.name)) {
                    throw ExternalExtensionInstallException("Invalid primary DEX entry")
                }
            }
        } catch (e: ExternalExtensionInstallException) {
            throw e
        } catch (e: Exception) {
            throw ExternalExtensionInstallException("Malformed .cs3 archive", e)
        }
    }

    private fun validateEntryName(rawName: String) {
        val name = rawName.replace('\\', '/')
        val segments = name.split('/')
        if (name.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(name) ||
            segments.any { it == ".." || it.isEmpty() && segments.size > 1 && it != segments.last() }
        ) {
            throw ExternalExtensionInstallException("Unsafe .cs3 entry path")
        }
    }

    private fun removeAbandonedBackups(target: File) {
        val parent = target.parentFile ?: return
        val prefix = ".${target.name}."
        parent.listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".backup") }
            .forEach { backup ->
                // A backup without an active target may be from an interrupted activation. Restore
                // it instead of deleting the only known-good artifact.
                if (!target.exists()) {
                    try {
                        moveFile(backup, target)
                        target.setReadOnly()
                    } catch (_: Exception) {
                        return
                    }
                } else {
                    backup.setWritable(true)
                    backup.delete()
                }
            }
    }

    internal fun moveFile(source: File, destination: File) {
        if (destination.exists()) {
            throw ExternalExtensionInstallException("Move destination already exists")
        }
        if (source.renameTo(destination)) return

        try {
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!source.delete()) {
                throw ExternalExtensionInstallException("Could not remove source after staged copy")
            }
        } catch (e: Exception) {
            destination.setWritable(true)
            destination.delete()
            if (e is ExternalExtensionInstallException) throw e
            throw ExternalExtensionInstallException("Could not move staged extension", e)
        }
    }
}
