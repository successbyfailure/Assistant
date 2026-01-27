package com.sbf.assistant

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton provider for OkHttpClient instances.
 * Sharing a single client improves connection pooling and reduces resource usage.
 */
object HttpClientProvider {

    /**
     * Default client with standard timeouts.
     * Use for most API calls.
     */
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Client with extended timeouts for large downloads.
     * Use for model downloads and other large file transfers.
     */
    val download: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Client with longer read timeout for streaming responses.
     * Use for SSE streams and long-running API calls.
     */
    val streaming: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
