package com.foxtv.app.ui.screens.addon

import com.foxtv.app.core.server.AddonWebConfigMode
import com.foxtv.app.domain.model.ExperienceMode
import com.foxtv.app.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonManagementAccessTest {

    @Test
    fun `secondary profile using primary addons is read only and collections only`() {
        val profile = UserProfile(
            id = 2,
            name = "Secondary",
            avatarColorHex = "#FFFFFF",
            usesPrimaryAddons = true
        )

        assertTrue(AddonManagementAccess.isReadOnly(profile))
        assertEquals(
            AddonWebConfigMode.COLLECTIONS_ONLY,
            AddonManagementAccess.webConfigMode(profile, ExperienceMode.ESSENTIAL)
        )
    }

    @Test
    fun `primary profile keeps full addon management`() {
        val profile = UserProfile(
            id = 1,
            name = "Primary",
            avatarColorHex = "#000000",
            usesPrimaryAddons = true
        )

        assertFalse(AddonManagementAccess.isReadOnly(profile))
        assertEquals(
            AddonWebConfigMode.FULL,
            AddonManagementAccess.webConfigMode(profile, ExperienceMode.ADVANCED)
        )
    }

    @Test
    fun `primary essential profile uses addons only web management`() {
        val profile = UserProfile(
            id = 1,
            name = "Primary",
            avatarColorHex = "#000000",
            usesPrimaryAddons = false
        )

        assertFalse(AddonManagementAccess.isReadOnly(profile))
        assertEquals(
            AddonWebConfigMode.ADDONS_ONLY,
            AddonManagementAccess.webConfigMode(profile, ExperienceMode.ESSENTIAL)
        )
    }
}
