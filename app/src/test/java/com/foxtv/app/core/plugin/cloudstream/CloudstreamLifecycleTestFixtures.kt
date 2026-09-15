package com.foxtv.app.core.plugin.cloudstream

import com.foxtv.app.domain.model.ExternalPluginEntry
import com.foxtv.app.domain.model.PluginRepository
import com.foxtv.app.domain.model.RepositoryType
import com.foxtv.app.domain.model.ScraperInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object CloudstreamLifecycleTestFixtures {
    fun repository(
        id: String = "external-repo",
        url: String = "https://repo.example/plugins.json",
    ) = PluginRepository(
        id = id,
        name = "External Repository",
        url = url,
        description = "Verified test repository",
        enabled = true,
        lastUpdated = 1L,
        scraperCount = 1,
        type = RepositoryType.EXTERNAL_DEX,
    )

    fun plugin(
        internalName: String,
        artifact: ByteArray = validCs3("example.${internalName}Plugin"),
    ) = ExternalPluginEntry(
        name = internalName,
        internalName = internalName,
        version = 1,
        apiVersion = 1,
        status = 1,
        authors = listOf("FOX.TV test"),
        tvTypes = listOf("Movie", "TvSeries"),
        url = "https://repo.example/$internalName.cs3",
        fileSize = artifact.size.toLong(),
        fileHash = "sha256-${sha256(artifact)}",
        repositoryUrl = "https://repo.example/source",
    )

    fun scraper(
        id: String,
        repositoryId: String,
        enabled: Boolean = true,
        manifestEnabled: Boolean = true,
        type: RepositoryType = RepositoryType.EXTERNAL_DEX,
    ) = ScraperInfo(
        id = id,
        repositoryId = repositoryId,
        name = id.substringAfterLast(':'),
        description = "Test scraper",
        version = "1",
        filename = "https://repo.example/${id.substringAfterLast(':')}.cs3",
        supportedTypes = listOf("movie", "tv"),
        enabled = enabled,
        manifestEnabled = manifestEnabled,
        logo = null,
        contentLanguage = emptyList(),
        formats = null,
        type = type,
    )

    suspend fun prepared(
        scraperId: String,
        targetFile: File,
        artifact: ByteArray = validCs3("example.${scraperId.replace(':', '_')}Plugin"),
    ): ExternalExtensionLoader.PreparedExternalExtension {
        val staged = ExternalExtensionInstaller.stageAndValidate(
            targetFile = targetFile,
            source = ByteArrayInputStream(artifact),
            declaredContentLength = artifact.size.toLong(),
            spec = ExternalExtensionArtifactSpec(
                expectedSize = artifact.size.toLong(),
                expectedSha256 = sha256(artifact),
            ),
            loadabilityValidator = { true },
        )
        return ExternalExtensionLoader.PreparedExternalExtension(
            scraperId = scraperId,
            stagedArtifact = staged,
            byteCount = artifact.size.toLong(),
        )
    }

    fun validCs3(pluginClassName: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """{"name":"Provider","version":1,"pluginClassName":"$pluginClassName"}"""
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(
                ByteArray(128).also { dex ->
                    byteArrayOf(
                        'd'.code.toByte(),
                        'e'.code.toByte(),
                        'x'.code.toByte(),
                        '\n'.code.toByte(),
                        '0'.code.toByte(),
                        '3'.code.toByte(),
                        '5'.code.toByte(),
                        0,
                    ).copyInto(dex)
                },
            )
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    fun assertNoTransactionFiles(directory: File) {
        check(
            directory.listFiles().orEmpty().none {
                it.name.endsWith(".backup") || it.name.endsWith(".staged")
            },
        ) { "Transaction file leaked in ${directory.absolutePath}" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
