package com.foxtv.app.core.plugin.aniyomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AniyomiExtensionApkInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsPinnedOfficialJellyfinBytesWhenAndroidVerifierReturnsPinnedFacts() {
        val apk = officialResearchFixture()
        val policy = AniyomiApkPolicy.officialJellyfinV14_17(setOf("armeabi-v7a", "armeabi"))
        val result = inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy)

        val accepted = assertAccepted(result)
        assertEquals(policy.expectedSha256, accepted.report.sha256)
        assertEquals(policy.expectedSizeBytes, accepted.report.sizeBytes)
        assertEquals(policy.expectedPackageName, accepted.report.manifest.packageName)
        assertEquals(policy.expectedFactoryClassName, accepted.report.dex.factoryClassName)
        assertEquals(1, accepted.report.dex.dexFiles)
        assertEquals(939, accepted.report.dex.definedClasses)
        assertTrue(accepted.report.archive.nativeAbis.isEmpty())
        assertTrue(accepted.report.signature.cryptographicallyVerified)
        assertFalse(accepted.report.runtimeCompatibilityVerified)
    }

    @Test
    fun rejectsWrongSha256BeforeArchiveParsing() {
        val apk = validSyntheticApk()
        val policy = AniyomiApkTestFixtures.policyFor(apk).copy(expectedSha256 = "0".repeat(64))

        assertRejected(
            inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy),
            AniyomiApkRejectionCode.HASH_MISMATCH,
        )
    }

    @Test
    fun rejectsWrongTrustedSize() {
        val apk = validSyntheticApk()
        val base = AniyomiApkTestFixtures.policyFor(apk)
        val policy = base.copy(expectedSizeBytes = base.expectedSizeBytes + 1)

        assertRejected(
            inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy),
            AniyomiApkRejectionCode.SIZE_MISMATCH,
        )
    }

    @Test
    fun rejectsTruncatedZip() {
        val validBytes = AniyomiApkTestFixtures.buildApkBytes()
        val apk = File(temporaryFolder.root, "truncated.apk").apply {
            writeBytes(validBytes.copyOf(validBytes.size - 12))
        }.canonicalFile
        val policy = AniyomiApkTestFixtures.policyFor(apk)

        assertRejected(
            inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy),
            AniyomiApkRejectionCode.MALFORMED_ARCHIVE,
        )
    }

    @Test
    fun rejectsArchivePathTraversal() {
        val apk = AniyomiApkTestFixtures.writeApk(
            temporaryFolder.root,
            extraEntries = mapOf("../../escape.dex" to byteArrayOf(1, 2, 3)),
        )
        val policy = AniyomiApkTestFixtures.policyFor(apk)

        assertRejected(
            inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy),
            AniyomiApkRejectionCode.UNSAFE_ENTRY_PATH,
        )
    }

    @Test
    fun rejectsMissingManifestEntry() {
        val apk = AniyomiApkTestFixtures.writeApk(
            temporaryFolder.root,
            includeManifest = false,
        )
        val policy = AniyomiApkTestFixtures.policyFor(apk)

        assertRejected(
            inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy),
            AniyomiApkRejectionCode.REQUIRED_ENTRY_MISSING,
        )
    }

    @Test
    fun rejectsManifestThatAndroidCannotParse() {
        val apk = validSyntheticApk()
        val policy = AniyomiApkTestFixtures.policyFor(apk)
        val reader = AniyomiPackageArchiveReader {
            throw AniyomiPackageArchiveReadException(
                AniyomiPackageArchiveReadException.Reason.MANIFEST,
                "manifest parse rejected",
            )
        }

        assertRejected(
            AniyomiExtensionApkInspector(reader).inspect(apk, policy),
            AniyomiApkRejectionCode.MANIFEST_UNREADABLE,
        )
    }

    @Test
    fun rejectsWrongManifestPackage() {
        val apk = validSyntheticApk()
        val policy = AniyomiApkTestFixtures.policyFor(apk)
        val facts = AniyomiApkTestFixtures.validPackageFacts(
            policy,
            packageName = "com.example.untrusted",
        )

        assertRejected(
            inspector(facts).inspect(apk, policy),
            AniyomiApkRejectionCode.PACKAGE_MISMATCH,
        )
    }

    @Test
    fun rejectsWrongManifestEntrypoint() {
        val apk = validSyntheticApk()
        val policy = AniyomiApkTestFixtures.policyFor(apk)
        val facts = AniyomiApkTestFixtures.validPackageFacts(
            policy,
            factoryMetadata = ".DifferentFactory",
        )

        assertRejected(
            inspector(facts).inspect(apk, policy),
            AniyomiApkRejectionCode.ENTRYPOINT_MISMATCH,
        )
    }

    @Test
    fun rejectsFactoryWithoutAnimeSourceFactoryContract() {
        val apk = AniyomiApkTestFixtures.writeApk(
            temporaryFolder.root,
            dex = AniyomiApkTestFixtures.buildDex(implementsFactoryContract = false),
        )
        val policy = AniyomiApkTestFixtures.policyFor(apk)

        assertRejected(
            inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy),
            AniyomiApkRejectionCode.ENTRYPOINT_CONTRACT_MISMATCH,
        )
    }

    @Test
    fun rejectsNativeLibrariesForIncompatibleAbi() {
        val apk = AniyomiApkTestFixtures.writeApk(
            temporaryFolder.root,
            extraEntries = mapOf("lib/arm64-v8a/libjellyfin.so" to byteArrayOf(0x7f, 'E'.code.toByte())),
        )
        val policy = AniyomiApkTestFixtures.policyFor(apk, supportedAbis = setOf("armeabi-v7a"))

        assertRejected(
            inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy),
            AniyomiApkRejectionCode.ABI_INCOMPATIBLE,
        )
    }

    @Test
    fun rejectsCryptographicallyUnverifiedSignature() {
        val apk = validSyntheticApk()
        val policy = AniyomiApkTestFixtures.policyFor(apk)
        val facts = AniyomiApkTestFixtures.validPackageFacts(policy, signatureVerified = false)

        assertRejected(
            inspector(facts).inspect(apk, policy),
            AniyomiApkRejectionCode.SIGNATURE_INVALID,
        )
    }

    @Test
    fun rejectsWrongSignerCertificate() {
        val apk = validSyntheticApk()
        val policy = AniyomiApkTestFixtures.policyFor(apk)
        val facts = AniyomiApkTestFixtures.validPackageFacts(
            policy,
            certificateSha256 = setOf("f".repeat(64)),
        )

        assertRejected(
            inspector(facts).inspect(apk, policy),
            AniyomiApkRejectionCode.CERTIFICATE_MISMATCH,
        )
    }

    @Test
    fun rejectsOrdinaryApkWithoutAniyomiExtensionContract() {
        val apk = validSyntheticApk()
        val policy = AniyomiApkTestFixtures.policyFor(apk)
        val facts = AniyomiApkTestFixtures.validPackageFacts(policy, requiredFeatures = emptySet())

        assertRejected(
            inspector(facts).inspect(apk, policy),
            AniyomiApkRejectionCode.EXTENSION_CONTRACT_MISSING,
        )
    }

    @Test
    fun rejectsDefinitionsFromEveryParentOwnedNamespace() {
        val parentOwnedClasses = listOf(
            "eu.kanade.tachiyomi.animesource.model.SAnimeImpl",
            "eu.kanade.tachiyomi.animesource.model.SAnime\$Companion",
            "androidx.preference.Preference",
            "uy.kohesive.injekt.Injekt",
            "okhttp3.OkHttpClient",
            "okio.Buffer",
            "kotlin.Unit",
            "kotlinx.coroutines.Job",
            "rx.Observable",
        )

        parentOwnedClasses.forEachIndexed { index, className ->
            val apk = AniyomiApkTestFixtures.writeApk(
                temporaryFolder.root,
                name = "parent-owned-$index.apk",
                dex = AniyomiApkTestFixtures.buildDex(additionalDefinedClasses = setOf(className)),
            )
            val policy = AniyomiApkTestFixtures.policyFor(apk)

            val result = inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy)
            assertRejected(result, AniyomiApkRejectionCode.HOST_CLASS_SHADOWING)
        }
    }

    @Test
    fun acceptsDefinitionsUnderExactPinnedJellyfinChildPrefix() {
        val apk = AniyomiApkTestFixtures.writeApk(
            temporaryFolder.root,
            dex = AniyomiApkTestFixtures.buildDex(
                additionalDefinedClasses = setOf(
                    "${AniyomiApkTestFixtures.PACKAGE_NAME}.BundledDependency",
                    "${AniyomiApkTestFixtures.PACKAGE_NAME}.internal.NestedType",
                ),
            ),
        )
        val policy = AniyomiApkTestFixtures.policyFor(apk)

        val accepted = assertAccepted(
            inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy),
        )
        assertEquals(3, accepted.report.dex.definedClasses)
    }

    @Test
    fun rejectsLookalikePackageOutsideExactJellyfinChildBoundary() {
        val apk = AniyomiApkTestFixtures.writeApk(
            temporaryFolder.root,
            dex = AniyomiApkTestFixtures.buildDex(
                additionalDefinedClasses = setOf(
                    "${AniyomiApkTestFixtures.PACKAGE_NAME}_evil.Shadow",
                ),
            ),
        )
        val policy = AniyomiApkTestFixtures.policyFor(apk)

        assertRejected(
            inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy),
            AniyomiApkRejectionCode.HOST_CLASS_SHADOWING,
        )
    }

    @Test
    fun rejectsMalformedDexArtifact() {
        val malformedDex = ByteArray(0x70).apply {
            this[0] = 'd'.code.toByte()
            this[1] = 'e'.code.toByte()
            this[2] = 'x'.code.toByte()
            this[3] = '\n'.code.toByte()
        }
        val apk = AniyomiApkTestFixtures.writeApk(temporaryFolder.root, dex = malformedDex)
        val policy = AniyomiApkTestFixtures.policyFor(apk)

        assertRejected(
            inspector(AniyomiApkTestFixtures.validPackageFacts(policy)).inspect(apk, policy),
            AniyomiApkRejectionCode.DEX_MALFORMED,
        )
    }

    private fun validSyntheticApk(): File = AniyomiApkTestFixtures.writeApk(temporaryFolder.root)

    private fun inspector(facts: AniyomiPackageArchiveFacts): AniyomiExtensionApkInspector =
        AniyomiExtensionApkInspector(AniyomiPackageArchiveReader { facts })

    private fun assertAccepted(result: AniyomiApkInspectionResult): InspectedAniyomiApk {
        assertTrue("Expected acceptance but got $result", result is AniyomiApkInspectionResult.Accepted)
        return (result as AniyomiApkInspectionResult.Accepted).artifact
    }

    private fun assertRejected(
        result: AniyomiApkInspectionResult,
        expectedCode: AniyomiApkRejectionCode,
    ) {
        assertTrue("Expected rejection but got $result", result is AniyomiApkInspectionResult.Rejected)
        val rejected = result as AniyomiApkInspectionResult.Rejected
        assertEquals(listOf(expectedCode), rejected.failures.map { it.code })
    }

    private fun officialResearchFixture(): File {
        val relative =
            ".kiro/research/aniyomi/f8150feba27664976e77cdb9fe021bf80ffab782/" +
                "aniyomi-all.jellyfin-v14.17.apk"
        val workingDirectory = requireNotNull(System.getProperty("user.dir")) {
            "JVM test working directory is unavailable"
        }
        val start = File(workingDirectory).canonicalFile
        val fixture = generateSequence(start) { current -> current.parentFile }
            .map { directory -> File(directory, relative) }
            .firstOrNull(File::isFile)
        assertTrue("Pinned official Aniyomi research fixture is missing", fixture != null)
        return fixture!!.canonicalFile
    }
}
