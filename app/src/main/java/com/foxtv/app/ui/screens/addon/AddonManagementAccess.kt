package com.foxtv.app.ui.screens.addon

import com.foxtv.app.core.server.AddonWebConfigMode
import com.foxtv.app.domain.model.ExperienceMode
import com.foxtv.app.domain.model.UserProfile

internal object AddonManagementAccess {

    fun isReadOnly(profile: UserProfile?): Boolean {
        return profile?.let { !it.isPrimary && it.usesPrimaryAddons } == true
    }

    /**
     * What the phone-paired web configurator is allowed to change.
     *
     * - A secondary profile that inherits the primary profile's addons may only touch its own
     *   collections; the addon set is not its to edit.
     * - The advanced experience exposes the full surface: addons, catalog order and collections.
     * - The essential experience deliberately hides catalog management, so it stays on
     *   [AddonWebConfigMode.ADDONS_ONLY].
     *
     * [experienceMode] used to be accepted and ignored, which made [AddonWebConfigMode.FULL]
     * unreachable and silently denied catalog edits to advanced users.
     */
    fun webConfigMode(
        profile: UserProfile?,
        experienceMode: ExperienceMode = ExperienceMode.ADVANCED
    ): AddonWebConfigMode {
        return when {
            isReadOnly(profile) -> AddonWebConfigMode.COLLECTIONS_ONLY
            experienceMode == ExperienceMode.ADVANCED -> AddonWebConfigMode.FULL
            else -> AddonWebConfigMode.ADDONS_ONLY
        }
    }
}
