import java.io.File
import java.security.MessageDigest
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.GradleException
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.w3c.dom.Element

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.sentry.android.gradle) apply false
    alias(libs.plugins.chaquopy) apply false
}

val expectedAndroidApi = "36"
val expectedAndroidPlatformRevision = "2"
val expectedAndroidJarSha256 =
    "d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a"
// `:ffmpeg-decoder-downmix` is included conditionally by settings.gradle.kts, so the allowlist is
// derived from the projects that actually exist in this invocation. In default builds the FFmpeg
// path is therefore not allowlisted at all instead of being permanently pre-approved.
val androidApisProjectAllowlist = buildSet {
    add(":app")
    add(":baselineprofile")
    if (rootProject.findProject(":ffmpeg-decoder-downmix") != null) {
        add(":ffmpeg-decoder-downmix")
    }
}

// settings.gradle.kts owns the only boolean parser for the FFmpeg flags and publishes the
// normalized decisions. Reading them back with an explicit contract keeps a future lifecycle or
// composite-build change from surfacing as an opaque cast failure.
fun requiredBuildFlag(name: String): Boolean {
    val extras = gradle.extensions.extraProperties
    check(extras.has(name)) {
        "Build flag '$name' was not published by settings.gradle.kts"
    }
    val value = extras.get(name)
    return value as? Boolean
        ?: throw GradleException(
            "Build flag '$name' must be a Boolean, found ${value?.javaClass?.name}: $value"
        )
}

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun androidSdkRoot(): File {
    val localProperties = Properties().apply {
        val source = rootProject.file("local.properties")
        if (source.isFile) source.inputStream().use(::load)
    }
    val configured = localProperties.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: throw GradleException(
            "Cannot validate androidApis: sdk.dir, ANDROID_HOME and ANDROID_SDK_ROOT are absent"
        )
    val sdkRoot = File(configured)
    check(sdkRoot.isAbsolute) { "Android SDK path must be absolute: $configured" }
    return sdkRoot.canonicalFile
}

fun validatePinnedAndroidSdkJar(actualJar: File, consumer: String): File {
    val expectedJar = androidSdkRoot().resolve(
        "platforms/android-$expectedAndroidApi/android.jar"
    ).canonicalFile
    val canonicalActualJar = actualJar.canonicalFile
    check(canonicalActualJar == expectedJar) {
        "$consumer points to $canonicalActualJar; expected $expectedJar"
    }
    check(expectedJar.isFile) { "Pinned Android SDK JAR is missing: $expectedJar" }

    val sourcePropertiesFile = expectedJar.parentFile.resolve("source.properties")
    check(sourcePropertiesFile.isFile) {
        "Pinned Android SDK metadata is missing: $sourcePropertiesFile"
    }
    val sourceProperties = Properties().apply {
        sourcePropertiesFile.inputStream().use(::load)
    }
    check(sourceProperties.getProperty("AndroidVersion.ApiLevel") == expectedAndroidApi) {
        "Unexpected Android SDK API in $sourcePropertiesFile: " +
            sourceProperties.getProperty("AndroidVersion.ApiLevel")
    }
    check(sourceProperties.getProperty("Pkg.Revision") == expectedAndroidPlatformRevision) {
        "Unexpected Android SDK platform revision in $sourcePropertiesFile: " +
            sourceProperties.getProperty("Pkg.Revision")
    }
    val actualHash = expectedJar.sha256()
    check(actualHash == expectedAndroidJarSha256) {
        "Unexpected Android SDK android.jar SHA-256 for $expectedJar: " +
            "$actualHash; expected $expectedAndroidJarSha256"
    }
    return expectedJar
}

// A committed lock state is mandatory for every resolvable project configuration.
// Settings/plugin classpaths and task-created tooling are protected separately by strict
// verification metadata; the expected detached-tooling selection is audited below.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode = LockMode.STRICT
    }

    // AGP creates `androidApis` lazily and populates it with one local SDK android.jar.
    // That file cannot have a module lock state, so the exception is bound to its exact
    // project, canonical path, SDK metadata and bytes. Every other dependency shape fails.
    configurations.configureEach {
        if (name == "androidApis") {
            val localAndroidApis = this
            resolutionStrategy.deactivateDependencyLocking()
            incoming.beforeResolve {
                check(project.path in androidApisProjectAllowlist) {
                    "Dependency locking may be disabled for androidApis only in " +
                        "$androidApisProjectAllowlist, not ${project.path}"
                }
                check(localAndroidApis.extendsFrom.isEmpty()) {
                    "${project.path}:androidApis must not extend other configurations: " +
                        localAndroidApis.extendsFrom.joinToString { it.name }
                }
                check(localAndroidApis.allDependencyConstraints.isEmpty()) {
                    "${project.path}:androidApis must not contain dependency constraints"
                }

                val declaredDependencies = localAndroidApis.dependencies.toList()
                check(declaredDependencies.size == 1) {
                    "${project.path}:androidApis must contain exactly one local Android SDK " +
                        "dependency, found ${declaredDependencies.size}: " +
                        declaredDependencies.joinToString { it.javaClass.name }
                }
                val sdkDependency = declaredDependencies.single()
                check(sdkDependency is FileCollectionDependency) {
                    "${project.path}:androidApis accepts only one FileCollectionDependency; " +
                        "found ${sdkDependency.javaClass.name}"
                }

                val dependencyFiles = sdkDependency.files.files.map(File::getCanonicalFile)
                check(dependencyFiles.size == 1) {
                    "${project.path}:androidApis must resolve exactly one local file, found " +
                        dependencyFiles.joinToString()
                }
                validatePinnedAndroidSdkJar(
                    dependencyFiles.single(),
                    "${project.path}:androidApis"
                )
            }
        }
    }
}

fun parseDetachedToolingManifest(manifest: File): LinkedHashMap<String, Set<String>> {
    check(manifest.isFile) { "Detached tooling manifest is missing: $manifest" }
    val entries = linkedMapOf<String, Set<String>>()
    manifest.readLines().forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        val columns = rawLine.split('\t')
        check(columns.size == 2) {
            "Malformed detached tooling manifest line ${index + 1}: expected two TSV columns"
        }
        val coordinate = columns[0].trim()
        val coordinateParts = coordinate.split(':')
        check(coordinateParts.size == 3 && coordinateParts.all(String::isNotBlank)) {
            "Malformed detached tooling coordinate on line ${index + 1}: $coordinate"
        }
        val version = coordinateParts[2]
        check(!version.contains('+') && !version.equals("latest", ignoreCase = true) &&
            !version.endsWith("-SNAPSHOT", ignoreCase = true)) {
            "Detached tooling coordinate must use an immutable version: $coordinate"
        }
        val artifacts = columns[1].split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        check(artifacts.isNotEmpty()) {
            "Detached tooling component has no artifacts on line ${index + 1}: $coordinate"
        }
        check(entries.put(coordinate, artifacts) == null) {
            "Duplicate detached tooling coordinate: $coordinate"
        }
    }
    check(entries.size == 15) {
        "Detached tooling manifest must contain exactly 15 components, found ${entries.size}"
    }
    val artifactCount = entries.values.sumOf(Set<String>::size)
    check(artifactCount == 28) {
        "Detached tooling manifest must contain exactly 28 artifacts, found $artifactCount"
    }
    return entries
}

val detachedToolingManifestFile = rootProject.file("gradle/detached-tooling-manifest.tsv")
val detachedToolingManifest = parseDetachedToolingManifest(detachedToolingManifestFile)
val detachedToolingAudit = configurations.create("detachedToolingAudit") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    description =
        "Manifest-pinned audit view of AGP, KSP and lint artifacts resolved outside lockfiles"
    // Exact versions are supplied by the reviewed 15/28 manifest. This configuration mirrors
    // un-lockable task-created selections and must not create a misleading root lock state.
    resolutionStrategy.deactivateDependencyLocking()
    resolutionStrategy.failOnDynamicVersions()
    resolutionStrategy.failOnChangingVersions()
}

detachedToolingManifest.keys.forEach { coordinate ->
    val notation = if (coordinate.startsWith("com.android.tools.build:aapt2:")) {
        "$coordinate:linux@jar"
    } else {
        coordinate
    }
    dependencies.add(detachedToolingAudit.name, notation)
}

fun verifiedMetadataArtifactsByCoordinate(metadataFile: File): Map<String, Map<String, List<String>>> {
    check(metadataFile.isFile) { "Dependency verification metadata is missing: $metadataFile" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(metadataFile)
    val result = linkedMapOf<String, Map<String, List<String>>>()
    val componentNodes = document.getElementsByTagName("component")
    for (componentIndex in 0 until componentNodes.length) {
        val component = componentNodes.item(componentIndex) as Element
        val coordinate = listOf("group", "name", "version")
            .joinToString(":") { attribute -> component.getAttribute(attribute) }
        val artifacts = linkedMapOf<String, List<String>>()
        val children = component.childNodes
        for (childIndex in 0 until children.length) {
            val artifact = children.item(childIndex)
            if (artifact !is Element || artifact.tagName != "artifact") continue
            val shaNodes = artifact.getElementsByTagName("sha256")
            val hashes = (0 until shaNodes.length).map { shaIndex ->
                (shaNodes.item(shaIndex) as Element).getAttribute("value")
            }
            check(artifacts.put(artifact.getAttribute("name"), hashes) == null) {
                "Duplicate verification metadata artifact in $coordinate: " +
                    artifact.getAttribute("name")
            }
        }
        check(result.put(coordinate, artifacts) == null) {
            "Duplicate component in dependency verification metadata: $coordinate"
        }
    }
    return result
}

val verifyDependencySupplyChain = tasks.register("verifyDependencySupplyChain") {
    group = "verification"
    description =
        "Fail-hard resolves locked configurations and validates manifest-pinned detached tooling."
    inputs.file(rootProject.file("gradle/verification-metadata.xml"))
    inputs.file(detachedToolingManifestFile)
    inputs.files(
        rootProject.file("settings-gradle.lockfile"),
        rootProject.file("app/gradle.lockfile"),
        rootProject.file("baselineprofile/gradle.lockfile")
    )

    doLast {
        val expectedProjectPaths = mutableSetOf(":", ":app", ":baselineprofile")
        val ffmpegProject = rootProject.findProject(":ffmpeg-decoder-downmix")
        if (ffmpegProject != null) expectedProjectPaths += ":ffmpeg-decoder-downmix"
        val actualProjectPaths = rootProject.allprojects.map { it.path }.toSet()
        check(actualProjectPaths == expectedProjectPaths) {
            "Unexpected included-project set. Expected $expectedProjectPaths, found $actualProjectPaths"
        }
        val ffmpegProjectRequested = requiredBuildFlag("foxtvIncludeFfmpegDecoderProject")
        check(ffmpegProjectRequested == (ffmpegProject != null)) {
            "FFmpeg module inclusion drift: settings requested $ffmpegProjectRequested but " +
                ":ffmpeg-decoder-downmix is ${if (ffmpegProject != null) "" else "not "}included"
        }

        val requiredLockFiles = buildList {
            add(rootProject.file("settings-gradle.lockfile"))
            add(rootProject.file("app/gradle.lockfile"))
            add(rootProject.file("baselineprofile/gradle.lockfile"))
            if (ffmpegProject != null) {
                add(rootProject.file("ffmpeg-decoder-downmix/gradle.lockfile"))
            }
        }
        requiredLockFiles.forEach { lockFile ->
            check(lockFile.isFile) { "Required strict dependency lock is missing: $lockFile" }
        }

        val metadataFile = rootProject.file("gradle/verification-metadata.xml")
        val metadata = verifiedMetadataArtifactsByCoordinate(metadataFile)
        detachedToolingManifest.forEach { (coordinate, expectedArtifacts) ->
            val actualArtifacts = metadata[coordinate]
                ?: throw GradleException(
                    "Detached tooling component is absent from verification metadata: $coordinate"
                )
            check(actualArtifacts.keys == expectedArtifacts) {
                "Detached tooling artifact drift for $coordinate. Expected $expectedArtifacts, " +
                    "found ${actualArtifacts.keys}"
            }
            actualArtifacts.forEach { (artifact, hashes) ->
                check(hashes.size == 1 && hashes.single().matches(Regex("[0-9a-f]{64}"))) {
                    "Detached tooling artifact must have exactly one SHA-256: " +
                        "$coordinate / $artifact; found $hashes"
                }
            }
        }

        val agpVersion = libs.versions.agp.get()
        val kspVersion = libs.versions.ksp.get()
        check(agpVersion == "8.13.2") {
            "AGP changed from the reviewed detached-tooling anchor 8.13.2 to $agpVersion; " +
                "regenerate and review the 15/28 manifest"
        }
        check(kspVersion == "2.3.12") {
            "KSP changed from the reviewed detached-tooling anchor 2.3.12 to $kspVersion; " +
                "regenerate and review the 15/28 manifest"
        }
        check(
            "com.android.tools.build:aapt2:8.13.2-14304508" in detachedToolingManifest &&
                "com.google.devtools.ksp:symbol-processing-aa-embeddable:2.3.12" in
                detachedToolingManifest
        ) {
            "Direct AAPT2/KSP detached-tooling anchors drifted"
        }
        val expectedLintCoordinates = setOf(
            "com.android.tools.lint:lint:31.13.2",
            "com.android.tools.lint:lint-api:31.13.2",
            "com.android.tools.lint:lint-checks:31.13.2",
            "com.android.tools.lint:lint-gradle:31.13.2"
        )
        check(detachedToolingManifest.keys.containsAll(expectedLintCoordinates)) {
            "AGP lint detached-tooling anchors drifted: expected $expectedLintCoordinates"
        }

        val configurationsToResolve = rootProject.allprojects
            .sortedBy { it.path }
            .flatMap { candidateProject ->
                candidateProject.configurations
                    .filter { configuration -> configuration.isCanBeResolved }
                    .sortedBy { configuration -> configuration.name }
                    .map { configuration -> candidateProject to configuration }
            }
        check(configurationsToResolve.isNotEmpty()) {
            "No resolvable Gradle configurations were found"
        }
        val actualAndroidApisProjects = configurationsToResolve
            .filter { (_, configuration) -> configuration.name == "androidApis" }
            .map { (candidateProject, _) -> candidateProject.path }
            .toSet()
        check(actualAndroidApisProjects.all { projectPath ->
            projectPath in androidApisProjectAllowlist
        }) {
            "androidApis appeared in a project outside the allowlist: $actualAndroidApisProjects"
        }
        val pinnedAndroidJar = androidSdkRoot().resolve(
            "platforms/android-$expectedAndroidApi/android.jar"
        )
        validatePinnedAndroidSdkJar(pinnedAndroidJar, "verifyDependencySupplyChain")

        val uniqueExternalArtifacts = linkedSetOf<String>()
        configurationsToResolve.forEach { (candidateProject, configuration) ->
            val configurationPath = "${candidateProject.path}:${configuration.name}"
                .replace("::", ":")
            val resolutionResult = configuration.incoming.resolutionResult
            val unresolved = resolutionResult.allDependencies
                .filterIsInstance<UnresolvedDependencyResult>()
            check(unresolved.isEmpty()) {
                val failures = unresolved.joinToString("\n") { dependency ->
                    "  ${dependency.attempted.displayName}: ${dependency.failure.message}"
                }
                "Unresolved dependencies in $configurationPath:\n$failures"
            }

            // Artifact views are non-lenient by default. Filtering to external modules avoids
            // mistaking project outputs and reviewed local file dependencies for Maven bytes.
            val artifactCollection = configuration.incoming.artifactView {
                componentFilter { identifier -> identifier is ModuleComponentIdentifier }
            }.artifacts
            check(artifactCollection.failures.isEmpty()) {
                "Artifact materialization failed in $configurationPath:\n" +
                    artifactCollection.failures.joinToString("\n") { failure ->
                        "  ${failure.message}"
                    }
            }
            artifactCollection.artifacts.forEach { artifact ->
                val file = artifact.file
                check(file.exists()) {
                    "Resolved external artifact does not exist in $configurationPath: $file"
                }
                uniqueExternalArtifacts +=
                    "${artifact.id.componentIdentifier.displayName}|${file.canonicalPath}"
            }
        }

        val selectedDetachedComponents = detachedToolingAudit.incoming.resolutionResult
            .allComponents
            .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
            .map { identifier -> "${identifier.group}:${identifier.module}:${identifier.version}" }
            .toSet()
        check(selectedDetachedComponents == detachedToolingManifest.keys) {
            "Detached tooling selection drift. Expected ${detachedToolingManifest.keys}, " +
                "resolved $selectedDetachedComponents"
        }

        logger.lifecycle(
            "Dependency supply chain verified: {} projects, {} resolvable configurations, " +
                "{} currently materialized androidApis configurations plus 1 pinned SDK input, " +
                "and {} unique external artifact files materialized; detached tooling " +
                "15/15 components and 28/28 metadata artifacts SHA-256 verified.",
            actualProjectPaths.size,
            configurationsToResolve.size,
            actualAndroidApisProjects.size,
            uniqueExternalArtifacts.size
        )
    }
}
