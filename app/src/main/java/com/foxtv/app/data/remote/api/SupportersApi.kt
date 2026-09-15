package com.foxtv.app.data.remote.api

import com.foxtv.app.data.remote.dto.SupportersWallResponseDto
import retrofit2.Response
import retrofit2.http.GET

interface SupportersApi {
    @GET("api/supporters/wall")
    suspend fun getSupportersWall(): Response<SupportersWallResponseDto>
}
