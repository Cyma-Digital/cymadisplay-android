package com.cyma.videoloop.di

import com.cyma.videoloop.BuildConfig
import com.cyma.videoloop.data.api.CymaApi
import com.cyma.videoloop.data.identity.DeviceIdentityRepository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val BASE_URL = BuildConfig.API_BASE_URL

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun authInterceptor(identity: DeviceIdentityRepository): Interceptor = Interceptor { chain ->
        // Auth token is only available after pairing; omit header until then
        val authToken = runBlocking { identity.getAuthToken() }
        val request = chain.request().newBuilder()
            .apply { authToken?.let { header("Authorization", "Bearer $it") } }
            .build()
        chain.proceed(request)
    }

    @Provides
    @Singleton
    fun okHttpClient(authInterceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            // 30 s covers the 8-second server-hold in the long-poll pairing endpoint
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun cymaApi(retrofit: Retrofit): CymaApi = retrofit.create(CymaApi::class.java)
}
