package com.foxtv.app.core.plugin.cloudstream

import com.foxtv.app.domain.model.ExternalPluginEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRepositoryContractTest {
    @Test
    fun `resolves HTTPS repository lists and artifacts with RFC URL semantics`() {
        assertEquals(
            "https://example.test/repos/builds/plugins.json",
            ExternalRepositoryContract.resolveHttpsUrl(
                "https://example.test/repos/catalog/repo.json?channel=stable",
                "../builds/plugins.json",
            ),
        )
        assertEquals(
            "https://cdn.example.test/releases/Provider.cs3",
            ExternalRepositoryContract.resolveHttpsUrl(
                "https://example.test/repos/builds/plugins.json",
                "//cdn.example.test/releases/Provider.cs3",
            ),
        )

        val result = ExternalRepositoryContract.preparePlugins(
            listOf(validEntry(url = "../artifacts/Provider.cs3")),
            "https://example.test/repos/builds/plugins.json",
        )

        assertTrue(result.rejected.isEmpty())
        assertEquals(
            "https://example.test/repos/artifacts/Provider.cs3",
            result.accepted.single().url,
        )
    }

    @Test
    fun `rejects HTTP bases absolute references artifacts and repository URLs`() {
        assertNull(
            ExternalRepositoryContract.resolveHttpsUrl(
                "http://example.test/repos/repo.json",
                "plugins.json",
            ),
        )
        assertNull(
            ExternalRepositoryContract.resolveHttpsUrl(
                "https://example.test/repos/repo.json",
                "http://example.test/plugins.json",
            ),
        )

        val httpsSourceResult = ExternalRepositoryContract.preparePlugins(
            listOf(
                validEntry(internalName = "HttpArtifact", url = "http://example.test/Provider.cs3"),
                validEntry(
                    internalName = "HttpRepository",
                    repositoryUrl = "http://example.test/source",
                ),
            ),
            PLUGINS_URL,
        )
        val httpSourceResult = ExternalRepositoryContract.preparePlugins(
            listOf(validEntry(internalName = "HttpSource")),
            "http://example.test/repos/plugins.json",
        )

        assertTrue(httpsSourceResult.accepted.isEmpty())
        assertEquals(
            listOf(
                ExternalPluginRejectionClassification.ACTIVE_INVALID,
                ExternalPluginRejectionClassification.ACTIVE_INVALID,
            ),
            httpsSourceResult.rejected.map(ExternalPluginRejection::classification),
        )
        assertTrue(httpSourceResult.accepted.isEmpty())
        assertEquals(
            ExternalPluginRejectionClassification.ACTIVE_INVALID,
            httpSourceResult.rejected.single().classification,
        )
    }

    @Test
    fun `accepts and normalizes verified Cloudstream metadata`() {
        val result = ExternalRepositoryContract.preparePlugins(
            listOf(
                validEntry(
                    fileHash = "sha256-${"AB".repeat(32)}",
                    language = " pl ",
                    authors = listOf(" Author "),
                ),
            ),
            PLUGINS_URL,
        )

        val plugin = result.accepted.single()
        assertEquals("sha256-${"ab".repeat(32)}", plugin.fileHash)
        assertEquals("pl", plugin.language)
        assertEquals(listOf("Author"), plugin.authors)
        assertFalse(result.hasActiveInvalidEntries)
        assertFalse(result.hasDuplicateAmbiguity)
    }

    @Test
    fun `classifies only identifiable inactive entries as explicitly inactive`() {
        val result = ExternalRepositoryContract.preparePlugins(
            listOf(
                validEntry(
                    internalName = "Retired",
                    status = 0,
                    authors = null,
                    fileSize = null,
                    fileHash = null,
                    repositoryUrl = null,
                ),
                validEntry(internalName = "FutureApi", apiVersion = 2),
                validEntry(internalName = "bad/name", status = 0),
            ),
            PLUGINS_URL,
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(
            listOf(
                ExternalPluginRejectionClassification.EXPLICITLY_INACTIVE,
                ExternalPluginRejectionClassification.ACTIVE_INVALID,
                ExternalPluginRejectionClassification.ACTIVE_INVALID,
            ),
            result.rejected.map(ExternalPluginRejection::classification),
        )
        assertEquals(setOf("Retired"), result.explicitlyInactiveInternalNames)
        assertTrue(result.hasActiveInvalidEntries)
        assertFalse(result.hasDuplicateAmbiguity)
    }

    @Test
    fun `rejects unsupported unverified oversized and APK active entries`() {
        val invalid = listOf(
            validEntry(internalName = "FutureApi", apiVersion = 2),
            validEntry(internalName = "NoHash", fileHash = null),
            validEntry(internalName = "BadHash", fileHash = "9036525a"),
            validEntry(internalName = "NoSize", fileSize = null),
            validEntry(internalName = "TooLarge", fileSize = MAX_EXTERNAL_EXTENSION_BYTES + 1),
            validEntry(internalName = "ApkAlias", url = "Provider.apk"),
            validEntry(internalName = "NoAuthor", authors = emptyList()),
            validEntry(internalName = "NoSource", repositoryUrl = null),
        )

        val result = ExternalRepositoryContract.preparePlugins(invalid, PLUGINS_URL)

        assertTrue(result.accepted.isEmpty())
        assertEquals(invalid.size, result.rejected.size)
        assertEquals(invalid.map { it.internalName }, result.rejected.map { it.internalName })
        assertTrue(
            result.rejected.all {
                it.classification == ExternalPluginRejectionClassification.ACTIVE_INVALID
            },
        )
        assertTrue(result.hasActiveInvalidEntries)
    }

    @Test
    fun `classifies duplicate internal names and removes duplicate inactive ambiguity`() {
        val activeDuplicates = ExternalRepositoryContract.preparePlugins(
            listOf(validEntry(), validEntry(name = "Second")),
            PLUGINS_URL,
        )
        val inactiveDuplicates = ExternalRepositoryContract.preparePlugins(
            listOf(
                validEntry(internalName = "Retired", status = 0),
                validEntry(internalName = "Retired", status = 0),
            ),
            PLUGINS_URL,
        )

        assertEquals(listOf("Provider"), activeDuplicates.accepted.map { it.name })
        assertEquals(
            ExternalPluginRejectionClassification.DUPLICATE_AMBIGUITY,
            activeDuplicates.rejected.single().classification,
        )
        assertTrue(activeDuplicates.hasDuplicateAmbiguity)
        assertEquals(emptySet<String>(), inactiveDuplicates.explicitlyInactiveInternalNames)
        assertTrue(inactiveDuplicates.hasDuplicateAmbiguity)
    }

    private fun validEntry(
        name: String = "Provider",
        internalName: String = "Provider",
        version: Int = 1,
        apiVersion: Int = 1,
        status: Int = 1,
        authors: List<String>? = listOf("Author"),
        url: String = "Provider.cs3",
        fileSize: Long? = 1024,
        fileHash: String? = "sha256-${"ab".repeat(32)}",
        language: String? = null,
        repositoryUrl: String? = "https://github.com/example/extensions",
    ) = ExternalPluginEntry(
        name = name,
        internalName = internalName,
        version = version,
        apiVersion = apiVersion,
        status = status,
        authors = authors,
        url = url,
        fileSize = fileSize,
        fileHash = fileHash,
        language = language,
        repositoryUrl = repositoryUrl,
    )

    private companion object {
        const val PLUGINS_URL = "https://example.test/repos/builds/plugins.json"
    }
}
