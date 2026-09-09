package top.e404.bangumi.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import top.e404.bangumi.api.ApiEnvelope
import top.e404.bangumi.api.ApiError
import top.e404.bangumi.api.BANGUMI_DATA_API_VERSION
import top.e404.bangumi.api.HealthResponse
import top.e404.bangumi.api.ServiceInfo
import top.e404.bangumi.api.CharacterGender
import top.e404.bangumi.api.FamiliarityTier
import top.e404.bangumi.server.catalog.CatalogReader
import java.security.MessageDigest

fun Application.configureApplication(
    config: ServerConfig,
    catalog: CatalogReader = EmptyCatalogReader,
    launchSync: (Boolean) -> Boolean = { false },
) {
    val applicationLog = environment.log
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(StatusPages) {
        exception<CancellationException> { _, cause -> throw cause }
        exception<Throwable> { call, cause ->
            applicationLog.error("请求处理失败", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(code = "internal_error", message = "服务内部错误"),
            )
        }
    }
    install(Authentication) {
        bearer("api-token") {
            authenticate { credential ->
                if (constantTimeEquals(credential.token, config.token)) {
                    UserIdPrincipal("service-client")
                } else {
                    null
                }
            }
        }
    }

    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }
        get("/openapi.yaml") {
            val spec = checkNotNull(javaClass.classLoader.getResourceAsStream("openapi.yaml"))
                .bufferedReader().use { it.readText() }
            call.respondText(spec, io.ktor.http.ContentType.parse("application/yaml"))
        }
        authenticate("api-token") {
            route("/api/v1") {
                get("/service") {
                    call.respond(
                        ApiEnvelope(
                            ServiceInfo(
                                apiVersion = BANGUMI_DATA_API_VERSION,
                                serviceVersion = applicationVersion(),
                            ),
                        ),
                    )
                }
                get("/catalog/status") {
                    call.respond(ApiEnvelope(catalog.status()))
                }
                get("/catalog/characters") {
                    val gender = call.request.queryParameters["gender"]?.let {
                        runCatching { CharacterGender.valueOf(it.uppercase()) }.getOrNull()
                    } ?: CharacterGender.FEMALE
                    val tiers = call.request.queryParameters["tiers"]?.split(',')?.mapNotNull {
                        runCatching { FamiliarityTier.valueOf(it.trim().uppercase()) }.getOrNull()
                    }?.toSet().orEmpty()
                    val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1_000) ?: 500
                    call.respond(ApiEnvelope(catalog.characters(gender, tiers, offset, limit)))
                }
                post("/admin/sync") {
                    val force = call.request.queryParameters["force"]?.toBooleanStrictOrNull() ?: false
                    if (launchSync(force)) call.respond(HttpStatusCode.Accepted, ApiEnvelope(mapOf("started" to true)))
                    else call.respond(HttpStatusCode.Conflict, ApiError("sync_running", "已有同步任务正在运行"))
                }
            }
        }
    }
}

private fun constantTimeEquals(actual: String, expected: String): Boolean =
    MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

private fun applicationVersion(): String =
    object {}.javaClass.`package`.implementationVersion ?: "development"

private object EmptyCatalogReader : CatalogReader {
    override fun status() = top.e404.bangumi.api.CatalogStatus()
    override fun characters(
        gender: CharacterGender,
        tiers: Set<FamiliarityTier>,
        offset: Int,
        limit: Int,
    ) = top.e404.bangumi.api.CatalogPage<top.e404.bangumi.api.CatalogCharacter>(emptyList(), offset, limit, 0, "")
}
