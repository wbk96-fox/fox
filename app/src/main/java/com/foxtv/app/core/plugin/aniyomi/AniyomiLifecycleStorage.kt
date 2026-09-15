package com.foxtv.app.core.plugin.aniyomi

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val ANIYOMI_LIFECYCLE_ROOT_NAME = "aniyomi_extensions"
private const val ANIYOMI_CANDIDATE_FILE_NAME = "candidate.apk"
private const val ANIYOMI_STATE_FILE_NAME = "lifecycle.json"
private const val ANIYOMI_MAX_STATE_BYTES = 256 * 1024
private val ANIYOMI_STORAGE_SHA_PATTERN = Regex("^[0-9a-f]{64}$")
private val ANIYOMI_STORAGE_TRANSACTION_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

/** Android DCL requires the source mode to contain no writable permission for the app. */
internal fun File.hasReadOnlyAniyomiCodePermissions(): Boolean = isFile && !canWrite()

@Singleton
internal class AniyomiLifecycleRoot private constructor(
    internal val directory: File,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.filesDir, ANIYOMI_LIFECYCLE_ROOT_NAME),
    )

    companion object {
        fun forTests(directory: File): AniyomiLifecycleRoot = AniyomiLifecycleRoot(directory)
    }
}

internal interface AniyomiLifecycleStateStore {
    suspend fun read(): AniyomiLifecycleRecord?
    suspend fun write(record: AniyomiLifecycleRecord?)
}

internal data class PublishedAniyomiGeneration(
    val file: File,
    val reusedExisting: Boolean,
)

internal interface AniyomiArtifactStore {
    suspend fun createTransaction(transactionId: String)

    suspend fun copyCapabilityToTransaction(
        source: InspectedAniyomiApk,
        transactionId: String,
        policy: AniyomiTrustedPolicy,
    ): File

    suspend fun publishGeneration(candidate: AniyomiArtifactIdentity): PublishedAniyomiGeneration

    suspend fun resolve(artifact: AniyomiArtifactIdentity): File

    suspend fun cleanupTransaction(transactionId: String)

    suspend fun quarantine(artifact: AniyomiArtifactIdentity, reason: String)

    suspend fun quarantineUnknownEntries(activeTransactionId: String?)
}

/**
 * Separator-aware resolver for the dedicated app-private Aniyomi tree.
 * It never follows a symbolic link and never recursively deletes an unknown entry.
 */
internal class AniyomiStoragePaths(
    private val configuredRoot: File,
) {
    val root: File
        get() = requireDirectory(configuredRoot, create = true)

    fun generations(): File = requireDirectory(File(root, "generations"), create = true)

    fun transactions(): File = requireDirectory(File(root, "transactions"), create = true)

    fun state(): File = requireDirectory(File(root, "state"), create = true)

    fun quarantine(): File = requireDirectory(File(root, "quarantine"), create = true)

    fun transactionDirectory(transactionId: String, create: Boolean): File {
        requireTransactionId(transactionId)
        return requireDirectory(File(transactions(), transactionId), create)
    }

    fun transactionCandidate(transactionId: String, requireRegular: Boolean): File {
        val file = requireContained(File(transactionDirectory(transactionId, create = false), ANIYOMI_CANDIDATE_FILE_NAME))
        if (requireRegular) requireRegularFile(file)
        return file
    }

    fun generationDirectory(generationId: String, create: Boolean): File {
        requireGenerationId(generationId)
        return requireDirectory(File(generations(), generationId), create)
    }

    fun generationApk(generationId: String, requireRegular: Boolean): File {
        val file = requireContained(File(generationDirectory(generationId, create = false), ANIYOMI_APK_FILE_NAME))
        if (requireRegular) requireRegularFile(file)
        return file
    }

    fun resolve(artifact: AniyomiArtifactIdentity, requireRegular: Boolean): File {
        val expected = when (artifact.kind) {
            AniyomiStoredArtifactKind.TRANSACTION -> {
                val transactionId = requireNotNull(artifact.transactionId)
                require(artifact.relativePath == "transactions/$transactionId/$ANIYOMI_CANDIDATE_FILE_NAME")
                transactionCandidate(transactionId, requireRegular)
            }

            AniyomiStoredArtifactKind.GENERATION -> {
                require(artifact.relativePath == "generations/${artifact.generationId}/$ANIYOMI_APK_FILE_NAME")
                generationApk(artifact.generationId, requireRegular)
            }
        }
        return expected
    }

    fun requireContained(file: File): File {
        val safeRoot = requireDirectory(configuredRoot, create = true)
        val rootAbsolute = safeRoot.absoluteFile
        val targetAbsolute = file.absoluteFile
        val rootPrefix = rootAbsolute.path + File.separator
        if (targetAbsolute.path != rootAbsolute.path && !targetAbsolute.path.startsWith(rootPrefix)) {
            unsafePath("Path escapes the dedicated Aniyomi root: ${targetAbsolute.path}")
        }
        if (containsTraversalSegment(targetAbsolute.path)) {
            unsafePath("Path contains a traversal segment: ${targetAbsolute.path}")
        }
        val canonical = try {
            targetAbsolute.canonicalFile
        } catch (error: IOException) {
            unsafePath("Aniyomi path cannot be canonicalized", error)
        }
        if (canonical.path != targetAbsolute.path) {
            unsafePath("Aniyomi path is symbolic or non-canonical: ${targetAbsolute.path}")
        }
        val canonicalRootPrefix = rootAbsolute.path + File.separator
        if (canonical.path != rootAbsolute.path && !canonical.path.startsWith(canonicalRootPrefix)) {
            unsafePath("Canonical path escapes the dedicated Aniyomi root: ${canonical.path}")
        }
        return canonical
    }

    fun requireRegularFile(file: File): File {
        val safe = requireContained(file)
        if (!safe.exists() || !safe.isFile) {
            unsafePath("Expected an existing regular Aniyomi file: ${safe.path}")
        }
        return safe
    }

    fun requireImmutableGenerationFile(file: File): File {
        val safe = requireRegularFile(file)
        if (!safe.hasReadOnlyAniyomiCodePermissions()) {
            immutableGenerationRequired(
                "Aniyomi generation is writable and cannot be used as dynamic code: ${safe.path}",
            )
        }
        return safe
    }

    fun requireDirectory(file: File, create: Boolean): File {
        val absolute = file.absoluteFile
        if (containsTraversalSegment(absolute.path)) {
            unsafePath("Directory path contains a traversal segment: ${absolute.path}")
        }
        val nearestExisting = generateSequence(absolute) { it.parentFile }
            .firstOrNull(File::exists)
            ?: unsafePath("Aniyomi storage has no existing parent")
        val nearestCanonical = try {
            nearestExisting.canonicalFile
        } catch (error: IOException) {
            unsafePath("Aniyomi directory parent cannot be canonicalized", error)
        }
        if (nearestCanonical.path != nearestExisting.absolutePath) {
            unsafePath("Aniyomi directory parent is symbolic or non-canonical")
        }
        if (!absolute.exists() && create && !absolute.mkdirs() && !absolute.isDirectory) {
            storageIo("Could not create Aniyomi directory ${absolute.path}")
        }
        if (!absolute.exists()) {
            unsafePath("Required Aniyomi directory does not exist: ${absolute.path}")
        }
        val canonical = try {
            absolute.canonicalFile
        } catch (error: IOException) {
            unsafePath("Aniyomi directory cannot be canonicalized", error)
        }
        if (canonical.path != absolute.path || !canonical.isDirectory) {
            unsafePath("Aniyomi directory is symbolic, non-canonical, or not a directory: ${absolute.path}")
        }
        if (absolute != configuredRoot.absoluteFile) {
            val rootAbsolute = configuredRoot.absoluteFile
            val prefix = rootAbsolute.path + File.separator
            if (!absolute.path.startsWith(prefix)) {
                unsafePath("Directory escapes the dedicated Aniyomi root: ${absolute.path}")
            }
        }
        return canonical
    }

    fun requireGenerationId(value: String) {
        if (!ANIYOMI_STORAGE_SHA_PATTERN.matches(value)) unsafePath("Invalid Aniyomi generation ID")
    }

    fun requireTransactionId(value: String) {
        if (!ANIYOMI_STORAGE_TRANSACTION_PATTERN.matches(value)) {
            unsafePath("Invalid Aniyomi transaction ID")
        }
    }

    private fun containsTraversalSegment(path: String): Boolean =
        path.split(File.separatorChar).any { it == "." || it == ".." }
}

@Singleton
internal class FileAniyomiLifecycleStateStore @Inject constructor(
    root: AniyomiLifecycleRoot,
) : AniyomiLifecycleStateStore {
    private val paths = AniyomiStoragePaths(root.directory)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    override suspend fun read(): AniyomiLifecycleRecord? = withContext(Dispatchers.IO) {
        settleInterruptedWrite()
        val base = stateFile()
        if (!base.exists()) return@withContext null
        paths.requireRegularFile(base)
        val bytes = readBounded(base)
        try {
            json.decodeFromString<AniyomiLifecycleRecord>(bytes.toString(Charsets.UTF_8))
        } catch (error: IllegalArgumentException) {
            stateCorrupt("Aniyomi lifecycle record violates its invariants", error)
        } catch (error: SerializationException) {
            stateCorrupt("Aniyomi lifecycle record is malformed", error)
        }
    }

    override suspend fun write(record: AniyomiLifecycleRecord?) = withContext(Dispatchers.IO) {
        settleInterruptedWrite()
        val base = stateFile()
        val backup = backupFile()
        val next = nextFile()

        if (record == null) {
            if (!base.exists()) return@withContext
            paths.requireRegularFile(base)
            if (!base.renameTo(backup)) {
                statePersistence("Could not preserve the Aniyomi state before clearing it")
            }
            if (!backup.delete()) {
                if (!backup.renameTo(base)) {
                    statePersistence(
                        "Could not clear or restore the previous Aniyomi lifecycle state",
                    )
                }
                statePersistence(
                    "Could not durably clear the Aniyomi lifecycle state; previous state was restored",
                )
            }
            return@withContext
        }

        val encoded = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        if (encoded.size > ANIYOMI_MAX_STATE_BYTES) {
            statePersistence("Aniyomi lifecycle record exceeds its byte limit")
        }
        if (next.exists()) {
            statePersistence("Stale Aniyomi state staging file survived recovery")
        }
        try {
            FileOutputStream(next).use { output ->
                output.write(encoded)
                output.flush()
                output.fd.sync()
            }
            paths.requireRegularFile(next)
            if (base.exists()) {
                paths.requireRegularFile(base)
                if (!base.renameTo(backup)) {
                    next.delete()
                    statePersistence("Could not preserve the previous Aniyomi lifecycle state")
                }
            }
            if (!next.renameTo(base)) {
                if (base.exists()) base.delete()
                if (backup.exists()) backup.renameTo(base)
                statePersistence("Could not promote the new Aniyomi lifecycle state")
            }
            if (backup.exists() && !backup.delete()) {
                statePersistence("Could not retire the previous Aniyomi lifecycle state")
            }
        } catch (cancelled: CancellationException) {
            rollbackStateWrite(base, backup, next)?.let(cancelled::addSuppressed)
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            rollbackStateWrite(base, backup, next)?.let(error::addSuppressed)
            throw error
        } catch (error: Exception) {
            val rollbackFailure = rollbackStateWrite(base, backup, next)
            val wrapped = statePersistenceException(
                "Could not persist the Aniyomi lifecycle state",
                error,
            )
            rollbackFailure?.let(wrapped::addSuppressed)
            throw wrapped
        }
    }

    private fun settleInterruptedWrite() {
        paths.state()
        val base = stateFile()
        val backup = backupFile()
        val next = nextFile()
        if (backup.exists()) {
            paths.requireRegularFile(backup)
            if (base.exists()) {
                paths.requireRegularFile(base)
                if (!base.delete()) statePersistence("Could not discard interrupted Aniyomi state")
            }
            if (!backup.renameTo(base)) {
                statePersistence("Could not restore the previous Aniyomi lifecycle state")
            }
        }
        if (next.exists()) {
            paths.requireRegularFile(next)
            if (!next.delete()) statePersistence("Could not discard incomplete Aniyomi state")
        }
    }

    private fun rollbackStateWrite(base: File, backup: File, next: File): AniyomiLifecycleException? {
        if (next.exists() && (!next.isFile || !next.delete())) {
            return statePersistenceException("Could not remove incomplete Aniyomi state")
        }
        if (backup.exists()) {
            if (!backup.isFile) {
                return statePersistenceException("Aniyomi state backup is not a regular file")
            }
            if (base.exists() && (!base.isFile || !base.delete())) {
                return statePersistenceException("Could not discard failed Aniyomi state")
            }
            if (!backup.renameTo(base)) {
                return statePersistenceException("Could not restore previous Aniyomi state")
            }
        }
        return null
    }

    private fun readBounded(file: File): ByteArray {
        if (file.length() !in 1..ANIYOMI_MAX_STATE_BYTES.toLong()) {
            stateCorrupt("Aniyomi lifecycle record has an invalid size")
        }
        val bytes = file.readBytes()
        if (bytes.size !in 1..ANIYOMI_MAX_STATE_BYTES) {
            stateCorrupt("Aniyomi lifecycle record exceeds its byte limit")
        }
        return bytes
    }

    private fun stateFile(): File = paths.requireContained(File(paths.state(), ANIYOMI_STATE_FILE_NAME))
    private fun nextFile(): File = paths.requireContained(File(paths.state(), "$ANIYOMI_STATE_FILE_NAME.next"))
    private fun backupFile(): File = paths.requireContained(File(paths.state(), "$ANIYOMI_STATE_FILE_NAME.bak"))
}

@Singleton
internal class FileAniyomiArtifactStore @Inject constructor(
    root: AniyomiLifecycleRoot,
) : AniyomiArtifactStore {
    private val paths = AniyomiStoragePaths(root.directory)

    override suspend fun createTransaction(transactionId: String) = withContext(Dispatchers.IO) {
        paths.requireTransactionId(transactionId)
        val directory = File(paths.transactions(), transactionId)
        if (directory.exists()) unsafePath("Aniyomi transaction already exists")
        paths.transactionDirectory(transactionId, create = true)
        Unit
    }

    override suspend fun copyCapabilityToTransaction(
        source: InspectedAniyomiApk,
        transactionId: String,
        policy: AniyomiTrustedPolicy,
    ): File = withContext(Dispatchers.IO) {
        val sourceFile = requireExternalRegularFile(source.file)
        val candidate = paths.transactionCandidate(transactionId, requireRegular = false)
        if (candidate.exists()) unsafePath("Aniyomi transaction candidate already exists")
        val initialLength = sourceFile.length()
        val initialModified = sourceFile.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(candidate).use { output ->
                    if (!candidate.setReadOnly() || !candidate.hasReadOnlyAniyomiCodePermissions()) {
                        storageIo(
                            "Could not mark the Aniyomi candidate read-only before writing dynamic code",
                        )
                    }
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        if (copied > policy.policy.expectedSizeBytes ||
                            copied > policy.policy.maxArchiveBytes
                        ) {
                            copyMismatch("Aniyomi source exceeds the trusted byte count")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            if (sourceFile.length() != initialLength || sourceFile.lastModified() != initialModified) {
                throw AniyomiLifecycleException(
                    AniyomiLifecycleFailure(
                        AniyomiLifecycleFailureCode.SOURCE_CHANGED_DURING_COPY,
                        "Aniyomi source metadata changed during copy",
                    ),
                )
            }
            val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (copied != policy.policy.expectedSizeBytes || sha256 != policy.policy.expectedSha256) {
                copyMismatch("Copied Aniyomi bytes do not match the trusted size and SHA-256")
            }
            paths.requireRegularFile(candidate)
        } catch (cancelled: CancellationException) {
            deleteKnownLeaf(candidate)
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            deleteKnownLeaf(candidate)
            throw error
        } catch (error: Exception) {
            deleteKnownLeaf(candidate)
            storageIo("Could not copy the Aniyomi artifact into app-private staging", error)
        }
    }

    override suspend fun publishGeneration(
        candidate: AniyomiArtifactIdentity,
    ): PublishedAniyomiGeneration = withContext(Dispatchers.IO) {
        require(candidate.kind == AniyomiStoredArtifactKind.TRANSACTION)
        val source = paths.resolve(candidate, requireRegular = true)
        val generationDirectory = File(paths.generations(), candidate.generationId)
        if (!generationDirectory.exists()) {
            paths.generationDirectory(candidate.generationId, create = true)
        } else {
            paths.generationDirectory(candidate.generationId, create = false)
        }
        val destination = paths.generationApk(candidate.generationId, requireRegular = false)
        if (destination.exists()) {
            paths.requireImmutableGenerationFile(destination)
            return@withContext PublishedAniyomiGeneration(destination, reusedExisting = true)
        }
        val unexpected = generationDirectory.listFiles().orEmpty()
            .filterNot { it.name == ANIYOMI_APK_FILE_NAME }
        if (unexpected.isNotEmpty()) {
            unsafePath("Aniyomi generation directory contains unexpected entries")
        }
        if (!source.hasReadOnlyAniyomiCodePermissions()) {
            immutableGenerationRequired(
                "Aniyomi transaction candidate lost read-only mode before publication",
            )
        }
        if (!source.renameTo(destination)) {
            storageIo("Could not publish the synced Aniyomi candidate as an immutable generation")
        }
        PublishedAniyomiGeneration(
            paths.requireImmutableGenerationFile(destination),
            reusedExisting = false,
        )
    }

    override suspend fun resolve(artifact: AniyomiArtifactIdentity): File = withContext(Dispatchers.IO) {
        val resolved = paths.resolve(artifact, requireRegular = true)
        if (artifact.kind == AniyomiStoredArtifactKind.GENERATION) {
            paths.requireImmutableGenerationFile(resolved)
        } else {
            resolved
        }
    }

    override suspend fun cleanupTransaction(transactionId: String) = withContext(Dispatchers.IO) {
        paths.requireTransactionId(transactionId)
        val directory = File(paths.transactions(), transactionId)
        if (!directory.exists()) return@withContext
        val safeDirectory = paths.transactionDirectory(transactionId, create = false)
        deleteKnownTree(safeDirectory)
    }

    override suspend fun quarantine(artifact: AniyomiArtifactIdentity, reason: String) =
        withContext(Dispatchers.IO) {
            val sourceEntry = when (artifact.kind) {
                AniyomiStoredArtifactKind.TRANSACTION ->
                    File(paths.transactions(), requireNotNull(artifact.transactionId))

                AniyomiStoredArtifactKind.GENERATION ->
                    File(paths.generations(), artifact.generationId)
            }
            quarantineEntryWithoutFollowing(sourceEntry, reason)
        }

    override suspend fun quarantineUnknownEntries(activeTransactionId: String?) =
        withContext(Dispatchers.IO) {
            activeTransactionId?.let(paths::requireTransactionId)
            val knownRootNames = setOf("generations", "transactions", "state", "quarantine")
            paths.root.listFiles().orEmpty()
                .filterNot { it.name in knownRootNames }
                .forEach { quarantineEntryWithoutFollowing(it, "unknown-root-entry") }

            paths.generations().listFiles().orEmpty().forEach { entry ->
                if (!ANIYOMI_STORAGE_SHA_PATTERN.matches(entry.name)) {
                    quarantineEntryWithoutFollowing(entry, "invalid-generation-name")
                    return@forEach
                }
                val absolute = entry.absoluteFile
                val canonical = runCatching { absolute.canonicalFile }.getOrNull()
                if (canonical == null || canonical.path != absolute.path || !absolute.isDirectory) {
                    quarantineEntryWithoutFollowing(entry, "unsafe-generation-entry")
                    return@forEach
                }
                val children = absolute.listFiles()
                    ?: unsafePath("Could not enumerate Aniyomi generation ${entry.name}")
                val apk = children.singleOrNull()
                val apkCanonical = apk?.let { runCatching { it.absoluteFile.canonicalFile }.getOrNull() }
                if (apk == null || apk.name != ANIYOMI_APK_FILE_NAME ||
                    apkCanonical == null || apkCanonical.path != apk.absolutePath ||
                    !apk.hasReadOnlyAniyomiCodePermissions()
                ) {
                    quarantineEntryWithoutFollowing(entry, "unexpected-generation-content")
                }
            }

            paths.transactions().listFiles().orEmpty().forEach { entry ->
                if (entry.name != activeTransactionId) {
                    quarantineEntryWithoutFollowing(entry, "abandoned-transaction")
                }
            }

            val knownStateNames = setOf(
                ANIYOMI_STATE_FILE_NAME,
                "$ANIYOMI_STATE_FILE_NAME.next",
                "$ANIYOMI_STATE_FILE_NAME.bak",
                ANIYOMI_LOAD_STATE_FILE_NAME,
                "$ANIYOMI_LOAD_STATE_FILE_NAME.next",
                "$ANIYOMI_LOAD_STATE_FILE_NAME.bak",
            )
            paths.state().listFiles().orEmpty()
                .filterNot { it.name in knownStateNames }
                .forEach { quarantineEntryWithoutFollowing(it, "unknown-state-entry") }
        }

    private fun requireExternalRegularFile(file: File): File {
        val absolute = file.absoluteFile
        val canonical = try {
            absolute.canonicalFile
        } catch (error: IOException) {
            unsafePath("Aniyomi source cannot be canonicalized", error)
        }
        if (canonical.path != absolute.path || !canonical.exists() || !canonical.isFile) {
            unsafePath("Aniyomi source is symbolic, non-canonical, or not a regular file")
        }
        return canonical
    }

    private fun quarantineEntryWithoutFollowing(source: File, reason: String) {
        if (!source.exists() && source.canonicalFile == source.absoluteFile) return
        val parent = source.absoluteFile.parentFile
            ?: unsafePath("Aniyomi quarantine source has no parent")
        paths.requireContained(parent)
        val safeReason = reason.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(40)
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val destination = File(
            paths.quarantine(),
            "${UUID.randomUUID()}-$safeReason-$safeName",
        )
        paths.requireContained(destination)
        if (!source.renameTo(destination)) {
            storageIo("Could not quarantine unsafe Aniyomi storage entry ${source.name}")
        }
    }

    private fun deleteKnownTree(file: File) {
        val safe = paths.requireContained(file)
        if (safe.isDirectory) {
            val children = safe.listFiles() ?: storageIo("Could not enumerate known Aniyomi transaction")
            children.forEach(::deleteKnownTree)
        } else if (!safe.isFile) {
            unsafePath("Refusing to delete a special Aniyomi filesystem entry")
        }
        if (!safe.delete()) storageIo("Could not delete known Aniyomi transaction entry")
    }

    private fun deleteKnownLeaf(file: File) {
        if (!file.exists()) return
        val safe = paths.requireContained(file)
        if (!safe.isFile) unsafePath("Refusing to delete a non-file Aniyomi candidate")
        if (!safe.delete()) storageIo("Could not remove failed Aniyomi candidate")
    }
}

private fun unsafePath(message: String, cause: Throwable? = null): Nothing =
    throw AniyomiLifecycleException(
        AniyomiLifecycleFailure(
            AniyomiLifecycleFailureCode.UNSAFE_STORAGE_PATH,
            message,
            cause = cause,
        ),
    )

private fun immutableGenerationRequired(message: String): Nothing =
    throw AniyomiLifecycleException(
        AniyomiLifecycleFailure(
            AniyomiLifecycleFailureCode.IMMUTABLE_GENERATION_REQUIRED,
            message,
        ),
    )

private fun storageIo(message: String, cause: Throwable? = null): Nothing =
    throw AniyomiLifecycleException(
        AniyomiLifecycleFailure(
            AniyomiLifecycleFailureCode.STORAGE_IO,
            message,
            cause = cause,
        ),
    )

private fun copyMismatch(message: String): Nothing =
    throw AniyomiLifecycleException(
        AniyomiLifecycleFailure(AniyomiLifecycleFailureCode.COPY_INTEGRITY_MISMATCH, message),
    )

private fun stateCorrupt(message: String, cause: Throwable? = null): Nothing =
    throw AniyomiLifecycleException(
        AniyomiLifecycleFailure(
            AniyomiLifecycleFailureCode.STATE_CORRUPT,
            message,
            cause = cause,
        ),
    )

private fun statePersistenceException(
    message: String,
    cause: Throwable? = null,
): AniyomiLifecycleException = AniyomiLifecycleException(
    AniyomiLifecycleFailure(
        AniyomiLifecycleFailureCode.STATE_PERSISTENCE,
        message,
        cause = cause,
    ),
)

private fun statePersistence(message: String, cause: Throwable? = null): Nothing =
    throw statePersistenceException(message, cause)
