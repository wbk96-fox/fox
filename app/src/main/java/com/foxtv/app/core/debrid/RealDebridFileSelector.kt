package com.foxtv.app.core.debrid

import com.foxtv.app.data.remote.dto.RealDebridTorrentFileDto
import com.foxtv.app.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridFileSelector @Inject constructor() {
    fun selectFile(
        files: List<RealDebridTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?
    ): RealDebridTorrentFileDto? {
        if (files.isEmpty()) return null

        val targetSeason = season ?: resolve.season
        val targetEpisode = episode ?: resolve.episode

        return DebridMediaMatcher.pickMediaFile(
            files = files,
            fileIndex = resolve.fileIdx,
            filename = resolve.filename ?: resolve.torrentName,
            season = targetSeason,
            episode = targetEpisode,
            episodeTitle = resolve.title,
            name = { it.path?.takeIf { p -> p.isNotBlank() } ?: it.displayName() },
            size = { it.bytes ?: 0L }
        )
    }
}
