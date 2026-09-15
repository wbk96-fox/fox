package com.foxtv.app.updater

import com.foxtv.app.BuildConfig
import com.foxtv.app.data.remote.api.GitHubReleaseApi
import com.foxtv.app.updater.model.AppUpdate
import javax.inject.Inject
import javax.inject.Singleton

internal class NoEligibleUpdateException(channel: UpdateChannel) :
    IllegalStateException("No compatible APK release found for ${channel.storedValue} channel")

/** Raised when no release repository was configured for this build. */
internal class UpdaterNotConfiguredException :
    IllegalStateException("In-app updates are not configured for this build")

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi
) {

    /**
     * Whether release coordinates were supplied at build time.
     *
     * FOX.TV ships from a private repository, so `FOXTV_UPDATE_GITHUB_OWNER` /
     * `FOXTV_UPDATE_GITHUB_REPO` are configured in `local.properties` (or CI env).
     * When they are absent the updater must stay silent rather than query a
     * foreign repository and offer an APK with a different applicationId.
     */
    val isConfigured: Boolean =
        BuildConfig.GITHUB_OWNER.isNotBlank() && BuildConfig.GITHUB_REPO.isNotBlank()

    suspend fun getLatestUpdate(channel: UpdateChannel): Result<AppUpdate> {
        if (!isConfigured) {
            return Result.failure(UpdaterNotConfiguredException())
        }
        return runCatching {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val response = gitHubReleaseApi.getReleases(owner = owner, repo = repo)
            val releases = if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                response.body().orEmpty()
            } else {
                val latest = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
                if (latest.isSuccessful && latest.body() != null) {
                    listOf(latest.body()!!)
                } else {
                    error("GitHub API error: ${response.code()}")
                }
            }
            val releaseWithAsset = ReleaseSelector
                .eligibleReleases(releases, channel)
                .firstNotNullOfOrNull { release ->
                    AbiSelector.chooseBestApkAsset(release.assets)?.let { asset ->
                        release to asset
                    }
                }
                ?: throw NoEligibleUpdateException(channel)
            val (dto, asset) = releaseWithAsset

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            AppUpdate(
                tag = tag,
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size
            )
        }
    }
}
