package top.e404.bangumi.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    private val token = "a".repeat(32)
    private val config = ServerConfig(
        host = "127.0.0.1",
        port = 8080,
        token = token,
        databaseUrl = "jdbc:postgresql://localhost/test",
        databaseUser = "test",
        databasePassword = "test",
    )

    @Test
    fun `健康检查无需 token`() = testApplication {
        application { configureApplication(config) }

        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `业务接口拒绝未鉴权请求`() = testApplication {
        application { configureApplication(config) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/service").status)
    }

    @Test
    fun `业务接口接受环境变量 token`() = testApplication {
        application { configureApplication(config) }

        val response = client.get("/api/v1/service") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
