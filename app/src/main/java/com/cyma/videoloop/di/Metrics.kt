package com.cyma.videoloop.di

import javax.inject.Qualifier

/**
 * Marks the [retrofit2.Retrofit] instance pointed at the metrics host
 * (`BuildConfig.METRICS_BASE_URL`), which is a different backend from the
 * playlist API. Needed because [NetworkModule] already binds `Retrofit`,
 * `OkHttpClient` and `Interceptor` unqualified.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Metrics
