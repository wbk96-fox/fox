package com.foxtv.app.core.plugin.aniyomi

import android.content.Context
import dalvik.system.DexClassLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only production site allowed to create an Aniyomi DexClassLoader. Callers cannot supply a
 * path: [AniyomiLoadBoundary] obtains both files from M2 and the generation-owned cache store.
 */
@Singleton
internal class AndroidAniyomiClassLoaderFactory @Inject constructor(
    @ApplicationContext context: Context,
) : AniyomiClassLoaderFactory {
    private val applicationClassLoader = requireNotNull(context.applicationContext.classLoader) {
        "Application classloader is unavailable"
    }

    override fun open(
        sourceApk: File,
        legacyOptimizedDirectory: File,
        ownership: AniyomiClassOwnershipPolicy,
    ): AniyomiOwnedClassLoaderHandle {
        val source = requireCanonicalReadOnlyCodeFile(sourceApk)
        val legacyOptimized = requireCanonicalDirectory(legacyOptimizedDirectory)
        val filteringParent = FilteringAniyomiParentClassLoader(
            delegate = applicationClassLoader,
            ownership = ownership,
        )
        val loader = StrictAniyomiDexClassLoader(
            dexPath = source.path,
            legacyOptimizedDirectory = legacyOptimized.path,
            parent = filteringParent,
            ownership = ownership,
        )
        return AndroidAniyomiClassLoaderHandle(loader)
    }

    private fun requireCanonicalReadOnlyCodeFile(file: File): File {
        val absolute = file.absoluteFile
        val canonical = absolute.canonicalFile
        if (canonical.path != absolute.path || !canonical.isFile) {
            throw AniyomiLoadBoundaryException(
                AniyomiLoadBoundaryFailure(
                    AniyomiLoadBoundaryFailureCode.UNSAFE_CODE_SOURCE,
                    "Aniyomi code source is symbolic, non-canonical, or not a regular file",
                ),
            )
        }
        if (!canonical.hasReadOnlyAniyomiCodePermissions()) {
            throw AniyomiLoadBoundaryException(
                AniyomiLoadBoundaryFailure(
                    AniyomiLoadBoundaryFailureCode.WRITABLE_CODE_SOURCE,
                    "Aniyomi code source must be read-only immediately before DexClassLoader",
                ),
            )
        }
        return canonical
    }

    private fun requireCanonicalDirectory(directory: File): File {
        val absolute = directory.absoluteFile
        val canonical = absolute.canonicalFile
        if (canonical.path != absolute.path || !canonical.isDirectory) {
            throw AniyomiLoadBoundaryException(
                AniyomiLoadBoundaryFailure(
                    AniyomiLoadBoundaryFailureCode.CACHE_IO,
                    "Aniyomi legacy optimized-output directory is unsafe",
                ),
            )
        }
        return canonical
    }
}

private class FilteringAniyomiParentClassLoader(
    private val delegate: ClassLoader,
    private val ownership: AniyomiClassOwnershipPolicy,
) : ClassLoader(null) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (ownership.ownerOf(name) != AniyomiClassOwner.PARENT) {
            throw ClassNotFoundException("Class is not owned by the Aniyomi parent boundary: $name")
        }
        return delegate.loadClass(name)
    }
}

private class StrictAniyomiDexClassLoader(
    dexPath: String,
    legacyOptimizedDirectory: String,
    parent: ClassLoader,
    private val ownership: AniyomiClassOwnershipPolicy,
) : DexClassLoader(
    dexPath,
    legacyOptimizedDirectory,
    null,
    parent,
) {
    private val closed = AtomicBoolean(false)

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(this) {
            if (closed.get()) {
                throw ClassNotFoundException("Aniyomi child loader has been released")
            }
            return when (ownership.ownerOf(name)) {
                AniyomiClassOwner.PARENT -> parent.loadClass(name)
                AniyomiClassOwner.CHILD -> {
                    val loaded = findLoadedClass(name) ?: findClass(name)
                    if (resolve) resolveClass(loaded)
                    loaded
                }

                null -> throw ClassNotFoundException(
                    "Class is outside the Aniyomi parent/child ownership boundary: $name",
                )
            }
        }
    }

    fun loadOwnedChild(name: String): Class<*> {
        if (ownership.ownerOf(name) != AniyomiClassOwner.CHILD) {
            throw ClassNotFoundException("Requested entrypoint is not child-owned: $name")
        }
        return loadClass(name, false)
    }

    fun closeOwnedResources() {
        closed.set(true)
    }
}

private class AndroidAniyomiClassLoaderHandle(
    private val delegate: StrictAniyomiDexClassLoader,
) : AniyomiOwnedClassLoaderHandle {
    override val classLoader: ClassLoader
        get() = delegate

    override fun loadChildClass(binaryName: String): Class<*> =
        delegate.loadOwnedChild(binaryName)

    override fun close() {
        delegate.closeOwnedResources()
    }
}
