package top.e404.bangumi.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import top.e404.bangumi.api.ApiEnvelope
import top.e404.bangumi.api.ServiceInfo

class BangumiDataClient(
    baseUrl: String,
    private val token: String,
    engine: HttpClientEngine = CIO.create(),
) : AutoCloseable {
    private val apiBaseUrl = baseUrl.trimEnd('/') + "/api/v1"
    private val http = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun getServiceInfo(): ServiceInfo =
        http.get("$apiBaseUrl/service") {
            bearerAuth(token)
        }.body<ApiEnvelope<ServiceInfo>>().data

    override fun close() {
        http.close()
    }
}
