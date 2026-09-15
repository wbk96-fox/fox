package com.foxtv.app.core.profile

interface ProfileScopedCredentialStore {
    fun removeProfile(profileId: Int)
    fun clearAllProfiles()
}
