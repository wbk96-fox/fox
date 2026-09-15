package com.foxtv.app.core.plugin.aniyomi

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fail-closed static gate for Aniyomi extension APKs.
 *
 * This class performs no installation, reflection, DEX VM opening, class loading, or extension
 * execution. Acceptance means only that the immutable bytes satisfy the supplied static policy.
 */
internal fun ensureAniyomiInspectionActive() {
    if (Thread.currentThread().isInterrupted) {
        throw CancellationException("Aniyomi static inspection was cancelled")
    }
}

internal fun interface AniyomiStaticApkInspector {
    fun inspect(apk: File, policy: AniyomiApkPolicy): AniyomiApkInspectionResult
}

@Singleton
class AniyomiExtensionApkInspector @Inject internal constructor(
    private val packageArchiveReader: AniyomiPackageArchiveReader,
) : AniyomiStaticApkInspector {
    override fun inspect(apk: File, policy: AniyomiApkPolicy): AniyomiApkInspectionResult {
        return try {
            ensureAniyomiInspectionActive()
            val initial = measureAndValidateFile(apk, policy)
            ensureAniyomiInspectionActive()
            val archive = AniyomiApkArchiveInspector.inspect(initial.canonicalFile, policy)
            ensureAniyomiInspectionActive()
            val packageFacts = readPackageFacts(initial.canonicalFile)
            ensureAniyomiInspectionActive()
            val manifest = validatePackageFacts(packageFacts, policy)
            val dex = AniyomiDexInspector.inspect(
                apk = initial.canonicalFile,
                dexEntryNames = archive.dexEntries,
                policy = policy,
            )
            ensureAniyomiInspectionActive()
            val finalMeasurement = measureFile(initial.canonicalFile, policy.maxArchiveBytes)
            if (finalMeasurement.sizeBytes != initial.sizeBytes ||
                finalMeasurement.sha256 != initial.sha256
            ) {
                rejectAniyomiApk(
                    AniyomiApkInspectionStage.FILE,
                    AniyomiApkRejectionCode.ARTIFACT_CHANGED_DURING_INSPECTION,
                    "APK bytes changed during static inspection",
                )
            }

            val report = AniyomiApkInspectionReport(
                canonicalPath = initial.canonicalFile.path,
                sizeBytes = initial.sizeBytes,
                sha256 = initial.sha256,
                archive = archive.report,
                manifest = manifest,
                dex = dex,
                signature = AniyomiApkSignatureReport(
                    certificateSha256 = packageFacts.signerCertificateSha256,
                    cryptographicallyVerified = packageFacts.signatureVerified,
                ),
            )
            AniyomiApkInspectionResult.Accepted(
                InspectedAniyomiApk.create(initial.canonicalFile, report),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: AniyomiApkRejectionException) {
            AniyomiApkInspectionResult.Rejected(listOf(rejected.failure))
        } catch (error: Exception) {
            AniyomiApkInspectionResult.Rejected(
                listOf(
                    AniyomiApkInspectionFailure(
                        stage = AniyomiApkInspectionStage.POLICY,
                        code = AniyomiApkRejectionCode.MALFORMED_ARTIFACT,
                        message = "APK inspection failed closed: ${error.javaClass.simpleName}",
                    ),
                ),
            )
        }
    }

    private fun measureAndValidateFile(apk: File, policy: AniyomiApkPolicy): FileMeasurement {
        val absolute = apk.absoluteFile
        if (!absolute.exists() || !absolute.isFile) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.FILE,
                AniyomiApkRejectionCode.FILE_NOT_REGULAR,
                "APK path is not an existing regular file",
            )
        }
        val canonical = try {
            absolute.canonicalFile
        } catch (error: Exception) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.FILE,
                AniyomiApkRejectionCode.FILE_PATH_NOT_CANONICAL,
                "APK path could not be canonicalized",
                cause = error,
            )
        }
        if (canonical.path != absolute.path) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.FILE,
                AniyomiApkRejectionCode.FILE_PATH_NOT_CANONICAL,
                "APK path contains a symbolic link or non-canonical segment",
            )
        }
        if (canonical.length() != policy.expectedSizeBytes) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.FILE,
                AniyomiApkRejectionCode.SIZE_MISMATCH,
                "APK size does not match trusted metadata",
            )
        }
        val measured = measureFile(canonical, policy.maxArchiveBytes)
        if (measured.sizeBytes != policy.expectedSizeBytes) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.FILE,
                AniyomiApkRejectionCode.SIZE_MISMATCH,
                "APK streamed size does not match trusted metadata",
            )
        }
        if (measured.sha256 != policy.expectedSha256) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.HASH,
                AniyomiApkRejectionCode.HASH_MISMATCH,
                "APK SHA-256 does not match trusted metadata",
            )
        }
        return measured.copy(canonicalFile = canonical)
    }

    private fun measureFile(file: File, maxBytes: Long): FileMeasurement {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                ensureAniyomiInspectionActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                count += read
                if (count > maxBytes) {
                    rejectAniyomiApk(
                        AniyomiApkInspectionStage.FILE,
                        AniyomiApkRejectionCode.SIZE_MISMATCH,
                        "APK exceeds the configured byte budget while reading",
                    )
                }
                digest.update(buffer, 0, read)
            }
        }
        return FileMeasurement(
            canonicalFile = file,
            sizeBytes = count,
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
        )
    }

    private fun readPackageFacts(apk: File): AniyomiPackageArchiveFacts {
        return try {
            packageArchiveReader.read(apk)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AniyomiPackageArchiveReadException) {
            val signatureFailure = error.reason == AniyomiPackageArchiveReadException.Reason.SIGNATURE
            rejectAniyomiApk(
                stage = if (signatureFailure) {
                    AniyomiApkInspectionStage.SIGNATURE
                } else {
                    AniyomiApkInspectionStage.MANIFEST
                },
                code = if (signatureFailure) {
                    AniyomiApkRejectionCode.SIGNATURE_INVALID
                } else {
                    AniyomiApkRejectionCode.MANIFEST_UNREADABLE
                },
                message = error.message ?: "Android rejected the APK package archive",
                cause = error,
            )
        } catch (error: Exception) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.MANIFEST,
                AniyomiApkRejectionCode.MANIFEST_UNREADABLE,
                "APK manifest and signer facts could not be read",
                cause = error,
            )
        }
    }

    private fun validatePackageFacts(
        facts: AniyomiPackageArchiveFacts,
        policy: AniyomiApkPolicy,
    ): AniyomiApkManifestReport {
        if (!facts.signatureVerified) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.SIGNATURE,
                AniyomiApkRejectionCode.SIGNATURE_INVALID,
                "APK signature was not cryptographically verified",
            )
        }
        if (facts.signerCertificateSha256.size != 1) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.SIGNATURE,
                AniyomiApkRejectionCode.SIGNER_COUNT_MISMATCH,
                "APK must have exactly one current signer",
            )
        }
        if (facts.signerCertificateSha256.single() != policy.expectedCertificateSha256) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.SIGNATURE,
                AniyomiApkRejectionCode.CERTIFICATE_MISMATCH,
                "APK signer certificate does not match the trusted pin",
            )
        }
        if (facts.packageName != policy.expectedPackageName) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.MANIFEST,
                AniyomiApkRejectionCode.PACKAGE_MISMATCH,
                "APK package name does not match trusted metadata",
                facts.packageName,
            )
        }
        if (facts.versionCode != policy.expectedVersionCode ||
            facts.versionName != policy.expectedVersionName
        ) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.MANIFEST,
                AniyomiApkRejectionCode.VERSION_MISMATCH,
                "APK version does not match trusted metadata",
            )
        }
        if (facts.minSdk != policy.expectedMinSdk || facts.targetSdk != policy.expectedTargetSdk) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.MANIFEST,
                AniyomiApkRejectionCode.SDK_MISMATCH,
                "APK SDK contract does not match trusted metadata",
            )
        }
        if (policy.requiredExtensionFeature !in facts.requiredFeatures) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.MANIFEST,
                AniyomiApkRejectionCode.EXTENSION_CONTRACT_MISSING,
                "APK is not declared as a required Aniyomi anime extension",
                policy.requiredExtensionFeature,
            )
        }

        val factoryValue = facts.metadata[policy.factoryMetadataKey]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: rejectAniyomiApk(
                AniyomiApkInspectionStage.MANIFEST,
                AniyomiApkRejectionCode.EXTENSION_CONTRACT_MISSING,
                "APK manifest has no Aniyomi extension factory metadata",
                policy.factoryMetadataKey,
            )
        val factoryClassName = normalizeClassName(facts.packageName, factoryValue)
        if (factoryClassName != policy.expectedFactoryClassName) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.MANIFEST,
                AniyomiApkRejectionCode.ENTRYPOINT_MISMATCH,
                "APK manifest factory does not match trusted metadata",
                factoryClassName,
            )
        }
        if (facts.metadata[policy.nsfwMetadataKey]?.trim() != policy.expectedNsfwValue) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.MANIFEST,
                AniyomiApkRejectionCode.NSFW_METADATA_MISMATCH,
                "APK NSFW metadata does not match trusted metadata",
                policy.nsfwMetadataKey,
            )
        }

        val forbiddenPermissions = facts.requestedPermissions - policy.allowedPermissions
        if (forbiddenPermissions.isNotEmpty()) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.MANIFEST,
                AniyomiApkRejectionCode.PERMISSION_NOT_ALLOWED,
                "APK requests permissions outside the extension policy",
                forbiddenPermissions.sorted().joinToString(","),
            )
        }
        val components = facts.activities + facts.services + facts.receivers + facts.providers
        if (components.isNotEmpty()) {
            rejectAniyomiApk(
                AniyomiApkInspectionStage.MANIFEST,
                AniyomiApkRejectionCode.COMPONENT_NOT_ALLOWED,
                "Aniyomi extension APK declares executable Android components",
                components.sorted().joinToString(","),
            )
        }

        return AniyomiApkManifestReport(
            packageName = facts.packageName,
            versionCode = facts.versionCode,
            versionName = facts.versionName,
            minSdk = facts.minSdk,
            targetSdk = facts.targetSdk,
            factoryClassName = factoryClassName,
            requiredFeatures = facts.requiredFeatures,
            permissions = facts.requestedPermissions,
        )
    }

    private fun normalizeClassName(packageName: String, value: String): String = when {
        value.startsWith('.') -> packageName + value
        '.' in value -> value
        else -> "$packageName.$value"
    }

    private data class FileMeasurement(
        val canonicalFile: File,
        val sizeBytes: Long,
        val sha256: String,
    )
}
