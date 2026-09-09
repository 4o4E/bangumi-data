package top.e404.bangumi.server.archive

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.e404.bangumi.server.net.httpProxyUrl

class ArchiveClientTest {
    @Test
    fun `Archive 请求使用显式 HTTP 代理`() {
        val requestTarget = AtomicReference<String>()
        val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requestTarget.set(exchange.requestURI.toString())
                val response =
                    """{"name":"dump.zip","browser_download_url":"https://example.com/dump.zip","digest":"sha256:00","created_at":"2026-09-10T00:00:00Z"}"""
                        .encodeToByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        try {
            val release = ArchiveClient(
                latestUrl = "http://archive.invalid/latest.json",
                proxyUrl = "http://127.0.0.1:${proxy.address.port}",
            ).latest()

            assertEquals("dump.zip", release.name)
            assertTrue(requestTarget.get().contains("archive.invalid/latest.json"))
        } finally {
            proxy.stop(0)
        }
    }

    @Test
    fun `代理环境变量按 HTTPS 和大写优先`() {
        assertEquals(
            "http://https-upper:1",
            httpProxyUrl(
                mapOf(
                    "HTTP_PROXY" to "http://http-upper:4",
                    "http_proxy" to "http://http-lower:3",
                    "https_proxy" to "http://https-lower:2",
                    "HTTPS_PROXY" to "http://https-upper:1",
                ),
            ),
        )
    }
}
