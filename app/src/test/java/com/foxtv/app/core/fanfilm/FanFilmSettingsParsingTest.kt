package com.foxtv.app.core.fanfilm

import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings parsing and dependency evaluation.
 *
 * The declaration comes from the addon's `resources/settings.xml` via the Python parser;
 * these tests pin the Kotlin half — the shape it expects, and the `<dependency>` logic that
 * decides whether a row is interactive. Getting that wrong means offering the user a toggle
 * the addon will ignore.
 */
class FanFilmSettingsParsingTest {

    private val repository = FanFilmSettingsRepository(runtime = mockk(relaxed = true))

    private val payload = JSONObject(
        """
        {
          "addonId": "plugin.video.fanfilm",
          "addonVersion": "2026.09.06.1",
          "categories": [
            {
              "id": "general", "label": "General", "help": "",
              "groups": [
                {
                  "id": "1", "label": "General",
                  "settings": [
                    {"id":"hosts.mode","type":"integer","label":"Default action","help":"",
                     "default":"0","level":0,
                     "control":{"type":"list","format":"string"},
                     "options":[{"label":"Dialog","value":"0"},{"label":"Auto Play","value":"2"}],
                     "minimum":null,"maximum":null,"step":null,
                     "dependencies":{},"secret":false,"action":false},
                    {"id":"isa.enabled","type":"boolean","label":"Use InputStream Adaptive",
                     "help":"","default":"false","level":0,
                     "control":{"type":"toggle","format":""},"options":[],
                     "minimum":null,"maximum":null,"step":null,
                     "dependencies":{},"secret":false,"action":false},
                    {"id":"trakt.rating","type":"boolean","label":"Trakt ratings","help":"",
                     "default":"true","level":0,
                     "control":{"type":"toggle","format":""},"options":[],
                     "minimum":null,"maximum":null,"step":null,
                     "dependencies":{},"secret":false,"action":false},
                    {"id":"ratings.combined","type":"boolean","label":"Combined ratings",
                     "help":"","default":"false","level":0,
                     "control":{"type":"toggle","format":""},"options":[],
                     "minimum":null,"maximum":null,"step":null,
                     "dependencies":{"enable":[{"op":"or","operands":[
                        {"op":"is","setting":"trakt.rating","value":"true"},
                        {"op":"is","setting":"tmdb.rating","value":"true"}]}]},
                     "secret":false,"action":false},
                    {"id":"tmdb.rating","type":"boolean","label":"TMDB ratings","help":"",
                     "default":"false","level":0,
                     "control":{"type":"toggle","format":""},"options":[],
                     "minimum":null,"maximum":null,"step":null,
                     "dependencies":{},"secret":false,"action":false},
                    {"id":"premiumize.api_key","type":"string","label":"Premiumize API key",
                     "help":"","default":"","level":0,
                     "control":{"type":"edit","format":"string"},"options":[],
                     "minimum":null,"maximum":null,"step":null,
                     "dependencies":{},"secret":true,"action":false},
                    {"id":"cache.clear","type":"action","label":"Clear cache","help":"",
                     "default":"","level":0,
                     "control":{"type":"button","format":"action"},"options":[],
                     "minimum":null,"maximum":null,"step":null,
                     "dependencies":{},"secret":false,"action":true},
                    {"id":"scrapers.timeout.1","type":"integer","label":"Scraper timeout",
                     "help":"seconds","default":"30","level":0,
                     "control":{"type":"slider","format":"integer"},"options":[],
                     "minimum":5.0,"maximum":120.0,"step":5.0,
                     "dependencies":{},"secret":false,"action":false}
                  ]
                }
              ]
            }
          ],
          "values": {
            "hosts.mode": "2",
            "isa.enabled": "true",
            "trakt.rating": "false",
            "tmdb.rating": "false",
            "ratings.combined": "false",
            "premiumize.api_key": "secret-value",
            "scrapers.timeout.1": "45"
          },
          "valuesPath": "/data/user/0/com.foxtv.app/files/fanfilm/userdata/addon_data/plugin.video.fanfilm/settings.xml"
        }
        """.trimIndent()
    )

    private val snapshot = repository.parse(payload)

    @Test
    fun `parses the category group setting hierarchy`() {
        assertEquals("plugin.video.fanfilm", snapshot.addonId)
        assertEquals("2026.09.06.1", snapshot.addonVersion)
        assertEquals(1, snapshot.categories.size)
        assertEquals(1, snapshot.categories.single().groups.size)
        assertEquals(8, snapshot.categories.single().groups.single().settings.size)
    }

    @Test
    fun `maps Kodi setting types`() {
        val definitions = snapshot.definitions
        assertEquals(
            FanFilmSettingsRepository.Definition.Type.INTEGER,
            definitions["hosts.mode"]!!.type,
        )
        assertEquals(
            FanFilmSettingsRepository.Definition.Type.BOOLEAN,
            definitions["isa.enabled"]!!.type,
        )
        assertEquals(
            FanFilmSettingsRepository.Definition.Type.STRING,
            definitions["premiumize.api_key"]!!.type,
        )
        assertEquals(
            FanFilmSettingsRepository.Definition.Type.ACTION,
            definitions["cache.clear"]!!.type,
        )
    }

    @Test
    fun `parses enum options with their labels`() {
        val options = snapshot.definitions["hosts.mode"]!!.options
        assertEquals(2, options.size)
        assertEquals("Dialog", options.first().label)
        assertEquals("2", options.last().value)
    }

    @Test
    fun `parses slider constraints`() {
        val slider = snapshot.definitions["scrapers.timeout.1"]!!
        assertEquals(5.0, slider.minimum!!, 0.001)
        assertEquals(120.0, slider.maximum!!, 0.001)
        assertEquals(5.0, slider.step!!, 0.001)
    }

    @Test
    fun `reads current values with typed accessors and falls back to defaults`() {
        assertEquals("2", snapshot.value("hosts.mode"))
        assertEquals(2, snapshot.integer("hosts.mode"))
        assertTrue(snapshot.boolean("isa.enabled"))
        assertFalse(snapshot.boolean("trakt.rating"))
        assertEquals(45, snapshot.integer("scrapers.timeout.1"))
        // Declared but absent from values: the declared default applies.
        assertEquals("", snapshot.value("cache.clear"))
    }

    @Test
    fun `an or dependency disables a setting until one condition holds`() {
        assertFalse(
            "no rating source is on, so the combined toggle must be disabled",
            snapshot.isEnabled("ratings.combined"),
        )

        val withTrakt = snapshot.copy(values = snapshot.values + ("trakt.rating" to "true"))
        assertTrue(withTrakt.isEnabled("ratings.combined"))
    }

    @Test
    fun `a setting without dependencies is always enabled and visible`() {
        assertTrue(snapshot.isEnabled("isa.enabled"))
        assertTrue(snapshot.isVisible("isa.enabled"))
    }

    @Test
    fun `credentials are flagged as secret`() {
        assertTrue(snapshot.definitions["premiumize.api_key"]!!.isSecret)
        assertFalse(snapshot.definitions["isa.enabled"]!!.isSecret)
    }

    @Test
    fun `actions are flagged so the UI renders a button not a value`() {
        assertTrue(snapshot.definitions["cache.clear"]!!.isAction)
        assertFalse(snapshot.definitions["hosts.mode"]!!.isAction)
    }

    @Test
    fun `an unknown dependency operator leaves the setting enabled`() {
        // Kodi is permissive here; silently disabling a working setting would be worse than
        // showing one whose condition we could not evaluate.
        val exotic = snapshot.copy(
            categories = listOf(
                snapshot.categories.single().copy(
                    groups = listOf(
                        snapshot.categories.single().groups.single().copy(
                            settings = listOf(
                                snapshot.definitions["isa.enabled"]!!.copy(
                                    dependencies = mapOf(
                                        "enable" to listOf(
                                            FanFilmSettingsRepository.Definition.Condition(
                                                operator = "matches-regex",
                                                setting = "hosts.mode",
                                                value = ".*",
                                                operands = emptyList(),
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        assertTrue(exotic.isEnabled("isa.enabled"))
    }
}
