package com.foxtv.app.core.plugin.aniyomi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AniyomiPackagingContractTest {
    @Test
    fun r8RulesPreserveEveryParentOwnedDynamicAbiNamespace() {
        val rules = projectFile("app/proguard-rules.pro").readText()
        listOf(
            "-keep class eu.kanade.tachiyomi.** { *; }",
            "-keep class androidx.preference.** { *; }",
            "-keep class uy.kohesive.injekt.** { *; }",
            "-keep class rx.** { *; }",
            "-keep class kotlinx.serialization.** { *; }",
            "-keep class kotlin.** { *; }",
            "-keep class kotlinx.coroutines.** { *; }",
            "-keep class okhttp3.** { *; }",
            "-keep class okio.** { *; }",
        ).forEach { rule -> assertTrue("Missing R8 ownership rule: $rule", rules.contains(rule)) }
    }

    @Test
    fun completeApacheAndInjektLicenseTextsAndPinnedNoticeAreDistributed() {
        val apache = projectFile("app/src/main/assets/licenses/aniyomi-host-APACHE-2.0.txt").readText()
        val mit = projectFile("app/src/main/assets/licenses/injekt-MIT.txt").readText()
        val notice = projectFile("app/src/main/assets/licenses/aniyomi-host-NOTICE.txt").readText()

        assertTrue(apache.trimStart().startsWith("Apache License"))
        assertTrue(apache.contains("Version 2.0, January 2004"))
        assertTrue(apache.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(apache.length > 10_000)
        assertTrue(mit.contains("Copyright (c) 2015 Jayson Minard"))
        assertTrue(mit.contains("Permission is hereby granted, free of charge"))
        assertTrue(mit.contains("THE SOFTWARE IS PROVIDED \"AS IS\""))
        assertTrue(notice.contains("192928ce066f4e4e3e213a47842c88b19d42c514"))
        assertTrue(notice.contains("f33cc241dee8f7561b97f0aa0921c58f0f8a3b8c"))
        assertTrue(notice.contains("65b04400cf379aa952dce00443f57b81f9a936ea"))
    }

    @Test
    fun throwingExtensionsLibArtifactIsNotPackagedAsRuntimeImplementation() {
        val buildScript = projectFile("app/build.gradle.kts").readLines()
        val activeDependencyLines = buildScript
            .map(String::trim)
            .filterNot { it.startsWith("//") }
            .filter { it.startsWith("implementation") || it.startsWith("api") || it.startsWith("runtimeOnly") }

        assertFalse(activeDependencyLines.any { "extensions-lib" in it })
    }

    @Test
    fun aniyomiDexLoadingIsConfinedToDedicatedClassLoaderFactory() {
        val productionSources = aniyomiProductionSources()
        val loaderFile = projectFile(
            "app/src/main/java/com/foxtv/app/core/plugin/aniyomi/AniyomiClassLoader.kt",
        )
        val boundaryFile = projectFile(
            "app/src/main/java/com/foxtv/app/core/plugin/aniyomi/AniyomiLoadBoundary.kt",
        )
        val loaderSource = productionSources.getValue(loaderFile)
        val boundarySource = productionSources.getValue(boundaryFile)
        val dexReferences = productionSources.filterValues { source ->
            Regex(
                """import\s+dalvik\.system\.DexClassLoader|:\s*DexClassLoader\s*\(|dalvik\.system\.DexClassLoader\s*\(""",
            ).containsMatchIn(source)
        }
        assertTrue(
            "DexClassLoader references escaped the dedicated Aniyomi loader file: ${dexReferences.keys}",
            dexReferences.keys == setOf(loaderFile),
        )
        assertTrue(
            "Aniyomi strict DexClassLoader declaration or null native-library path changed",
            Regex(
                """private\s+class\s+StrictAniyomiDexClassLoader\([\s\S]*?\)\s*:\s*DexClassLoader\(\s*dexPath,\s*legacyOptimizedDirectory,\s*null,\s*parent,\s*\)""",
            ).containsMatchIn(loaderSource),
        )
        assertFalse(
            "The API 26+ ART cache must not be represented as an owned optimizedDirectory",
            Regex("""\boptimizedDirectory\b""").containsMatchIn(loaderSource),
        )
        assertTrue(
            "Aniyomi DexClassLoader must be constructed exactly once by its dedicated factory",
            Regex("""val\s+loader\s*=\s*StrictAniyomiDexClassLoader\s*\(""")
                .findAll(loaderSource).count() == 1,
        )
        val directLoadClassFiles = productionSources.filterValues { source ->
            Regex("\\.loadClass\\s*\\(").containsMatchIn(source)
        }
        assertTrue(
            "Direct class loading escaped the dedicated Aniyomi loader file: ${directLoadClassFiles.keys}",
            directLoadClassFiles.keys == setOf(loaderFile),
        )
        val loaderOpenFiles = productionSources.filterValues { source ->
            Regex("\\bclassLoaders\\.open\\s*\\(").containsMatchIn(source)
        }
        assertTrue(
            "Aniyomi loader opening escaped the load boundary: ${loaderOpenFiles.keys}",
            loaderOpenFiles.keys == setOf(boundaryFile) &&
                Regex("\\bclassLoaders\\.open\\s*\\(").findAll(boundarySource).count() == 1,
        )
        assertTrue(boundarySource.contains("val source = resolveExactSource(selected)"))
        assertTrue(boundarySource.contains("legacyDexCache.prepare(selected.identity.generationId)"))
        assertTrue(loaderSource.contains("val source = requireCanonicalReadOnlyCodeFile(sourceApk)"))
        assertTrue(loaderSource.contains("AniyomiLoadBoundaryFailureCode.WRITABLE_CODE_SOURCE"))
        assertTrue(loaderSource.contains("!canonical.hasReadOnlyAniyomiCodePermissions()"))
    }

    @Test
    fun dynamicCodeIsMadeReadOnlyBeforeFirstWriteAndRecheckedBeforeLoading() {
        val storageSource = projectFile(
            "app/src/main/java/com/foxtv/app/core/plugin/aniyomi/AniyomiLifecycleStorage.kt",
        ).readText()
        val loaderSource = projectFile(
            "app/src/main/java/com/foxtv/app/core/plugin/aniyomi/AniyomiClassLoader.kt",
        ).readText()
        val outputOpen = storageSource.indexOf("FileOutputStream(candidate).use { output ->")
        val readOnlyBeforeWrite = storageSource.indexOf("candidate.setReadOnly()", outputOpen)
        val firstDynamicCodeWrite = storageSource.indexOf("output.write(buffer, 0, count)", outputOpen)

        assertTrue("Candidate output must be opened before its mode is frozen", outputOpen >= 0)
        assertTrue(
            "Dynamic code must become read-only before its first byte is written",
            readOnlyBeforeWrite > outputOpen && firstDynamicCodeWrite > readOnlyBeforeWrite,
        )
        assertTrue(storageSource.contains("paths.requireImmutableGenerationFile(destination)"))
        assertTrue(
            Regex(
                """override\s+fun\s+open\([\s\S]*?val\s+source\s*=\s*requireCanonicalReadOnlyCodeFile\(sourceApk\)[\s\S]*?StrictAniyomiDexClassLoader\([\s\S]*?dexPath\s*=\s*source\.path""",
            ).containsMatchIn(loaderSource),
        )
    }

    @Test
    fun aniyomiBoundaryRejectsUncontrolledRuntimeApisAndCrossRuntimeImports() {
        val productionSources = aniyomiProductionSources()
        val boundaryFile = projectFile(
            "app/src/main/java/com/foxtv/app/core/plugin/aniyomi/AniyomiLoadBoundary.kt",
        )
        val forbiddenPatterns = listOf(
            Regex("\\bDexFile\\s*[.(]"),
            Regex("\\b(?:BaseDexClassLoader|InMemoryDexClassLoader|PathClassLoader)\\b"),
            Regex("\\bClass\\.forName\\s*\\("),
            Regex("\\bgetDeclaredConstructor\\s*\\("),
            Regex("\\bsetAccessible\\s*\\("),
            Regex("\\bPackageInstaller\\b"),
            Regex("\\bcreatePackageContext\\s*\\("),
            Regex("\\bACTION_INSTALL_PACKAGE\\b"),
            Regex("\\bProcessBuilder\\s*\\("),
            Regex("Runtime\\.getRuntime\\(\\)\\.exec"),
            Regex("import\\s+.*(?:cloudstream|fanfilm)", RegexOption.IGNORE_CASE),
        )
        productionSources.forEach { (file, source) ->
            forbiddenPatterns.forEach { forbidden ->
                assertFalse(
                    "Aniyomi boundary uses uncontrolled runtime API ${forbidden.pattern} in ${file.path}",
                    forbidden.containsMatchIn(source),
                )
            }
        }

        val constructorReflectionFiles = productionSources.filterValues { source ->
            source.contains(".getConstructor()") || source.contains(".newInstance()")
        }
        assertTrue(
            "Factory construction reflection escaped the controlled load boundary",
            constructorReflectionFiles.keys == setOf(boundaryFile),
        )
        val boundarySource = productionSources.getValue(boundaryFile)
        assertTrue(Regex("\\.getConstructor\\(\\)").findAll(boundarySource).count() == 1)
        assertTrue(Regex("\\.newInstance\\(\\)").findAll(boundarySource).count() == 1)
        assertTrue(
            "Factory construction must bind and restore the strict child TCCL",
            Regex(
                """val\s+previousContextLoader\s*=\s*thread\.contextClassLoader[\s\S]*?return\s+try\s*\{\s*thread\.contextClassLoader\s*=\s*handle\.classLoader\s*constructFactoryWithOwnedContext\(handle,\s*ownership\)\s*\}\s*finally\s*\{\s*thread\.contextClassLoader\s*=\s*previousContextLoader\s*\}""",
            ).containsMatchIn(boundarySource),
        )
        val reflectionImports = productionSources.flatMap { (file, source) ->
            source.lineSequence()
                .filter { it.startsWith("import java.lang.reflect") }
                .map { file to it }
                .toList()
        }
        assertTrue(
            "Unexpected reflection API entered the Aniyomi production boundary: $reflectionImports",
            reflectionImports.map(Pair<File, String>::second).toSet() == setOf(
                "import java.lang.reflect.InvocationTargetException",
                "import java.lang.reflect.Modifier",
            ) && reflectionImports.all { it.first == boundaryFile },
        )
    }

    @Test
    fun selectedGenerationCapabilityAndActivationExistOnlyInCoordinatorHandoff() {
        val productionSources = aniyomiProductionSources()
        val coordinatorFile = projectFile(
            "app/src/main/java/com/foxtv/app/core/plugin/aniyomi/AniyomiLifecycleCoordinator.kt",
        )
        val loadBoundaryFile = projectFile(
            "app/src/main/java/com/foxtv/app/core/plugin/aniyomi/AniyomiLoadBoundary.kt",
        )
        val capabilitySites = productionSources.filterValues { source ->
            Regex("\\bAniyomiSelectedGeneration\\s*\\(").containsMatchIn(source)
        }
        val activationSites = productionSources.filterValues { source ->
            Regex("\\bboundary\\.activate\\s*\\(").containsMatchIn(source)
        }
        assertTrue(
            "Selected-generation capability construction escaped the M2 coordinator",
            capabilitySites.keys == setOf(coordinatorFile) &&
                Regex("\\bAniyomiSelectedGeneration\\s*\\(")
                    .findAll(productionSources.getValue(coordinatorFile)).count() == 1,
        )
        assertTrue(
            "Load-boundary activation escaped the M2 coordinator",
            activationSites.keys == setOf(coordinatorFile) &&
                Regex("\\bboundary\\.activate\\s*\\(")
                    .findAll(productionSources.getValue(coordinatorFile)).count() == 1,
        )
        val coordinatorSource = productionSources.getValue(coordinatorFile)
        assertTrue(
            "Selected activation must retain the M2 mutex through exact capability handoff",
            Regex(
                """internal\s+suspend\s+fun\s+activateSelected\([\s\S]*?=\s*mutex\.withLock\s*\{\s*val\s+selectedIdentity\s*=\s*recoverSelectedIdentityForLoadLocked\(\)\s*activateIdentityLocked\(selectedIdentity,\s*boundary\)\s*\}""",
            ).containsMatchIn(coordinatorSource),
        )
        assertTrue(
            "Fresh inspection and capability issuance must remain inside the private exact handoff",
            Regex(
                """private\s+suspend\s+fun\s+selectedGenerationLocked\([\s\S]*?val\s+inspected\s*=\s*inspectStored\(selectedIdentity\)[\s\S]*?val\s+policy\s*=\s*trustedPolicyFor\(inspected\.identity\)[\s\S]*?return\s+AniyomiSelectedGeneration\(""",
            ).containsMatchIn(coordinatorSource),
        )
        assertTrue(
            "Rollback must acquire M2 first and retain M3 authorization through swap and activation",
            Regex(
                """internal\s+suspend\s+fun\s+rollbackFailedActivationAndActivate\([\s\S]*?=\s*mutex\.withLock\s*\{[\s\S]*?val\s+request\s*=\s*AniyomiActivationRollbackRequest\([\s\S]*?boundary\.consumeRollbackAndActivate\(request\)\s*\{[\s\S]*?performRollback\([\s\S]*?selectionChangeAlreadyAuthorized\s*=\s*true[\s\S]*?selectedGenerationLocked\(rolledBack\.identity\)\s*\}""",
            ).containsMatchIn(coordinatorSource),
        )
        assertFalse(
            "A pre-M2 rollbackRequest read would reintroduce stale M3 authorization",
            productionSources.values.any { it.contains("rollbackRequest(") },
        )
        assertTrue(
            productionSources.getValue(loadBoundaryFile)
                .contains("lifecycle.activateSelected(boundary)"),
        )
    }

    private fun aniyomiProductionSources(): Map<File, String> {
        val aniyomiRoot = projectPath("app/src/main/java/com/foxtv/app/core/plugin/aniyomi")
        return (
            aniyomiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
                projectFile("app/src/main/java/com/foxtv/app/FoxTvApplication.kt")
            ).associateWith(File::readText)
    }

    private fun projectPath(relative: String): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull(File::exists)
            ?.canonicalFile
            ?: error("Project path is missing: $relative")
    }

    private fun projectFile(relative: String): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull(File::isFile)
            ?.canonicalFile
            ?: error("Project file is missing: $relative")
    }
}
