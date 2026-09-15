package com.foxtv.app.core.plugin.cloudstream

import com.foxtv.app.domain.model.ExternalPluginEntry
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val SUPPORTED_EXTERNAL_API_VERSION = 1
internal const val MAX_EXTERNAL_EXTENSION_BYTES = 10L * 1024L * 1024L

internal enum class ExternalPluginRejectionClassification {
    EXPLICITLY_INACTIVE,
    ACTIVE_INVALID,
    DUPLICATE_AMBIGUITY,
}

internal data class ExternalPluginRejection(
    val internalName: String,
    val reason: String,
    val classification: ExternalPluginRejectionClassification,
)

internal data class ExternalPluginPreparation(
    val accepted: List<ExternalPluginEntry>,
    val rejected: List<ExternalPluginRejection>,
    val declaredInternalNames: List<String>,
) {
    private val duplicateInternalNames: Set<String> = rejected
        .asSequence()
        .filter { it.classification == ExternalPluginRejectionClassification.DUPLICATE_AMBIGUITY }
        .map { it.internalName }
        .toSet()

    val explicitlyInactiveInternalNames: Set<String> = rejected
        .asSequence()
        .filter { it.classification == ExternalPluginRejectionClassification.EXPLICITLY_INACTIVE }
        .map { it.internalName }
        .filterNot(duplicateInternalNames::contains)
        .toSet()

    val hasActiveInvalidEntries: Boolean = rejected.any {
        it.classification == ExternalPluginRejectionClassification.ACTIVE_INVALID
    }

    val hasDuplicateAmbiguity: Boolean = duplicateInternalNames.isNotEmpty()
}

/** Validates the signed metadata contract used by Cloudstream plugins.json files. */
internal object ExternalRepositoryContract {
    private val sha256Pattern = Regex("^sha256-[0-9a-fA-F]{64}$")
    private val internalNamePattern = Regex("^[A-Za-z0-9._-]{1,128}$")

    /** Resolves [reference] with RFC HttpUrl semantics and accepts only HTTPS results. */
    fun resolveHttpsUrl(baseUrl: String, reference: String): String? {
        val base = baseUrl.trim().toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
        val candidate = reference.trim()
        if (candidate.isEmpty()) return null
        return base.resolve(candidate)?.takeIf { it.isHttps }?.toString()
    }

    fun preparePlugins(
        entries: List<ExternalPluginEntry>,
        sourceListUrl: String,
    ): ExternalPluginPreparation {
        val accepted = mutableListOf<ExternalPluginEntry>()
        val rejected = mutableListOf<ExternalPluginRejection>()
        val declaredInternalNames = mutableListOf<String>()
        val seenInternalNames = mutableSetOf<String>()

        entries.forEach { entry ->
            val identity = entry.internalName.ifBlank { entry.name.ifBlank { "<unnamed>" } }
            if (!internalNamePattern.matches(entry.internalName)) {
                rejected += ExternalPluginRejection(
                    internalName = identity,
                    reason = "invalid internalName",
                    classification = ExternalPluginRejectionClassification.ACTIVE_INVALID,
                )
                return@forEach
            }

            declaredInternalNames += entry.internalName
            if (!seenInternalNames.add(entry.internalName)) {
                rejected += ExternalPluginRejection(
                    internalName = entry.internalName,
                    reason = "duplicate internalName",
                    classification = ExternalPluginRejectionClassification.DUPLICATE_AMBIGUITY,
                )
                return@forEach
            }

            if (entry.status != 1) {
                rejected += ExternalPluginRejection(
                    internalName = entry.internalName,
                    reason = "status ${entry.status} is not active",
                    classification = ExternalPluginRejectionClassification.EXPLICITLY_INACTIVE,
                )
                return@forEach
            }

            val reason = activeRejectionReason(entry, sourceListUrl)
            if (reason != null) {
                rejected += ExternalPluginRejection(
                    internalName = identity,
                    reason = reason,
                    classification = ExternalPluginRejectionClassification.ACTIVE_INVALID,
                )
                return@forEach
            }

            val resolvedUrl = requireNotNull(resolveHttpsUrl(sourceListUrl, entry.url))
            val resolvedRepositoryUrl = requireNotNull(
                resolveHttpsUrl(sourceListUrl, requireNotNull(entry.repositoryUrl)),
            )
            accepted += entry.copy(
                name = entry.name.trim(),
                internalName = entry.internalName.trim(),
                authors = entry.authors.orEmpty().map(String::trim).filter(String::isNotEmpty),
                url = resolvedUrl,
                fileHash = entry.fileHash?.lowercase(),
                repositoryUrl = resolvedRepositoryUrl,
                language = entry.language?.trim()?.takeIf(String::isNotEmpty),
            )
        }

        return ExternalPluginPreparation(
            accepted = accepted,
            rejected = rejected,
            declaredInternalNames = declaredInternalNames,
        )
    }

    private fun activeRejectionReason(entry: ExternalPluginEntry, sourceListUrl: String): String? {
        if (entry.apiVersion != SUPPORTED_EXTERNAL_API_VERSION) {
            return "unsupported apiVersion ${entry.apiVersion}"
        }
        if (entry.version <= 0) return "version must be positive"
        if (entry.name.isBlank()) return "name is blank"
        if (entry.authors.orEmpty().none { it.isNotBlank() }) return "authors are missing"

        val fileSize = entry.fileSize
            ?: return "fileSize is missing"
        if (fileSize !in 1..MAX_EXTERNAL_EXTENSION_BYTES) {
            return "fileSize is outside the supported range"
        }

        val fileHash = entry.fileHash
            ?: return "fileHash is missing"
        if (!sha256Pattern.matches(fileHash)) return "fileHash is not strict sha256 metadata"

        val resolvedUrl = resolveHttpsUrl(sourceListUrl, entry.url)
            ?: return "plugin URL is invalid or is not HTTPS"
        val artifactUrl = resolvedUrl.toHttpUrlOrNull()
            ?: return "plugin URL is invalid"
        if (!artifactUrl.encodedPath.endsWith(".cs3", ignoreCase = true)) {
            return "plugin artifact is not a .cs3 file"
        }

        val repositoryUrl = entry.repositoryUrl
            ?: return "repositoryUrl is missing"
        if (resolveHttpsUrl(sourceListUrl, repositoryUrl) == null) {
            return "repositoryUrl is invalid or is not HTTPS"
        }
        return null
    }
}
