package com.foxtv.app.core.fanfilm

import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Version comparison and package validation, the two decisions that determine whether an
 * update is offered and whether it is allowed to touch the installed tree.
 */
class FanFilmVersionComparisonTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val installer = mockk<FanFilmAssetInstaller>(relaxed = true)

    private val updater = FanFilmAddonUpdater(
        paths = mockk(relaxed = true),
        installer = installer,
        client = mockk(relaxed = true),
        runtime = mockk(relaxed = true),
        state = mockk(relaxed = true),
    )

    @Test
    fun `compares numeric segments numerically not lexically`() {
        // The bug a string comparison has: "2026.09.10" < "2026.09.9" alphabetically.
        assertTrue(updater.isNewer("2026.09.10", "2026.09.9"))
        assertFalse(updater.isNewer("2026.09.9", "2026.09.10"))
    }

    @Test
    fun `recognises FanFilm date versions`() {
        assertTrue(updater.isNewer("2026.09.06.1", "2026.08.31.2"))
        assertTrue(updater.isNewer("2026.09.06.2", "2026.09.06.1"))
        assertFalse(updater.isNewer("2026.09.06.1", "2026.09.06.1"))
    }

    @Test
    fun `recognises ResolveURL semantic versions`() {
        assertTrue(updater.isNewer("5.1.208", "5.1.207"))
        assertTrue(updater.isNewer("5.2.0", "5.1.999"))
        assertFalse(updater.isNewer("5.1.207", "5.1.208"))
    }

    @Test
    fun `treats a longer version with extra segments as newer`() {
        assertTrue(updater.isNewer("0.8.6.1", "0.8.6"))
        assertFalse(updater.isNewer("0.8.6", "0.8.6.1"))
    }

    @Test
    fun `handles Kodi distro suffixes`() {
        assertEquals(0, updater.compareVersions("0.8.6+matrix.1", "0.8.6+matrix.1"))
        assertTrue(updater.isNewer("0.8.7+matrix.1", "0.8.6+matrix.1"))
    }

    // ── validate() ──────────────────────────────────────────────────────────

    private fun remote(id: String, version: String, requires: List<String> = emptyList()) =
        FanFilmUpdateRepositoryClient.RemoteAddon(
            id = id,
            version = version,
            repositoryId = "test",
            archiveUrl = "https://example.invalid/$id-$version.zip",
            checksumUrl = "https://example.invalid/$id-$version.zip.sha256",
            requires = requires.map {
                FanFilmUpdateRepositoryClient.RemoteAddon.Requirement(it, "", optional = false)
            },
        )

    private fun stagedFanFilm(
        version: String = "2026.09.06.1",
        declaredId: String = "plugin.video.fanfilm",
        includeEntryPoints: Boolean = true,
    ): File {
        val root = temporaryFolder.newFolder("staged-${System.nanoTime()}", "plugin.video.fanfilm")
        File(root, "addon.xml").writeText(
            """<addon id="$declaredId" name="FanFilm" version="$version"/>"""
        )
        if (includeEntryPoints) {
            File(root, "default.py").writeText("")
            File(root, "service.py").writeText("")
            File(root, "lib/fake").mkdirs()
            File(root, "lib/fake/xbmc.py").writeText("")
            File(root, "resources").mkdirs()
            File(root, "resources/settings.xml").writeText("<settings/>")
        }
        return root
    }

    @Test
    fun `accepts a well formed package whose dependencies are installed`() {
        every { installer.readInstalledVersions() } returns mapOf(
            "script.module.resolveurl" to "5.1.208",
            "script.fanfilm.media" to "2026.06.15.0",
        )

        val error = updater.validate(
            stagedFanFilm(),
            remote(
                "plugin.video.fanfilm",
                "2026.09.06.1",
                requires = listOf("script.module.resolveurl", "script.fanfilm.media"),
            ),
        )
        assertNull(error)
    }

    @Test
    fun `rejects a package whose addon xml declares a different id`() {
        every { installer.readInstalledVersions() } returns emptyMap()
        val error = updater.validate(
            stagedFanFilm(declaredId = "plugin.video.somethingelse"),
            remote("plugin.video.fanfilm", "2026.09.06.1"),
        )
        assertNotNull(error)
        assertTrue(error is FanFilmError.Update)
    }

    @Test
    fun `rejects a package whose version does not match the index`() {
        every { installer.readInstalledVersions() } returns emptyMap()
        val error = updater.validate(
            stagedFanFilm(version = "2026.01.01.0"),
            remote("plugin.video.fanfilm", "2026.09.06.1"),
        )
        assertNotNull(error)
    }

    @Test
    fun `rejects a package missing an entry point the runtime calls`() {
        every { installer.readInstalledVersions() } returns emptyMap()
        val error = updater.validate(
            stagedFanFilm(includeEntryPoints = false),
            remote("plugin.video.fanfilm", "2026.09.06.1"),
        )
        assertNotNull(error)
        assertTrue(error!!.technicalDetail.contains("missing"))
    }

    @Test
    fun `rejects a package that introduces an unmet mandatory dependency`() {
        every { installer.readInstalledVersions() } returns emptyMap()
        val error = updater.validate(
            stagedFanFilm(),
            remote(
                "plugin.video.fanfilm",
                "2026.09.06.1",
                requires = listOf("script.module.brandnewthing"),
            ),
        )
        assertNotNull(error)
        assertTrue(error!!.technicalDetail.contains("script.module.brandnewthing"))
    }

    @Test
    fun `ignores xbmc python and runtime provided dependencies`() {
        every { installer.readInstalledVersions() } returns emptyMap()
        val error = updater.validate(
            stagedFanFilm(),
            remote(
                "plugin.video.fanfilm",
                "2026.09.06.1",
                requires = listOf(
                    "xbmc.python",
                    "script.module.requests",
                    "script.module.six",
                    "script.module.pyqrcode",
                ),
            ),
        )
        assertNull(error)
    }

    @Test
    fun `rejects a package with no addon directory inside`() {
        every { installer.readInstalledVersions() } returns emptyMap()
        val missing = File(temporaryFolder.root, "nope/plugin.video.fanfilm")
        val error = updater.validate(missing, remote("plugin.video.fanfilm", "2026.09.06.1"))
        assertNotNull(error)
    }
}
