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
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import top.e404.bangumi.api.ApiEnvelope
import top.e404.bangumi.api.ApiError
import top.e404.bangumi.api.BANGUMI_DATA_API_VERSION
import top.e404.bangumi.api.HealthResponse
import top.e404.bangumi.api.ServiceInfo
import java.security.MessageDigest

fun Application.configureApplication(config: ServerConfig) {
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
            }
        }
    }
}

private fun constantTimeEquals(actual: String, expected: String): Boolean =
    MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

private fun applicationVersion(): String =
    object {}.javaClass.`package`.implementationVersion ?: "development"
