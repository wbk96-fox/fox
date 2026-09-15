package com.foxtv.app.core.plugin.aniyomi

import android.os.Build
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

internal const val ANIYOMI_LIFECYCLE_SCHEMA_VERSION = 1
internal const val ANIYOMI_OFFICIAL_POLICY_ID = "official-jellyfin"
internal const val ANIYOMI_OFFICIAL_POLICY_VERSION = 1
internal const val ANIYOMI_APK_FILE_NAME = "extension.apk"

private val ANIYOMI_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val ANIYOMI_TRANSACTION_ID_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val ANIYOMI_PACKAGE_PATTERN =
    Regex("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+$")

@Serializable
internal enum class AniyomiLifecyclePhase {
    PREPARING,
    PREPARED,
    SELECTING,
    SELECTED,
    ROLLBACK_REQUIRED,
    CLEANUP_PENDING,
}

@Serializable
internal enum class AniyomiStoredArtifactKind {
    TRANSACTION,
    GENERATION,
}

/**
 * Durable identity derived from an accepted inspection of the bytes at [relativePath].
 * The runtime flag is deliberately persisted and required to remain false in this static gate.
 */
@Serializable
internal data class AniyomiArtifactIdentity(
    val generationId: String,
    val kind: AniyomiStoredArtifactKind,
    val transactionId: String? = null,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val certificateSha256: String,
    val factoryClassName: String,
    val policyId: String,
    val policyVersion: Int,
    val runtimeCompatibilityVerified: Boolean = false,
) {
    init {
        require(ANIYOMI_SHA256_PATTERN.matches(generationId)) { "Invalid Aniyomi generation ID" }
        require(ANIYOMI_SHA256_PATTERN.matches(sha256) && generationId == sha256) {
            "Aniyomi generation ID must equal the artifact SHA-256"
        }
        require(sizeBytes > 0L) { "Aniyomi artifact size must be positive" }
        require(ANIYOMI_PACKAGE_PATTERN.matches(packageName)) { "Invalid Aniyomi package name" }
        require(versionCode >= 0L && versionName.isNotBlank()) { "Invalid Aniyomi artifact version" }
        require(ANIYOMI_SHA256_PATTERN.matches(certificateSha256)) {
            "Invalid Aniyomi certificate SHA-256"
        }
        require(ANIYOMI_PACKAGE_PATTERN.matches(factoryClassName)) {
            "Invalid Aniyomi factory class"
        }
        require(policyId.isNotBlank() && policyVersion > 0) { "Invalid Aniyomi policy identity" }
        require(!runtimeCompatibilityVerified) {
            "Static Aniyomi lifecycle cannot claim runtime compatibility"
        }
        when (kind) {
            AniyomiStoredArtifactKind.TRANSACTION -> {
                val id = requireNotNull(transactionId) {
                    "Transaction artifact requires a transaction ID"
                }
                require(ANIYOMI_TRANSACTION_ID_PATTERN.matches(id)) {
                    "Invalid Aniyomi transaction ID"
                }
                require(relativePath == "transactions/$id/candidate.apk") {
                    "Transaction artifact path does not match its ID"
                }
            }

            AniyomiStoredArtifactKind.GENERATION -> {
                require(transactionId == null) { "Generation artifact cannot retain a transaction ID" }
                require(relativePath == "generations/$generationId/$ANIYOMI_APK_FILE_NAME") {
                    "Generation artifact path does not match its ID"
                }
            }
        }
    }

    fun asGeneration(): AniyomiArtifactIdentity = copy(
        kind = AniyomiStoredArtifactKind.GENERATION,
        transactionId = null,
        relativePath = "generations/$generationId/$ANIYOMI_APK_FILE_NAME",
    )

    companion object {
        fun fromInspection(
            artifact: InspectedAniyomiApk,
            kind: AniyomiStoredArtifactKind,
            transactionId: String?,
            policy: AniyomiTrustedPolicy,
        ): AniyomiArtifactIdentity {
            val report = artifact.report
            val signer = report.signature.certificateSha256.singleOrNull()
                ?: throw AniyomiLifecycleException(
                    AniyomiLifecycleFailure(
                        AniyomiLifecycleFailureCode.INSPECTION_REJECTED,
                        "Accepted Aniyomi report does not contain exactly one signer",
                    ),
                )
            val relativePath = when (kind) {
                AniyomiStoredArtifactKind.TRANSACTION -> {
                    val id = requireNotNull(transactionId)
                    "transactions/$id/candidate.apk"
                }

                AniyomiStoredArtifactKind.GENERATION ->
                    "generations/${report.sha256}/$ANIYOMI_APK_FILE_NAME"
            }
            return AniyomiArtifactIdentity(
                generationId = report.sha256,
                kind = kind,
                transactionId = transactionId,
                relativePath = relativePath,
                sizeBytes = report.sizeBytes,
                sha256 = report.sha256,
                packageName = report.manifest.packageName,
                versionCode = report.manifest.versionCode,
                versionName = report.manifest.versionName,
                certificateSha256 = signer,
                factoryClassName = report.manifest.factoryClassName,
                policyId = policy.id,
                policyVersion = policy.version,
                runtimeCompatibilityVerified = report.runtimeCompatibilityVerified,
            )
        }
    }
}

@Serializable
internal data class AniyomiLifecycleRecord(
    val schemaVersion: Int = ANIYOMI_LIFECYCLE_SCHEMA_VERSION,
    val policyId: String,
    val policyVersion: Int,
    val phase: AniyomiLifecyclePhase,
    val transactionId: String? = null,
    val selected: AniyomiArtifactIdentity? = null,
    val previous: AniyomiArtifactIdentity? = null,
    val candidate: AniyomiArtifactIdentity? = null,
) {
    init {
        require(schemaVersion == ANIYOMI_LIFECYCLE_SCHEMA_VERSION) {
            "Unsupported Aniyomi lifecycle schema"
        }
        require(policyId.isNotBlank() && policyVersion > 0) { "Invalid lifecycle policy identity" }
        transactionId?.let {
            require(ANIYOMI_TRANSACTION_ID_PATTERN.matches(it)) { "Invalid lifecycle transaction ID" }
        }
        listOfNotNull(selected, previous, candidate).forEach { artifact ->
            require(!artifact.runtimeCompatibilityVerified) {
                "Static lifecycle record cannot claim runtime compatibility"
            }
        }
        val phasePolicyOwner = when (phase) {
            AniyomiLifecyclePhase.PREPARING,
            AniyomiLifecyclePhase.PREPARED,
            AniyomiLifecyclePhase.SELECTING,
            -> candidate

            AniyomiLifecyclePhase.SELECTED,
            AniyomiLifecyclePhase.CLEANUP_PENDING,
            -> selected

            AniyomiLifecyclePhase.ROLLBACK_REQUIRED -> previous
        }
        requireNotNull(phasePolicyOwner) { "$phase requires a policy-owning artifact" }
        require(
            phasePolicyOwner.policyId == policyId &&
                phasePolicyOwner.policyVersion == policyVersion,
        ) {
            "Lifecycle operation policy does not match its phase artifact"
        }
        require(selected?.kind != AniyomiStoredArtifactKind.TRANSACTION) {
            "Selected artifact must be an immutable generation"
        }
        require(previous?.kind != AniyomiStoredArtifactKind.TRANSACTION) {
            "Previous artifact must be an immutable generation"
        }
        when (phase) {
            AniyomiLifecyclePhase.PREPARING,
            AniyomiLifecyclePhase.PREPARED,
            -> {
                requireNotNull(transactionId) { "$phase requires a transaction" }
                requireNotNull(candidate) { "$phase requires a candidate" }
                require(candidate.kind == AniyomiStoredArtifactKind.TRANSACTION) {
                    "$phase candidate must be in transaction storage"
                }
                require(candidate.transactionId == transactionId) {
                    "$phase candidate transaction does not match the journal"
                }
            }

            AniyomiLifecyclePhase.SELECTING -> {
                requireNotNull(candidate) { "SELECTING requires a candidate" }
                require(candidate.kind == AniyomiStoredArtifactKind.GENERATION) {
                    "SELECTING candidate must be an immutable generation"
                }
            }

            AniyomiLifecyclePhase.SELECTED -> {
                requireNotNull(selected) { "SELECTED requires a selected generation" }
                require(transactionId == null && candidate == null) {
                    "SELECTED cannot retain transaction state"
                }
            }

            AniyomiLifecyclePhase.ROLLBACK_REQUIRED -> {
                requireNotNull(previous) { "ROLLBACK_REQUIRED requires a previous generation" }
                require(candidate == null) { "ROLLBACK_REQUIRED cannot retain a candidate" }
            }

            AniyomiLifecyclePhase.CLEANUP_PENDING -> {
                requireNotNull(selected) { "CLEANUP_PENDING requires a selected generation" }
                require(candidate == null) { "CLEANUP_PENDING cannot retain a candidate" }
            }
        }
    }

    fun selectedRecord(): AniyomiLifecycleRecord {
        val current = requireNotNull(selected)
        return copy(
            phase = AniyomiLifecyclePhase.SELECTED,
            transactionId = null,
            selected = current,
            candidate = null,
        )
    }
}

internal data class AniyomiTrustedPolicy(
    val id: String,
    val version: Int,
    val policy: AniyomiApkPolicy,
) {
    init {
        require(id.isNotBlank() && version > 0) { "Invalid trusted Aniyomi policy identity" }
    }
}

internal interface AniyomiTrustedPolicyProvider {
    /** Policy allowed for newly staged bytes. */
    fun current(): AniyomiTrustedPolicy

    /**
     * Resolve only a host-compiled policy for a persisted generation. Metadata can select among
     * this allowlist but can never construct or widen a policy.
     */
    fun find(policyId: String, policyVersion: Int, generationId: String): AniyomiTrustedPolicy?
}

@Singleton
internal class OfficialJellyfinAniyomiPolicyProvider @Inject constructor() :
    AniyomiTrustedPolicyProvider {
    override fun current(): AniyomiTrustedPolicy = officialPolicy()

    override fun find(
        policyId: String,
        policyVersion: Int,
        generationId: String,
    ): AniyomiTrustedPolicy? = officialPolicy().takeIf { trusted ->
        trusted.id == policyId &&
            trusted.version == policyVersion &&
            trusted.policy.expectedSha256 == generationId
    }

    private fun officialPolicy(): AniyomiTrustedPolicy {
        val supportedAbis = Build.SUPPORTED_ABIS
            .orEmpty()
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        check(supportedAbis.isNotEmpty()) { "Android did not report a supported device ABI" }
        return AniyomiTrustedPolicy(
            id = ANIYOMI_OFFICIAL_POLICY_ID,
            version = ANIYOMI_OFFICIAL_POLICY_VERSION,
            policy = AniyomiApkPolicy.officialJellyfinV14_17(supportedAbis),
        )
    }
}

internal enum class AniyomiLifecycleFailureCode {
    INVALID_CAPABILITY,
    UNSAFE_STORAGE_PATH,
    IMMUTABLE_GENERATION_REQUIRED,
    STORAGE_IO,
    COPY_INTEGRITY_MISMATCH,
    SOURCE_CHANGED_DURING_COPY,
    INSPECTION_REJECTED,
    STATE_CORRUPT,
    STATE_PERSISTENCE,
    POLICY_MISMATCH,
    CLEANUP_FAILED,
    NO_ROLLBACK_CANDIDATE,
    LOAD_BOUNDARY_RELEASE_FAILED,
    RECOVERY_FAILED,
}

internal data class AniyomiLifecycleFailure(
    val code: AniyomiLifecycleFailureCode,
    val message: String,
    val inspectionFailures: List<AniyomiApkInspectionFailure> = emptyList(),
    val cause: Throwable? = null,
)

internal class AniyomiLifecycleException(
    val failure: AniyomiLifecycleFailure,
) : Exception(failure.message, failure.cause)

internal sealed interface AniyomiLifecycleOutcome {
    data class Selected(
        val identity: AniyomiArtifactIdentity,
        val report: AniyomiApkInspectionReport,
        val recovered: Boolean,
    ) : AniyomiLifecycleOutcome {
        init {
            require(!identity.runtimeCompatibilityVerified && !report.runtimeCompatibilityVerified)
        }
    }

    data object Empty : AniyomiLifecycleOutcome

    data class Failed(
        val failure: AniyomiLifecycleFailure,
        val retainedSelection: AniyomiArtifactIdentity? = null,
    ) : AniyomiLifecycleOutcome
}
