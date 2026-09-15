package com.foxtv.app.core.plugin.aniyomi

import android.app.Application
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiHostRegistry
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiHostRuntime
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.mockk
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class ControlledAniyomiFactoryBehavior {
    SUCCESS,
    CLASSPATH_ISOLATION,
    CONSTRUCTOR_FAILURE,
    INITIALIZATION_FAILURE,
    CANCELLATION,
    TYPE_MISMATCH,
    PRIVATE_ONLY_CONSTRUCTOR,
    CLASS_MISSING,
}

internal data class ControlledAniyomiArtifact(
    val source: File,
    val policy: AniyomiTrustedPolicy,
    val inspector: AniyomiExtensionApkInspector,
    val capability: InspectedAniyomiApk,
)

internal object AniyomiLoadBoundaryTestFixtures {
    private val compiledFactoryClasses =
        ConcurrentHashMap<ControlledAniyomiFactoryBehavior, Map<String, ByteArray>>()

    fun controlledArtifact(
        directory: File,
        behavior: ControlledAniyomiFactoryBehavior = ControlledAniyomiFactoryBehavior.SUCCESS,
        name: String = "extension.apk",
        policyVersion: Int = 1,
        additionalDexClasses: Set<String> = emptySet(),
        certificateSha256: String = AniyomiApkTestFixtures.CERTIFICATE_SHA256,
    ): ControlledAniyomiArtifact {
        val artifactRoot = File(directory, "artifact-${name.substringBeforeLast('.')}-${behavior.name.lowercase()}")
            .apply { mkdirs() }
        val classEntries = if (behavior == ControlledAniyomiFactoryBehavior.CLASS_MISSING) {
            emptyMap()
        } else {
            compiledFactoryClasses.computeIfAbsent(behavior) { compileFactory(artifactRoot, it) }
        }
        val dex = AniyomiApkTestFixtures.buildDex(
            additionalDefinedClasses = additionalDexClasses,
        )
        val source = AniyomiApkTestFixtures.writeApk(
            directory = artifactRoot,
            name = name,
            dex = dex,
            extraEntries = classEntries,
        )
        val apkPolicy = AniyomiApkTestFixtures.policyFor(source)
        val inspector = AniyomiExtensionApkInspector(
            AniyomiPackageArchiveReader {
                AniyomiApkTestFixtures.validPackageFacts(
                    apkPolicy,
                    certificateSha256 = setOf(certificateSha256),
                )
            },
        )
        val capability = AniyomiLifecycleTestFixtures.accepted(
            inspector.inspect(source, apkPolicy),
        )
        return ControlledAniyomiArtifact(
            source = source,
            policy = AniyomiTrustedPolicy("controlled-jellyfin", policyVersion, apkPolicy),
            inspector = inspector,
            capability = capability,
        )
    }

    fun initializedHostRuntime(): AniyomiHostRuntime {
        val runtime = AniyomiHostRuntime(
            application = mockk<Application>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()) { "FOX.TV/M3-test" },
            registry = TestHostRegistry(),
        )
        runtime.initialize()
        return runtime
    }

    private fun compileFactory(
        root: File,
        behavior: ControlledAniyomiFactoryBehavior,
    ): Map<String, ByteArray> {
        val sourceRoot = File(root, "java-source")
        val classesRoot = File(root, "java-classes").apply { mkdirs() }
        val packageDirectory = File(
            sourceRoot,
            AniyomiApkTestFixtures.PACKAGE_NAME.replace('.', File.separatorChar),
        ).apply { mkdirs() }
        val source = File(packageDirectory, "JellyfinFactory.java")
        source.writeText(javaFactorySource(behavior))
        val diagnostics = ByteArrayOutputStream()
        val compilationClasspath = requireNotNull(
            System.getProperty("foxtv.test.runtimeClasspath"),
        ) { "Gradle did not export the M3 test runtime classpath" }
        val arguments = arrayOf(
            "--release", "11",
            "-classpath", compilationClasspath,
            "-d", classesRoot.path,
            source.path,
        )
        val exitCode = try {
            val providerType = Class.forName("javax.tools.ToolProvider")
            val compiler = requireNotNull(
                providerType.getMethod("getSystemJavaCompiler").invoke(null),
            ) { "M3 controlled load tests require a full JDK compiler" }
            val toolType = Class.forName("javax.tools.Tool")
            val runMethod = toolType.getMethod(
                "run",
                java.io.InputStream::class.java,
                java.io.OutputStream::class.java,
                java.io.OutputStream::class.java,
                Array<String>::class.java,
            )
            (runMethod.invoke(compiler, null, diagnostics, diagnostics, arguments) as Number).toInt()
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException(
                "M3 controlled JDK compiler invocation failed",
                error.targetException,
            )
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException(
                "M3 controlled load tests require the standard JDK compiler modules",
                error,
            )
        }
        check(exitCode == 0) {
            "Controlled Aniyomi factory compilation failed (exit=$exitCode): " +
                diagnostics.toString(Charsets.UTF_8.name())
        }
        return classesRoot.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .associate { file ->
                file.relativeTo(classesRoot).invariantSeparatorsPath to file.readBytes()
            }
            .also { entries ->
                check(entries.isNotEmpty()) {
                    "Controlled Aniyomi factory compilation produced no class files"
                }
            }
    }

    private fun javaFactorySource(behavior: ControlledAniyomiFactoryBehavior): String {
        val declaration = when (behavior) {
            ControlledAniyomiFactoryBehavior.TYPE_MISMATCH -> "public final class JellyfinFactory"
            else -> "public final class JellyfinFactory implements AnimeSourceFactory"
        }
        val staticBlock = if (behavior == ControlledAniyomiFactoryBehavior.INITIALIZATION_FAILURE) {
            "static { if (Boolean.TRUE.booleanValue()) throw new IllegalStateException(\"init failure\"); }"
        } else {
            ""
        }
        val constructor = when (behavior) {
            ControlledAniyomiFactoryBehavior.CLASSPATH_ISOLATION -> """
                public JellyfinFactory() {
                    ClassLoader owned = JellyfinFactory.class.getClassLoader();
                    ClassLoader context = Thread.currentThread().getContextClassLoader();
                    if (context != owned) {
                        throw new IllegalStateException("owned child loader was not bound as TCCL");
                    }
                    try {
                        Class.forName("com.foxtv.app.FoxTvApplication");
                        throw new IllegalStateException("ambient FOX.TV classpath leaked to child");
                    } catch (ClassNotFoundException expected) {
                    }
                    try {
                        context.loadClass("com.foxtv.app.FoxTvApplication");
                        throw new IllegalStateException("ambient FOX.TV classpath leaked through TCCL");
                    } catch (ClassNotFoundException expected) {
                    }
                }
            """.trimIndent()

            ControlledAniyomiFactoryBehavior.CONSTRUCTOR_FAILURE ->
                "public JellyfinFactory() { throw new IllegalStateException(\"constructor failure\"); }"

            ControlledAniyomiFactoryBehavior.CANCELLATION ->
                "public JellyfinFactory() { AniyomiLoadTestProbe.blockUntilInterrupted(); }"

            ControlledAniyomiFactoryBehavior.PRIVATE_ONLY_CONSTRUCTOR ->
                "private JellyfinFactory(String ignored) { }"

            else -> "public JellyfinFactory() { }"
        }
        val factoryMethod = if (behavior == ControlledAniyomiFactoryBehavior.TYPE_MISMATCH) {
            ""
        } else {
            "public List<AnimeSource> createSources() { return Collections.emptyList(); }"
        }
        return """
            package ${AniyomiApkTestFixtures.PACKAGE_NAME};

            import eu.kanade.tachiyomi.animesource.AnimeSource;
            import eu.kanade.tachiyomi.animesource.AnimeSourceFactory;
            import eu.kanade.tachiyomi.animesource.AniyomiLoadTestProbe;
            import java.util.Collections;
            import java.util.List;

            $declaration {
                $staticBlock
                $constructor
                $factoryMethod
            }
        """.trimIndent()
    }

    private class TestHostRegistry : AniyomiHostRegistry {
        private var application: Application? = null
        private var networkHelper: NetworkHelper? = null

        override fun hasApplication(): Boolean = application != null
        override fun getApplication(): Application = requireNotNull(application)
        override fun addApplication(application: Application) {
            this.application = application
        }

        override fun hasNetworkHelper(): Boolean = networkHelper != null
        override fun getNetworkHelper(): NetworkHelper = requireNotNull(networkHelper)
        override fun addNetworkHelper(networkHelper: NetworkHelper) {
            this.networkHelper = networkHelper
        }
    }
}

/** Real JVM archive loader with the same exact parent/child ownership algorithm as production. */
internal class JvmAniyomiClassLoaderFactory : AniyomiClassLoaderFactory {
    val openCount = AtomicInteger(0)
    val handles = CopyOnWriteArrayList<JvmAniyomiClassLoaderHandle>()
    val openedSources = CopyOnWriteArrayList<File>()

    override fun open(
        sourceApk: File,
        legacyOptimizedDirectory: File,
        ownership: AniyomiClassOwnershipPolicy,
    ): AniyomiOwnedClassLoaderHandle {
        check(sourceApk.isFile)
        check(sourceApk.hasReadOnlyAniyomiCodePermissions())
        check(legacyOptimizedDirectory.isDirectory)
        openCount.incrementAndGet()
        openedSources += sourceApk.canonicalFile
        val loader = StrictJvmAniyomiArchiveClassLoader(
            sourceApk = sourceApk,
            parent = FilteringJvmAniyomiParentClassLoader(
                delegate = requireNotNull(javaClass.classLoader),
                ownership = ownership,
            ),
            ownership = ownership,
        )
        return JvmAniyomiClassLoaderHandle(loader).also(handles::add)
    }
}

internal class JvmAniyomiClassLoaderHandle(
    private val delegate: StrictJvmAniyomiArchiveClassLoader,
) : AniyomiOwnedClassLoaderHandle {
    private val released = AtomicBoolean(false)

    override val classLoader: ClassLoader
        get() = delegate

    val isReleased: Boolean
        get() = released.get()

    override fun loadChildClass(binaryName: String): Class<*> {
        if (released.get()) throw ClassNotFoundException("Controlled Aniyomi loader was released")
        return delegate.loadOwnedChild(binaryName)
    }

    override fun close() {
        if (released.compareAndSet(false, true)) delegate.close()
    }
}

internal class StrictJvmAniyomiArchiveClassLoader(
    sourceApk: File,
    parent: ClassLoader,
    private val ownership: AniyomiClassOwnershipPolicy,
) : URLClassLoader(arrayOf(sourceApk.toURI().toURL()), parent) {
    private val released = AtomicBoolean(false)

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(this) {
            if (released.get()) throw ClassNotFoundException("Controlled Aniyomi loader was released")
            return when (ownership.ownerOf(name)) {
                AniyomiClassOwner.PARENT -> parent.loadClass(name)
                AniyomiClassOwner.CHILD -> {
                    val loaded = findLoadedClass(name) ?: findClass(name)
                    if (resolve) resolveClass(loaded)
                    loaded
                }

                null -> throw ClassNotFoundException("Class is outside controlled Aniyomi ownership: $name")
            }
        }
    }

    fun loadOwnedChild(name: String): Class<*> {
        if (ownership.ownerOf(name) != AniyomiClassOwner.CHILD) {
            throw ClassNotFoundException("Controlled entrypoint is not child-owned: $name")
        }
        return loadClass(name, false)
    }

    override fun close() {
        released.set(true)
        super.close()
    }
}

private class FilteringJvmAniyomiParentClassLoader(
    private val delegate: ClassLoader,
    private val ownership: AniyomiClassOwnershipPolicy,
) : ClassLoader(null) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (ownership.ownerOf(name) != AniyomiClassOwner.PARENT) {
            throw ClassNotFoundException("Class is not parent-owned by controlled Aniyomi loader: $name")
        }
        return delegate.loadClass(name)
    }
}
