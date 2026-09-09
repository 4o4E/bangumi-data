package top.e404.bangumi.client

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BangumiDataClientTest {
    @Test
    fun `客户端携带 token 并解析稳定响应`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"data":{"apiVersion":"v1","serviceVersion":"test"}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        BangumiDataClient(
            baseUrl = "http://bangumi-data:8080/",
            token = "test-token",
            engine = engine,
        ).use { client ->
            val result = client.getServiceInfo()
            assertEquals("v1", result.apiVersion)
            assertEquals("test", result.serviceVersion)
        }
    }
}
