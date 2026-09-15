package com.foxtv.app.data.remote.api

import com.foxtv.app.data.remote.dto.AddonManifestDto
import com.foxtv.app.data.remote.dto.CatalogResponseDto
import com.foxtv.app.data.remote.dto.MetaResponseDto
import com.foxtv.app.data.remote.dto.StreamResponseDto
import com.foxtv.app.data.remote.dto.SubtitleResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface AddonApi {

    @GET
    suspend fun getManifest(@Url manifestUrl: String): Response<AddonManifestDto>

    @GET
    suspend fun getCatalog(@Url catalogUrl: String): Response<CatalogResponseDto>

    @GET
    suspend fun getMeta(@Url metaUrl: String): Response<MetaResponseDto>

    @GET
    suspend fun getStreams(@Url streamUrl: String): Response<StreamResponseDto>

    @GET
    suspend fun getSubtitles(@Url subtitleUrl: String): Response<SubtitleResponseDto>
}
