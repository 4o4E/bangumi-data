package top.e404.bangumi.server.bangumi

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.e404.bangumi.api.CharacterGender

class BangumiApiClientTest {
    @Test
    fun `角色和作品请求通过显式 HTTP 代理`() {
        val requests = CopyOnWriteArrayList<String>()
        val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val target = exchange.requestURI.toString()
                requests += target
                val response = when {
                    target.contains("/characters/3") ->
                        """{"name":"测试角色","gender":"female","summary":"这是一段足够长的中文角色简介内容","images":{"large":"https://example.com/character.jpg"}}"""
                    target.contains("/subjects/8") ->
                        """{"name_cn":"测试作品","images":{"large":"https://example.com/subject.jpg"}}"""
                    else -> error("未预期的请求: $target")
                }.encodeToByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        try {
            BangumiApiClient(
                requestDelayMillis = 0,
                proxyUrl = "http://127.0.0.1:${proxy.address.port}",
                apiBaseUrl = "http://api.invalid/v0",
            ).use { client ->
                runBlocking {
                    val character = requireNotNull(client.character(3))
                    assertEquals("测试角色", character.name)
                    assertEquals(CharacterGender.FEMALE, character.gender)
                    assertEquals("测试作品", requireNotNull(client.subject(8)).name)
                }
            }
            assertTrue(requests.any { it.contains("api.invalid/v0/characters/3") })
            assertTrue(requests.any { it.contains("api.invalid/v0/subjects/8") })
        } finally {
            proxy.stop(0)
        }
    }
}
