package com.foxtv.app.core.plugin.aniyomi

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val ANIYOMI_MAX_LOAD_STATE_BYTES = 64 * 1024
private const val ANIYOMI_CODE_CACHE_ROOT_NAME = "aniyomi_extensions"
private val ANIYOMI_CODE_CACHE_GENERATION_PATTERN = Regex("^[0-9a-f]{64}$")

@Singleton
internal class FileAniyomiLoadBoundaryStateStore @Inject constructor(
    root: AniyomiLifecycleRoot,
) : AniyomiLoadBoundaryStateStore {
    private val paths = AniyomiStoragePaths(root.directory)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    override suspend fun read(): AniyomiLoadBoundaryRecord? = withContext(Dispatchers.IO) {
        settleInterruptedWrite()
        val base = stateFile()
        if (!base.exists()) return@withContext null
        paths.requireRegularFile(base)
        val bytes = readBounded(base)
        try {
            json.decodeFromString<AniyomiLoadBoundaryRecord>(bytes.toString(Charsets.UTF_8))
        } catch (error: IllegalArgumentException) {
            loadStateCorrupt("Aniyomi load-boundary record violates its invariants", error)
        } catch (error: SerializationException) {
            loadStateCorrupt("Aniyomi load-boundary record is malformed", error)
        }
    }

    override suspend fun write(record: AniyomiLoadBoundaryRecord) = withContext(Dispatchers.IO) {
        settleInterruptedWrite()
        val base = stateFile()
        val backup = backupFile()
        val next = nextFile()
        val encoded = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        if (encoded.size !in 1..ANIYOMI_MAX_LOAD_STATE_BYTES) {
            loadStatePersistence("Aniyomi load-boundary record exceeds its byte limit")
        }
        if (next.exists()) {
            loadStatePersistence("Stale Aniyomi load-state staging file survived recovery")
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
                    loadStatePersistence("Could not preserve previous Aniyomi load state")
                }
            }
            if (!next.renameTo(base)) {
                if (base.exists()) base.delete()
                if (backup.exists()) backup.renameTo(base)
                loadStatePersistence("Could not promote new Aniyomi load state")
            }
            if (backup.exists() && !backup.delete()) {
                loadStatePersistence("Could not retire previous Aniyomi load-state backup")
            }
        } catch (cancelled: CancellationException) {
            rollbackWrite(base, backup, next)?.let(cancelled::addSuppressed)
            throw cancelled
        } catch (error: AniyomiLoadBoundaryException) {
            rollbackWrite(base, backup, next)?.let(error::addSuppressed)
            throw error
        } catch (error: Exception) {
            val wrapped = AniyomiLoadBoundaryException(
                AniyomiLoadBoundaryFailure(
                    AniyomiLoadBoundaryFailureCode.STATE_PERSISTENCE,
                    "Could not persist Aniyomi load-boundary state",
                    error,
                ),
            )
            rollbackWrite(base, backup, next)?.let(wrapped::addSuppressed)
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
                if (!base.delete()) loadStatePersistence("Could not discard interrupted load state")
            }
            if (!backup.renameTo(base)) {
                loadStatePersistence("Could not restore previous Aniyomi load state")
            }
        }
        if (next.exists()) {
            paths.requireRegularFile(next)
            if (!next.delete()) loadStatePersistence("Could not discard incomplete load state")
        }
    }

    private fun rollbackWrite(base: File, backup: File, next: File): Exception? {
        return try {
            if (next.exists() && (!next.isFile || !next.delete())) {
                return AniyomiLoadBoundaryException(
                    AniyomiLoadBoundaryFailure(
                        AniyomiLoadBoundaryFailureCode.STATE_PERSISTENCE,
                        "Could not remove incomplete Aniyomi load state",
                    ),
                )
            }
            if (backup.exists()) {
                if (!backup.isFile) {
                    return AniyomiLoadBoundaryException(
                        AniyomiLoadBoundaryFailure(
                            AniyomiLoadBoundaryFailureCode.STATE_PERSISTENCE,
                            "Aniyomi load-state backup is not a regular file",
                        ),
                    )
                }
                if (base.exists() && (!base.isFile || !base.delete())) {
                    return AniyomiLoadBoundaryException(
                        AniyomiLoadBoundaryFailure(
                            AniyomiLoadBoundaryFailureCode.STATE_PERSISTENCE,
                            "Could not discard failed Aniyomi load state",
                        ),
                    )
                }
                if (!backup.renameTo(base)) {
                    return AniyomiLoadBoundaryException(
                        AniyomiLoadBoundaryFailure(
                            AniyomiLoadBoundaryFailureCode.STATE_PERSISTENCE,
                            "Could not restore previous Aniyomi load state",
                        ),
                    )
                }
            }
            null
        } catch (error: Exception) {
            error
        }
    }

    private fun readBounded(file: File): ByteArray {
        if (file.length() !in 1..ANIYOMI_MAX_LOAD_STATE_BYTES.toLong()) {
            loadStateCorrupt("Aniyomi load-boundary record has an invalid size")
        }
        val bytes = file.readBytes()
        if (bytes.size !in 1..ANIYOMI_MAX_LOAD_STATE_BYTES) {
            loadStateCorrupt("Aniyomi load-boundary record exceeds its byte limit")
        }
        return bytes
    }

    private fun stateFile(): File =
        paths.requireContained(File(paths.state(), ANIYOMI_LOAD_STATE_FILE_NAME))

    private fun nextFile(): File =
        paths.requireContained(File(paths.state(), "$ANIYOMI_LOAD_STATE_FILE_NAME.next"))

    private fun backupFile(): File =
        paths.requireContained(File(paths.state(), "$ANIYOMI_LOAD_STATE_FILE_NAME.bak"))
}

@Singleton
internal class AniyomiCodeCacheRoot private constructor(
    internal val directory: File,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.codeCacheDir, ANIYOMI_CODE_CACHE_ROOT_NAME),
    )

    companion object {
        fun forTests(directory: File): AniyomiCodeCacheRoot = AniyomiCodeCacheRoot(directory)
    }
}

@Singleton
internal class FileAniyomiCodeCacheStore @Inject constructor(
    root: AniyomiCodeCacheRoot,
) : AniyomiCodeCacheStore {
    // DexClassLoader writes here only on API 24–25. On API 26+ this remains a lifecycle-owned
    // workspace/marker; ART cache data is platform-managed and is not claimed or deleted here.
    private val configuredRoot = root.directory

    override suspend fun prepare(generationId: String): File = withContext(Dispatchers.IO) {
        requireGenerationId(generationId)
        val root = requireRoot(create = true)
        val generation = requireContained(File(root, generationId))
        if (generation.exists()) {
            requireOrdinaryDirectory(generation)
            deleteTree(generation)
        }
        if (!generation.mkdir() || !generation.isDirectory) {
            loadCacheFailure("Could not create generation-owned Aniyomi code cache")
        }
        requireOrdinaryDirectory(generation)
    }

    override suspend fun cleanup(generationId: String) = withContext(Dispatchers.IO) {
        requireGenerationId(generationId)
        val root = requireRoot(create = true)
        val generation = requireContained(File(root, generationId))
        if (!generation.exists()) return@withContext
        requireOrdinaryDirectory(generation)
        deleteTree(generation)
    }

    override suspend fun recover() = withContext(Dispatchers.IO) {
        val root = requireRoot(create = true)
        val entries = root.listFiles()
            ?: loadCacheFailure("Could not enumerate Aniyomi code cache")
        entries.forEach { entry ->
            val absolute = entry.absoluteFile
            requireContained(absolute)
            val canonical = runCatching { absolute.canonicalFile }.getOrNull()
            if (canonical == null || canonical.path != absolute.path) {
                if (!absolute.delete()) {
                    loadCacheFailure("Could not remove symbolic Aniyomi code-cache entry")
                }
                return@forEach
            }
            if (!ANIYOMI_CODE_CACHE_GENERATION_PATTERN.matches(entry.name)) {
                loadCacheFailure("Unknown entry exists in the dedicated Aniyomi code cache")
            }
            requireOrdinaryDirectory(absolute)
            deleteTree(absolute)
        }
    }

    private fun requireRoot(create: Boolean): File {
        val absolute = configuredRoot.absoluteFile
        val nearestExisting = generateSequence(absolute) { it.parentFile }
            .firstOrNull(File::exists)
            ?: loadCacheFailure("Aniyomi code cache has no existing parent")
        val nearestCanonical = try {
            nearestExisting.canonicalFile
        } catch (error: IOException) {
            loadCacheFailure("Aniyomi code-cache parent cannot be canonicalized", error)
        }
        if (nearestCanonical.path != nearestExisting.absolutePath) {
            loadCacheFailure("Aniyomi code-cache parent is symbolic or non-canonical")
        }
        if (!absolute.exists() && create && !absolute.mkdirs() && !absolute.isDirectory) {
            loadCacheFailure("Could not create dedicated Aniyomi code-cache root")
        }
        if (!absolute.exists() || !absolute.isDirectory) {
            loadCacheFailure("Dedicated Aniyomi code-cache root is unavailable")
        }
        val canonical = try {
            absolute.canonicalFile
        } catch (error: IOException) {
            loadCacheFailure("Aniyomi code-cache root cannot be canonicalized", error)
        }
        if (canonical.path != absolute.path) {
            loadCacheFailure("Aniyomi code-cache root is symbolic or non-canonical")
        }
        return canonical
    }

    private fun requireContained(file: File): File {
        val root = requireRoot(create = true)
        val absolute = file.absoluteFile
        val prefix = root.path + File.separator
        if (absolute.path != root.path && !absolute.path.startsWith(prefix)) {
            loadCacheFailure("Path escapes the dedicated Aniyomi code cache")
        }
        if (absolute.path.split(File.separatorChar).any { it == "." || it == ".." }) {
            loadCacheFailure("Aniyomi code-cache path contains traversal")
        }
        val canonical = try {
            absolute.canonicalFile
        } catch (error: IOException) {
            loadCacheFailure("Aniyomi code-cache path cannot be canonicalized", error)
        }
        if (canonical.path != absolute.path ||
            canonical.path != root.path && !canonical.path.startsWith(prefix)
        ) {
            loadCacheFailure("Aniyomi code-cache path is symbolic or escapes containment")
        }
        return canonical
    }

    private fun requireOrdinaryDirectory(directory: File): File {
        val safe = requireContained(directory)
        if (!safe.isDirectory) {
            loadCacheFailure("Expected an ordinary Aniyomi code-cache directory")
        }
        return safe
    }

    private fun deleteTree(entry: File) {
        val safe = requireContained(entry)
        if (safe.isDirectory) {
            val children = safe.listFiles()
                ?: loadCacheFailure("Could not enumerate generation-owned code cache")
            children.forEach { child ->
                val absolute = child.absoluteFile
                val canonical = runCatching { absolute.canonicalFile }.getOrNull()
                if (canonical == null || canonical.path != absolute.path) {
                    if (!absolute.delete()) {
                        loadCacheFailure("Could not remove symbolic code-cache child")
                    }
                } else {
                    deleteTree(absolute)
                }
            }
        } else if (!safe.isFile) {
            loadCacheFailure("Refusing to remove a special Aniyomi code-cache entry")
        }
        if (!safe.delete()) {
            loadCacheFailure("Could not remove generation-owned Aniyomi code cache")
        }
    }

    private fun requireGenerationId(value: String) {
        if (!ANIYOMI_CODE_CACHE_GENERATION_PATTERN.matches(value)) {
            loadCacheFailure("Invalid Aniyomi code-cache generation ID")
        }
    }
}

private fun loadStateCorrupt(message: String, cause: Throwable? = null): Nothing =
    throw AniyomiLoadBoundaryException(
        AniyomiLoadBoundaryFailure(
            AniyomiLoadBoundaryFailureCode.STATE_CORRUPT,
            message,
            cause,
        ),
    )

private fun loadStatePersistence(message: String, cause: Throwable? = null): Nothing =
    throw AniyomiLoadBoundaryException(
        AniyomiLoadBoundaryFailure(
            AniyomiLoadBoundaryFailureCode.STATE_PERSISTENCE,
            message,
            cause,
        ),
    )

private fun loadCacheFailure(message: String, cause: Throwable? = null): Nothing =
    throw AniyomiLoadBoundaryException(
        AniyomiLoadBoundaryFailure(
            AniyomiLoadBoundaryFailureCode.CACHE_IO,
            message,
            cause,
        ),
    )
