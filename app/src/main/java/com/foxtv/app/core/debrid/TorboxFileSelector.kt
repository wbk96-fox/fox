package com.foxtv.app.core.debrid

import com.foxtv.app.data.remote.dto.TorboxTorrentFileDto
import com.foxtv.app.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorboxFileSelector @Inject constructor() {

    /**
     * Pick the file to request a download link for.
     *
     * [StreamClientResolve.fileIdx] is the source addon's list offset, exactly as it is for
     * Real-Debrid and the common stream contract. Torbox's own file id is a different value:
     * it is read from the selected [TorboxTorrentFileDto] and only then passed to
     * `requestDownloadLink(fileId = …)`. Treating `fileIdx` as a Torbox id selected the wrong
     * episode whenever an offset happened to equal another file's provider id.
     */
    fun selectFile(
        files: List<TorboxTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?
    ): TorboxTorrentFileDto? {
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
            name = { it.shortName?.takeIf { p -> p.isNotBlank() } ?: it.name?.takeIf { p -> p.isNotBlank() } ?: it.displayName() },
            size = { it.size ?: 0L }
        )
    }
}
