package com.foxtv.app.core.cloud

import com.foxtv.app.core.debrid.DebridProvider

interface CloudLibraryProviderApi {
    val provider: DebridProvider

    suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>>

    suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult
}
