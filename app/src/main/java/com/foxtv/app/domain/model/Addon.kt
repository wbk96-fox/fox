package com.foxtv.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Addon(
    val id: String,
    val name: String,
    val displayName: String = name,
    val version: String,
    val description: String?,
    val logo: String?,
    val background: String? = null,
    val baseUrl: String,
    val catalogs: List<CatalogDescriptor>,
    val types: List<ContentType>,
    val rawTypes: List<String> = types.map { it.toApiString() },
    val resources: List<AddonResource>,
    val idPrefixes: List<String> = emptyList(),
    val behaviorHints: AddonBehaviorHints? = null,
    val stremioAddonsConfig: StremioAddonsConfig? = null,
    val manifestLanguage: String? = null,
    val configVersion: Long? = null,
    val timestamp: Long? = null,
    val enabled: Boolean = true
) {
    val isActive: Boolean
        get() = enabled
}

fun List<Addon>.enabledAddons(): List<Addon> =
    filter { it.enabled }

@Immutable
data class CatalogDescriptor(
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val id: String,
    val name: String,
    val extra: List<CatalogExtra> = emptyList(),
    val pageSize: Int? = null,
    val showInHome: Boolean = false,
    val hasExplicitShowInHome: Boolean = false,
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList()
) {
    val apiType: String
        get() = type.toApiString(rawType)

    /**
     * Whether this catalog accepts the `name` extra.
     *
     * A Stremio manifest can declare extras in three shapes: the full `extra` array, the
     * short-form `extraSupported` list, and `extraRequired` for extras the catalog cannot be
     * queried without. All three must be honoured — a catalog that only lists `search` under
     * `extraRequired` does support search, and treating it as unsupported hides the addon from
     * the search flow entirely.
     */
    fun supportsExtra(name: String): Boolean =
        extraSupported.any { it.equals(name, ignoreCase = true) } ||
            extraRequired.any { it.equals(name, ignoreCase = true) } ||
            extra.any { it.name.equals(name, ignoreCase = true) }
}

@Immutable
data class CatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String>? = null,
    val defaultValue: String? = null,
    val optionsLimit: Int? = null
)

@Immutable
data class AddonResource(
    val name: String,
    val types: List<String>,
    val idPrefixes: List<String>?
)

@Immutable
data class AddonBehaviorHints(
    val configurable: Boolean? = null,
    val configurationRequired: Boolean? = null,
    val newEpisodeNotifications: Boolean? = null
)

@Immutable
data class StremioAddonsConfig(
    val issuer: String? = null,
    val signature: String? = null
)
