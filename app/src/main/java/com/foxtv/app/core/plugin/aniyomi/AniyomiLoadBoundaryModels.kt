package com.foxtv.app.core.plugin.aniyomi

import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.File

internal const val ANIYOMI_LOAD_BOUNDARY_SCHEMA_VERSION = 1
internal const val ANIYOMI_LOAD_STATE_FILE_NAME = "load-boundary.json"

private val ANIYOMI_LOAD_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val ANIYOMI_LOAD_OPERATION_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val ANIYOMI_BINARY_CLASS_PATTERN =
    Regex("^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+$")

@Serializable
internal enum class AniyomiLoadBoundaryPhase {
    DISABLED,
    ENABLED_IDLE,
    ACTIVATING,
    ACTIVE,
    RELEASE_PENDING,
}

@Serializable
internal data class AniyomiLoadGenerationRef(
    val generationId: String,
    val policyId: String,
    val policyVersion: Int,
    val factoryClassName: String,
) {
    init {
        require(ANIYOMI_LOAD_SHA256_PATTERN.matches(generationId)) {
            "Invalid Aniyomi load generation ID"
        }
        require(policyId.isNotBlank() && policyVersion > 0) {
            "Invalid Aniyomi load policy identity"
        }
        require(ANIYOMI_BINARY_CLASS_PATTERN.matches(factoryClassName)) {
            "Invalid Aniyomi factory class name"
        }
    }

    companion object {
        fun from(identity: AniyomiArtifactIdentity): AniyomiLoadGenerationRef =
            AniyomiLoadGenerationRef(
                generationId = identity.generationId,
                policyId = identity.policyId,
                policyVersion = identity.policyVersion,
                factoryClassName = identity.factoryClassName,
            )
    }
}

@Serializable
internal data class AniyomiLoadBoundaryRecord(
    val schemaVersion: Int = ANIYOMI_LOAD_BOUNDARY_SCHEMA_VERSION,
    val phase: AniyomiLoadBoundaryPhase,
    val desiredEnabled: Boolean,
    val operationId: String? = null,
    val sessionId: String? = null,
    val target: AniyomiLoadGenerationRef? = null,
    val lastKnownGood: AniyomiLoadGenerationRef? = null,
    val failedGeneration: AniyomiLoadGenerationRef? = null,
) {
    init {
        require(schemaVersion == ANIYOMI_LOAD_BOUNDARY_SCHEMA_VERSION) {
            "Unsupported Aniyomi load-boundary schema"
        }
        operationId?.let {
            require(ANIYOMI_LOAD_OPERATION_PATTERN.matches(it)) {
                "Invalid Aniyomi load operation ID"
            }
        }
        sessionId?.let {
            require(ANIYOMI_LOAD_OPERATION_PATTERN.matches(it)) {
                "Invalid Aniyomi load session ID"
            }
        }
        when (phase) {
            AniyomiLoadBoundaryPhase.DISABLED -> {
                require(!desiredEnabled) { "DISABLED cannot retain enabled intent" }
                require(operationId == null && sessionId == null && target == null) {
                    "DISABLED cannot retain an active load operation"
                }
                require(lastKnownGood == null && failedGeneration == null) {
                    "DISABLED cannot retain generation fallback state"
                }
            }

            AniyomiLoadBoundaryPhase.ENABLED_IDLE -> {
                require(desiredEnabled) { "ENABLED_IDLE requires enabled intent" }
                require(operationId == null && sessionId == null && target == null) {
                    "ENABLED_IDLE cannot retain an active load operation"
                }
            }

            AniyomiLoadBoundaryPhase.ACTIVATING -> {
                require(desiredEnabled) { "ACTIVATING requires enabled intent" }
                requireNotNull(operationId) { "ACTIVATING requires an operation ID" }
                require(sessionId == null) { "ACTIVATING cannot publish a session ID" }
                requireNotNull(target) { "ACTIVATING requires an exact target" }
            }

            AniyomiLoadBoundaryPhase.ACTIVE -> {
                require(desiredEnabled) { "ACTIVE requires enabled intent" }
                require(operationId == null) { "ACTIVE cannot retain an operation ID" }
                requireNotNull(sessionId) { "ACTIVE requires a session ID" }
                requireNotNull(target) { "ACTIVE requires an exact target" }
                require(target == lastKnownGood) {
                    "ACTIVE target must be the last known good generation"
                }
                require(failedGeneration == null) {
                    "ACTIVE cannot retain a failed generation"
                }
            }

            AniyomiLoadBoundaryPhase.RELEASE_PENDING -> {
                requireNotNull(operationId) { "RELEASE_PENDING requires an operation ID" }
                require(sessionId == null) { "RELEASE_PENDING cannot expose an active session" }
            }
        }
    }

    companion object {
        fun disabled(): AniyomiLoadBoundaryRecord = AniyomiLoadBoundaryRecord(
            phase = AniyomiLoadBoundaryPhase.DISABLED,
            desiredEnabled = false,
        )

        fun enabledIdle(
            lastKnownGood: AniyomiLoadGenerationRef? = null,
            failedGeneration: AniyomiLoadGenerationRef? = null,
        ): AniyomiLoadBoundaryRecord = AniyomiLoadBoundaryRecord(
            phase = AniyomiLoadBoundaryPhase.ENABLED_IDLE,
            desiredEnabled = true,
            lastKnownGood = lastKnownGood,
            failedGeneration = failedGeneration,
        )
    }
}

/**
 * Ephemeral capability issued only while [AniyomiLifecycleCoordinator] holds its M2 mutex.
 * It is never serialized and M3 has no API accepting a caller-provided path.
 */
internal class AniyomiSelectedGeneration internal constructor(
    val file: File,
    val identity: AniyomiArtifactIdentity,
    val report: AniyomiApkInspectionReport,
    val policy: AniyomiTrustedPolicy,
) {
    init {
        require(identity.kind == AniyomiStoredArtifactKind.GENERATION)
        require(identity.generationId == report.sha256)
        require(identity.policyId == policy.id && identity.policyVersion == policy.version)
        require(policy.policy.expectedSha256 == identity.generationId)
        require(!identity.runtimeCompatibilityVerified && !report.runtimeCompatibilityVerified)
    }

    val reference: AniyomiLoadGenerationRef = AniyomiLoadGenerationRef.from(identity)
}

internal enum class AniyomiClassOwner {
    PARENT,
    CHILD,
}

/** Exact binary-name ownership for the pinned Jellyfin generation. */
internal class AniyomiClassOwnershipPolicy private constructor(
    val factoryClassName: String,
    private val extensionPackagePrefix: String,
) {
    fun ownerOf(binaryName: String): AniyomiClassOwner? {
        if (!ANIYOMI_BINARY_CLASS_PATTERN.matches(binaryName)) return null
        if (isChildOwned(binaryName)) return AniyomiClassOwner.CHILD
        if (isParentOwned(binaryName)) return AniyomiClassOwner.PARENT
        return null
    }

    fun requireFactoryOwnedByChild() {
        if (ownerOf(factoryClassName) != AniyomiClassOwner.CHILD) {
            throw AniyomiLoadBoundaryException(
                AniyomiLoadBoundaryFailure(
                    AniyomiLoadBoundaryFailureCode.CLASS_OWNERSHIP_VIOLATION,
                    "Aniyomi factory is not owned by the selected child namespace",
                ),
            )
        }
    }

    private fun isChildOwned(name: String): Boolean {
        if (name == extensionPackagePrefix.dropLast(1) || name.startsWith(extensionPackagePrefix)) {
            return true
        }
        if (name == "eu.kanade.tachiyomi.animeextension.BuildConfig") return true
        if (name == "eu.kanade.tachiyomi.animeextension.R" ||
            name.startsWith("eu.kanade.tachiyomi.animeextension.R$") ||
            name == "eu.kanade.tachiyomi.lib.core.R" ||
            name.startsWith("eu.kanade.tachiyomi.lib.core.R$")
        ) {
            return true
        }
        return name == "org.apache.commons" || name.startsWith("org.apache.commons.")
    }

    private fun isParentOwned(name: String): Boolean {
        if (name == "eu.kanade.tachiyomi.AppInfo") return true
        return PARENT_PREFIXES.any(name::startsWith)
    }

    companion object {
        private val PARENT_PREFIXES = listOf(
            "java.",
            "javax.",
            "android.",
            "dalvik.",
            "org.json.",
            "org.w3c.",
            "org.xml.",
            "eu.kanade.tachiyomi.animesource.",
            "eu.kanade.tachiyomi.network.",
            "androidx.preference.",
            "uy.kohesive.injekt.",
            "kotlin.",
            "kotlinx.",
            "okhttp3.",
            "okio.",
            "rx.",
        )

        fun from(selected: AniyomiSelectedGeneration): AniyomiClassOwnershipPolicy {
            val expectedPackage = selected.policy.policy.expectedPackageName
            if (selected.identity.packageName != expectedPackage ||
                selected.identity.factoryClassName != selected.policy.policy.expectedFactoryClassName
            ) {
                throw AniyomiLoadBoundaryException(
                    AniyomiLoadBoundaryFailure(
                        AniyomiLoadBoundaryFailureCode.POLICY_MISMATCH,
                        "Selected Aniyomi identity does not match class-ownership policy",
                    ),
                )
            }
            return AniyomiClassOwnershipPolicy(
                factoryClassName = selected.identity.factoryClassName,
                extensionPackagePrefix = "$expectedPackage.",
            ).also(AniyomiClassOwnershipPolicy::requireFactoryOwnedByChild)
        }
    }
}

internal enum class AniyomiLoadBoundaryFailureCode {
    DISABLED,
    NO_SELECTED_GENERATION,
    M2_LIFECYCLE_REJECTED,
    UNSAFE_CODE_SOURCE,
    WRITABLE_CODE_SOURCE,
    POLICY_MISMATCH,
    STALE_GENERATION,
    POISONED_GENERATION,
    CLASS_OWNERSHIP_VIOLATION,
    DUPLICATE_OWNER,
    CLASS_NOT_FOUND,
    TYPE_MISMATCH,
    CONSTRUCTOR_MISSING,
    CONSTRUCTOR_FAILED,
    INITIALIZATION_FAILED,
    HOST_NOT_READY,
    STATE_CORRUPT,
    STATE_PERSISTENCE,
    CACHE_IO,
    RELEASE_FAILED,
    RECOVERY_FAILED,
    ROLLBACK_REJECTED,
}

internal data class AniyomiLoadBoundaryFailure(
    val code: AniyomiLoadBoundaryFailureCode,
    val message: String,
    val cause: Throwable? = null,
)

internal class AniyomiLoadBoundaryException(
    val failure: AniyomiLoadBoundaryFailure,
) : Exception(failure.message, failure.cause)

internal sealed interface AniyomiLoadBoundaryOutcome {
    data class Activated(
        val sessionId: String,
        val generation: AniyomiLoadGenerationRef,
        val reusedExisting: Boolean,
    ) : AniyomiLoadBoundaryOutcome

    data class Enabled(val alreadyEnabled: Boolean) : AniyomiLoadBoundaryOutcome

    data class Released(val enabled: Boolean) : AniyomiLoadBoundaryOutcome

    data object Disabled : AniyomiLoadBoundaryOutcome

    data class Failed(val failure: AniyomiLoadBoundaryFailure) : AniyomiLoadBoundaryOutcome
}

internal data class AniyomiActivationRollbackRequest(
    val failedGeneration: AniyomiLoadGenerationRef,
    val lastKnownGood: AniyomiLoadGenerationRef,
)

internal interface AniyomiLoadBoundaryStateStore {
    suspend fun read(): AniyomiLoadBoundaryRecord?
    suspend fun write(record: AniyomiLoadBoundaryRecord)
}

internal interface AniyomiCodeCacheStore {
    /**
     * Creates an app-private per-generation optimized-output directory required only on API 24–25.
     * DexClassLoader ignores this directory on API 26+, where ART manages its own cache.
     */
    suspend fun prepare(generationId: String): File

    /** Removes only the owned legacy workspace; it never claims to remove platform-managed ART data. */
    suspend fun cleanup(generationId: String)

    suspend fun recover()
}

internal fun interface AniyomiStartupGate {
    /** Returns a typed blocker, or null only after both M3 and M2 recovery complete. */
    suspend fun awaitReady(): AniyomiLoadBoundaryFailure?
}

internal fun interface AniyomiSelectionChangeBarrier {
    suspend fun beforeSelectionChange(
        currentSelection: AniyomiLoadGenerationRef?,
        nextSelection: AniyomiLoadGenerationRef?,
    )
}

internal interface AniyomiOwnedClassLoaderHandle : Closeable {
    val classLoader: ClassLoader
    fun loadChildClass(binaryName: String): Class<*>
}

internal fun interface AniyomiClassLoaderFactory {
    fun open(
        sourceApk: File,
        legacyOptimizedDirectory: File,
        ownership: AniyomiClassOwnershipPolicy,
    ): AniyomiOwnedClassLoaderHandle
}
