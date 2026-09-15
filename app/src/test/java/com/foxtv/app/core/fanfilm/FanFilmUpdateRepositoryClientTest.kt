package com.foxtv.app.core.fanfilm

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Index parsing decides what FOX.TV is willing to install, so the filter, repository layout,
 * and version extraction are asserted against real Kodi repository shapes.
 */
class FanFilmUpdateRepositoryClientTest {

    private val client = FanFilmUpdateRepositoryClient(OkHttpClient())

    private val fanFilmIndex = """
        <?xml version='1.0' encoding='utf-8'?>
        <addons>
        <addon id="plugin.video.fanfilm" name="FanFilm" version="2026.09.06.1" provider-name="FF Team">
            <requires>
                <import addon="xbmc.python" version="3.0.0"/>
                <import addon="script.module.requests"/>
                <import addon="plugin.video.youtube" optional="true"/>
                <import addon="script.module.resolveurl"/>
                <import addon="script.fanfilm.media" version="2026.06.15.0"/>
            </requires>
            <extension point="xbmc.python.pluginsource" library="default.py">
                <provides>video</provides>
            </extension>
        </addon>
        <addon id="repository.fanfilm" name="FanFilm Repository" version="1.0" provider-name="FF Team">
            <extension point="xbmc.addon.repository" name="FanFilm"/>
        </addon>
        </addons>
    """.trimIndent()

    @Test
    fun `parses the addons the repository is allowed to update`() {
        val addons = client.parseIndex(fanFilmIndex, FanFilmUpdateRepositoryClient.FANFILM_STABLE)

        assertEquals(1, addons.size)
        val fanfilm = addons.single()
        assertEquals("plugin.video.fanfilm", fanfilm.id)
        assertEquals("2026.09.06.1", fanfilm.version)
    }

    @Test
    fun `ignores addons outside the allow list`() {
        // repository.fanfilm is in the index but FOX.TV has no runtime contract with it, so
        // it must never be offered as an update.
        val addons = client.parseIndex(fanFilmIndex, FanFilmUpdateRepositoryClient.FANFILM_STABLE)
        assertFalse(addons.any { it.id == "repository.fanfilm" })
    }

    @Test
    fun `builds the FanFilm package and checksum urls`() {
        val repository = FanFilmUpdateRepositoryClient.FANFILM_STABLE
        val addon = client.parseIndex(fanFilmIndex, repository).single()
        val packageBase = repository.packageBaseUrl

        assertEquals(
            "$packageBase/plugin.video.fanfilm/plugin.video.fanfilm-2026.09.06.1.zip",
            addon.archiveUrl,
        )
        assertEquals("${addon.archiveUrl}.sha256", addon.checksumUrl)
        assertEquals("${repository.indexBaseUrl}/addons.xml", client.indexUrl(repository))
    }

    @Test
    fun `ResolveURL keeps the repository index outside the package zips directory`() {
        val repository = FanFilmUpdateRepositoryClient.RESOLVEURL
        val resolveUrlIndex = """
            <addons>
              <addon id="script.module.resolveurl" name="ResolveURL" version="5.1.208">
                <requires><import addon="script.module.kodi-six"/></requires>
              </addon>
            </addons>
        """.trimIndent()

        val addon = client.parseIndex(resolveUrlIndex, repository).single()

        assertEquals(
            "https://raw.githubusercontent.com/Gujal00/smrzips/master/addons.xml",
            client.indexUrl(repository),
        )
        assertFalse(client.indexUrl(repository).contains("/zips/addons.xml"))
        assertEquals(
            "https://raw.githubusercontent.com/Gujal00/smrzips/master/zips/" +
                "script.module.resolveurl/script.module.resolveurl-5.1.208.zip",
            addon.archiveUrl,
        )
        assertEquals("${addon.archiveUrl}.sha256", addon.checksumUrl)
    }

    @Test
    fun `parses requires including the optional flag`() {
        val addon = client.parseIndex(fanFilmIndex, FanFilmUpdateRepositoryClient.FANFILM_STABLE)
            .single()

        val youtube = addon.requires.single { it.addonId == "plugin.video.youtube" }
        assertTrue(youtube.optional)

        val resolveUrl = addon.requires.single { it.addonId == "script.module.resolveurl" }
        assertFalse(resolveUrl.optional)

        val media = addon.requires.single { it.addonId == "script.fanfilm.media" }
        assertEquals("2026.06.15.0", media.minVersion)
    }

    @Test
    fun `an index without the expected addon yields nothing rather than failing`() {
        val addons = client.parseIndex(
            "<addons><addon id=\"other.addon\" version=\"1.0\"/></addons>",
            FanFilmUpdateRepositoryClient.RESOLVEURL,
        )
        assertTrue(addons.isEmpty())
    }
}
