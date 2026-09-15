package com.foxtv.app.core.plugin.aniyomi

import java.io.File
import java.security.MessageDigest

internal object AniyomiLifecycleTestFixtures {
    data class RealFixture(
        val source: File,
        val policy: AniyomiTrustedPolicy,
        val inspector: AniyomiExtensionApkInspector,
        val capability: InspectedAniyomiApk,
    )

    fun realFixture(directory: File): RealFixture {
        val sourceDirectory = File(directory, "source").apply { mkdirs() }
        val source = AniyomiApkTestFixtures.writeApk(sourceDirectory)
        val apkPolicy = AniyomiApkTestFixtures.policyFor(source)
        val inspector = AniyomiExtensionApkInspector(
            AniyomiPackageArchiveReader { AniyomiApkTestFixtures.validPackageFacts(apkPolicy) },
        )
        val capability = accepted(inspector.inspect(source, apkPolicy))
        return RealFixture(
            source = source,
            policy = AniyomiTrustedPolicy("test-jellyfin", 1, apkPolicy),
            inspector = inspector,
            capability = capability,
        )
    }

    fun fakeInspector(): AniyomiStaticApkInspector = AniyomiStaticApkInspector { file, _ ->
        if (!file.isFile) {
            AniyomiApkInspectionResult.Rejected(
                listOf(
                    AniyomiApkInspectionFailure(
                        AniyomiApkInspectionStage.FILE,
                        AniyomiApkRejectionCode.FILE_NOT_REGULAR,
                        "missing test artifact",
                    ),
                ),
            )
        } else {
            AniyomiApkInspectionResult.Accepted(fakeCapability(file))
        }
    }

    fun fakeCapability(file: File): InspectedAniyomiApk {
        val canonical = file.canonicalFile
        val sha = sha256(canonical.readBytes())
        return InspectedAniyomiApk.create(
            canonical,
            AniyomiApkInspectionReport(
                canonicalPath = canonical.path,
                sizeBytes = canonical.length(),
                sha256 = sha,
                archive = AniyomiApkArchiveReport(
                    entryCount = 3,
                    compressedBytes = canonical.length(),
                    uncompressedBytes = canonical.length(),
                    dexEntries = listOf("classes.dex"),
                    nativeAbis = emptySet(),
                ),
                manifest = AniyomiApkManifestReport(
                    packageName = AniyomiApkTestFixtures.PACKAGE_NAME,
                    versionCode = 17,
                    versionName = "14.17",
                    minSdk = 21,
                    targetSdk = 32,
                    factoryClassName = AniyomiApkTestFixtures.FACTORY_CLASS,
                    requiredFeatures = setOf(AniyomiApkPolicy.ANIYOMI_EXTENSION_FEATURE),
                    permissions = emptySet(),
                ),
                dex = AniyomiApkDexReport(
                    dexFiles = 1,
                    definedClasses = 1,
                    methodReferences = 1,
                    factoryClassName = AniyomiApkTestFixtures.FACTORY_CLASS,
                    factoryInterfaces = setOf("eu.kanade.tachiyomi.animesource.AnimeSourceFactory"),
                ),
                signature = AniyomiApkSignatureReport(
                    certificateSha256 = setOf(AniyomiApkTestFixtures.CERTIFICATE_SHA256),
                    cryptographicallyVerified = true,
                ),
                runtimeCompatibilityVerified = false,
            ),
        )
    }

    fun trustedPolicyFor(
        capability: InspectedAniyomiApk,
        id: String = "test-jellyfin",
        version: Int = 1,
    ): AniyomiTrustedPolicy {
        val report = capability.report
        return AniyomiTrustedPolicy(
            id = id,
            version = version,
            policy = AniyomiApkPolicy(
                expectedSizeBytes = report.sizeBytes,
                expectedSha256 = report.sha256,
                expectedPackageName = report.manifest.packageName,
                expectedVersionCode = report.manifest.versionCode,
                expectedVersionName = report.manifest.versionName,
                expectedMinSdk = report.manifest.minSdk,
                expectedTargetSdk = report.manifest.targetSdk,
                expectedFactoryClassName = report.manifest.factoryClassName,
                expectedCertificateSha256 = report.signature.certificateSha256.single(),
                supportedDeviceAbis = setOf("armeabi-v7a"),
            ),
        )
    }

    fun identity(
        file: File,
        policy: AniyomiTrustedPolicy,
        kind: AniyomiStoredArtifactKind = AniyomiStoredArtifactKind.GENERATION,
        transactionId: String? = null,
    ): AniyomiArtifactIdentity = AniyomiArtifactIdentity.fromInspection(
        fakeCapability(file),
        kind,
        transactionId,
        policy,
    )

    fun policyRegistry(
        current: AniyomiTrustedPolicy,
        additional: List<AniyomiTrustedPolicy> = emptyList(),
    ): AniyomiTrustedPolicyProvider {
        val policies = (listOf(current) + additional).associateBy { policy ->
            Triple(policy.id, policy.version, policy.policy.expectedSha256)
        }
        return object : AniyomiTrustedPolicyProvider {
            override fun current(): AniyomiTrustedPolicy = current

            override fun find(
                policyId: String,
                policyVersion: Int,
                generationId: String,
            ): AniyomiTrustedPolicy? = policies[Triple(policyId, policyVersion, generationId)]
        }
    }

    fun accepted(result: AniyomiApkInspectionResult): InspectedAniyomiApk =
        (result as? AniyomiApkInspectionResult.Accepted)?.artifact
            ?: error("Expected accepted Aniyomi test artifact, got $result")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
