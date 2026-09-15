package com.foxtv.app.core.plugin.aniyomi

import java.io.File

/** Immutable trust policy for one Aniyomi extension artifact. */
data class AniyomiApkPolicy(
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val expectedPackageName: String,
    val expectedVersionCode: Long,
    val expectedVersionName: String,
    val expectedMinSdk: Int,
    val expectedTargetSdk: Int,
    val expectedFactoryClassName: String,
    val expectedCertificateSha256: String,
    val supportedDeviceAbis: Set<String>,
    val requiredExtensionFeature: String = ANIYOMI_EXTENSION_FEATURE,
    val factoryMetadataKey: String = ANIYOMI_FACTORY_METADATA_KEY,
    val nsfwMetadataKey: String = ANIYOMI_NSFW_METADATA_KEY,
    val expectedNsfwValue: String = "0",
    val allowedPermissions: Set<String> = emptySet(),
    val maxArchiveEntries: Int = 512,
    val maxArchiveBytes: Long = 32L * 1024 * 1024,
    val maxEntryBytes: Long = 16L * 1024 * 1024,
    val maxCompressionRatio: Long = 250,
    val allowedDexCount: IntRange = 1..8,
    val maxDefinedClasses: Int = 50_000,
    val maxMethodReferencesPerDex: Int = 65_536,
) {
    init {
        require(expectedSizeBytes in 1..maxArchiveBytes) { "Expected APK size is outside the archive budget" }
        require(SHA256_PATTERN.matches(expectedSha256)) { "Expected APK SHA-256 must be lowercase hexadecimal" }
        require(PACKAGE_PATTERN.matches(expectedPackageName)) { "Expected APK package name is invalid" }
        require(expectedVersionCode >= 0L) { "Expected APK version code is negative" }
        require(expectedVersionName.isNotBlank()) { "Expected APK version name is blank" }
        require(expectedMinSdk > 0 && expectedTargetSdk >= expectedMinSdk) { "Expected SDK range is invalid" }
        require(CLASS_PATTERN.matches(expectedFactoryClassName)) { "Expected factory class name is invalid" }
        require(expectedFactoryClassName.startsWith("$expectedPackageName.")) {
            "Expected factory must belong to the extension package"
        }
        require(SHA256_PATTERN.matches(expectedCertificateSha256)) {
            "Expected certificate SHA-256 must be lowercase hexadecimal"
        }
        require(supportedDeviceAbis.isNotEmpty()) { "At least one device ABI is required" }
        require(supportedDeviceAbis.all(ABI_PATTERN::matches)) { "Device ABI list contains an invalid value" }
        require(requiredExtensionFeature.isNotBlank()) { "Required extension feature is blank" }
        require(factoryMetadataKey.isNotBlank() && nsfwMetadataKey.isNotBlank()) {
            "Required metadata key is blank"
        }
        require(maxArchiveEntries > 0) { "Archive entry limit must be positive" }
        require(maxArchiveBytes > 0L && maxEntryBytes in 1..maxArchiveBytes) {
            "Archive byte limits are invalid"
        }
        require(maxCompressionRatio > 0L) { "Compression ratio limit must be positive" }
        require(!allowedDexCount.isEmpty() && allowedDexCount.first >= 1) { "DEX count range is invalid" }
        require(maxDefinedClasses > 0) { "Defined class limit must be positive" }
        require(maxMethodReferencesPerDex in 1..65_536) { "Method reference limit is invalid" }
    }

    companion object {
        const val ANIYOMI_EXTENSION_FEATURE = "tachiyomi.animeextension"
        const val ANIYOMI_FACTORY_METADATA_KEY = "tachiyomi.animeextension.class"
        const val ANIYOMI_NSFW_METADATA_KEY = "tachiyomi.animeextension.nsfw"

        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private val PACKAGE_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        private val CLASS_PATTERN = PACKAGE_PATTERN
        private val ABI_PATTERN = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$")

        /** Verified policy for the immutable official Jellyfin 14.17 research artifact. */
        fun officialJellyfinV14_17(supportedDeviceAbis: Set<String>): AniyomiApkPolicy =
            AniyomiApkPolicy(
                expectedSizeBytes = 418_755L,
                expectedSha256 = "54e76891daf987e53f671fd86a4ce2a409666e38b5b81b30dee2973f7520085e",
                expectedPackageName = "eu.kanade.tachiyomi.animeextension.all.jellyfin",
                expectedVersionCode = 17L,
                expectedVersionName = "14.17",
                expectedMinSdk = 21,
                expectedTargetSdk = 32,
                expectedFactoryClassName =
                    "eu.kanade.tachiyomi.animeextension.all.jellyfin.JellyfinFactory",
                expectedCertificateSha256 =
                    "50ab1d1e3a20d204d0ad6d334c7691c632e41b98dfa132bf385695fdfa63839c",
                supportedDeviceAbis = supportedDeviceAbis,
                allowedDexCount = 1..1,
            )
    }
}

enum class AniyomiApkInspectionStage {
    FILE,
    HASH,
    ARCHIVE,
    SIGNATURE,
    MANIFEST,
    DEX,
    ABI,
    POLICY,
}

enum class AniyomiApkRejectionCode {
    FILE_NOT_REGULAR,
    FILE_PATH_NOT_CANONICAL,
    MALFORMED_ARTIFACT,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    MALFORMED_ARCHIVE,
    UNSUPPORTED_ZIP_LAYOUT,
    ARCHIVE_LIMIT_EXCEEDED,
    UNSAFE_ENTRY_PATH,
    DUPLICATE_ENTRY,
    ENCRYPTED_ENTRY,
    UNSUPPORTED_COMPRESSION,
    ARCHIVE_INTEGRITY_MISMATCH,
    REQUIRED_ENTRY_MISSING,
    MANIFEST_UNREADABLE,
    SIGNATURE_INVALID,
    SIGNER_COUNT_MISMATCH,
    CERTIFICATE_MISMATCH,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    SDK_MISMATCH,
    EXTENSION_CONTRACT_MISSING,
    ENTRYPOINT_MISMATCH,
    NSFW_METADATA_MISMATCH,
    PERMISSION_NOT_ALLOWED,
    COMPONENT_NOT_ALLOWED,
    DEX_MALFORMED,
    DEX_LAYOUT_INVALID,
    DEX_LIMIT_EXCEEDED,
    ENTRYPOINT_CLASS_MISSING,
    ENTRYPOINT_CONTRACT_MISMATCH,
    HOST_CLASS_SHADOWING,
    NATIVE_LIBRARY_PATH_INVALID,
    ABI_INCOMPATIBLE,
    ARTIFACT_CHANGED_DURING_INSPECTION,
}

data class AniyomiApkInspectionFailure(
    val stage: AniyomiApkInspectionStage,
    val code: AniyomiApkRejectionCode,
    val message: String,
    val subject: String? = null,
)

sealed interface AniyomiApkInspectionResult {
    data class Accepted(val artifact: InspectedAniyomiApk) : AniyomiApkInspectionResult

    data class Rejected(val failures: List<AniyomiApkInspectionFailure>) : AniyomiApkInspectionResult {
        init {
            require(failures.isNotEmpty()) { "A rejected inspection must contain a failure" }
        }
    }
}

data class AniyomiApkArchiveReport(
    val entryCount: Int,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val dexEntries: List<String>,
    val nativeAbis: Set<String>,
)

data class AniyomiApkManifestReport(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val factoryClassName: String,
    val requiredFeatures: Set<String>,
    val permissions: Set<String>,
)

data class AniyomiApkDexReport(
    val dexFiles: Int,
    val definedClasses: Int,
    val methodReferences: Int,
    val factoryClassName: String,
    val factoryInterfaces: Set<String>,
)

data class AniyomiApkSignatureReport(
    val certificateSha256: Set<String>,
    val cryptographicallyVerified: Boolean,
)

data class AniyomiApkInspectionReport(
    val canonicalPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val archive: AniyomiApkArchiveReport,
    val manifest: AniyomiApkManifestReport,
    val dex: AniyomiApkDexReport,
    val signature: AniyomiApkSignatureReport,
    val runtimeCompatibilityVerified: Boolean = false,
)

/**
 * Capability produced only after every static gate succeeds. Consumers must re-inspect the file
 * immediately before future class loading so a path replacement cannot bypass the measured hash.
 */
class InspectedAniyomiApk private constructor(
    internal val file: File,
    val report: AniyomiApkInspectionReport,
) {
    internal companion object {
        fun create(file: File, report: AniyomiApkInspectionReport): InspectedAniyomiApk =
            InspectedAniyomiApk(file, report)
    }
}

internal data class AniyomiPackageArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val requiredFeatures: Set<String>,
    val metadata: Map<String, String>,
    val requestedPermissions: Set<String>,
    val activities: Set<String>,
    val services: Set<String>,
    val receivers: Set<String>,
    val providers: Set<String>,
    val signerCertificateSha256: Set<String>,
    val signatureVerified: Boolean,
)

internal fun interface AniyomiPackageArchiveReader {
    @Throws(Exception::class)
    fun read(apk: File): AniyomiPackageArchiveFacts
}

internal class AniyomiApkRejectionException(
    val failure: AniyomiApkInspectionFailure,
    cause: Throwable? = null,
) : Exception(failure.message, cause)

internal fun rejectAniyomiApk(
    stage: AniyomiApkInspectionStage,
    code: AniyomiApkRejectionCode,
    message: String,
    subject: String? = null,
    cause: Throwable? = null,
): Nothing = throw AniyomiApkRejectionException(
    failure = AniyomiApkInspectionFailure(stage, code, message, subject),
    cause = cause,
)
