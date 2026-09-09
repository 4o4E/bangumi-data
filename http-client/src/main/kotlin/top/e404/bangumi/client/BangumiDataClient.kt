package top.e404.bangumi.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import top.e404.bangumi.api.ApiEnvelope
import top.e404.bangumi.api.CatalogCharacter
import top.e404.bangumi.api.CatalogPage
import top.e404.bangumi.api.CatalogStatus
import top.e404.bangumi.api.CharacterGender
import top.e404.bangumi.api.FamiliarityTier
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

    suspend fun getCatalogStatus(): CatalogStatus =
        authenticatedGet("$apiBaseUrl/catalog/status").body<ApiEnvelope<CatalogStatus>>().data

    suspend fun getCharacters(
        gender: CharacterGender,
        tiers: Set<FamiliarityTier> = setOf(FamiliarityTier.CORE, FamiliarityTier.FAMILIAR),
        offset: Int = 0,
        limit: Int = 500,
    ): CatalogPage<CatalogCharacter> = authenticatedGet("$apiBaseUrl/catalog/characters") {
        parameter("gender", gender.name)
        parameter("tiers", tiers.joinToString(",", transform = FamiliarityTier::name))
        parameter("offset", offset)
        parameter("limit", limit)
    }.body<ApiEnvelope<CatalogPage<CatalogCharacter>>>().data

    private suspend inline fun authenticatedGet(
        url: String,
        crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ) = http.get(url) {
        bearerAuth(token)
        block()
    }

    override fun close() {
        http.close()
    }
}
