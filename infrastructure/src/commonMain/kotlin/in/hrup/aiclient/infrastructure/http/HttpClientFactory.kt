package `in`.hrup.aiclient.infrastructure.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/** Creates a client owned by the caller. */
fun createHttpClient(configure: HttpClientConfig<*>.() -> Unit = {}): HttpClient =
    createPlatformHttpClient {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
        configure()
    }

internal expect fun createPlatformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient
