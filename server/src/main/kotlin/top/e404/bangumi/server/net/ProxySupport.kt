package top.e404.bangumi.server.net

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL

internal fun httpProxyUrl(environment: Map<String, String> = System.getenv()): String? =
    listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
        .firstNotNullOfOrNull { name -> environment[name]?.takeIf(String::isNotBlank) }

internal fun String.toHttpProxy(): Proxy {
    val uri = URI(this)
    require(uri.scheme.equals("http", ignoreCase = true)) { "代理只支持 HTTP URL: $this" }
    val host = requireNotNull(uri.host) { "代理缺少主机名: $this" }
    val port = uri.port.takeIf { it > 0 } ?: 80
    return Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))
}

internal fun URL.openHttpConnection(proxy: Proxy?): HttpURLConnection =
    (proxy?.let { openConnection(it) } ?: openConnection()) as HttpURLConnection
