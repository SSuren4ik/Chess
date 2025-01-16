package com.chess

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface LichessApiService {
    @POST("api/challenge/ai")
    suspend fun startGameAgainstBot(
        @Header("Authorization") authHeader: String,
        @Body request: StartGameRequest,
    ): LichessGameResponse

    @POST("api/bot/game/{gameId}/move/{move}")
    suspend fun makeMove(
        @Header("Authorization") authHeader: String,
        @Path("gameId") gameId: String,
        @Path("move") move: String,
    ): LichessMoveResponse

    object RetrofitInstance {
        private const val BASE_URL = "https://lichess.org/"

        fun create(): LichessApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(LichessApiService::class.java)
        }
    }
}
