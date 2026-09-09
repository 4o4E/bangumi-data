package top.e404.bangumi.server.bangumi

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.e404.bangumi.api.CharacterGender
import top.e404.bangumi.server.catalog.CharacterEnrichment
import top.e404.bangumi.server.catalog.SubjectEnrichment
import java.io.IOException

interface CatalogEnricher : AutoCloseable {
    suspend fun character(id: Long): CharacterEnrichment?
    suspend fun subject(id: Long): SubjectEnrichment?
    override fun close() = Unit
}

class BangumiApiClient(
    private val requestDelayMillis: Long,
    proxyUrl: String? = System.getenv("HTTPS_PROXY") ?: System.getenv("HTTP_PROXY"),
) : CatalogEnricher {
    private val jsonCodec = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) {
        engine {
            proxy = proxyUrl?.takeIf(String::isNotBlank)?.let { ProxyBuilder.http(Url(it)) }
        }
        expectSuccess = false
        install(ContentNegotiation) { json(jsonCodec) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    override suspend fun character(id: Long): CharacterEnrichment? {
        val root = request("https://api.bgm.tv/v0/characters/$id") ?: return null
        val infobox = root["infobox"] as? JsonArray ?: JsonArray(emptyList())
        val values = infobox.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val key = obj.string("key")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            key to obj["value"].leafStrings()
        }.toMap()
        val original = root.string("name").orEmpty().trim()
        val name = CHINESE_KEYS.asSequence().flatMap { values[it].orEmpty() }.map(String::trim)
            .firstOrNull(String::isNotEmpty) ?: original.takeIf { it.containsHan() && !it.containsKana() }.orEmpty()
        val aliases = buildList {
            add(original)
            CHINESE_KEYS.forEach { addAll(values[it].orEmpty()) }
            addAll(values["别名"].orEmpty())
        }.map(String::trim).filter { it.isNotEmpty() && it != name && it.length <= 100 }.distinct().take(20)
        val rawGender = root.string("gender") ?: values["性别"]?.firstOrNull()
        return CharacterEnrichment(
            id = id,
            name = name,
            aliases = aliases,
            gender = rawGender.toGender(),
            description = root.string("summary").toChineseDescription(),
            imageUrl = root.imageUrl(),
            nsfw = root.boolean("nsfw") ?: false,
        )
    }

    override suspend fun subject(id: Long): SubjectEnrichment? {
        val root = request("https://api.bgm.tv/v0/subjects/$id") ?: return null
        return SubjectEnrichment(id, root.string("name_cn")?.trim()?.takeIf(String::isNotEmpty), root.imageUrl())
    }

    private suspend fun request(url: String): JsonObject? = mutex.withLock {
        val wait = requestDelayMillis - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        try {
            repeat(3) { attempt ->
                val response = try {
                    http.get(url) {
                        header(HttpHeaders.UserAgent, USER_AGENT)
                        header(HttpHeaders.Accept, "application/json")
                    }
                } catch (error: IOException) {
                    if (attempt < 2) {
                        delay(500L * (attempt + 1))
                        return@repeat
                    }
                    throw error
                }
                if (response.status == HttpStatusCode.NotFound) {
                    response.bodyAsText()
                    return@withLock null
                }
                if (response.status.value in 200..299) return@withLock response.body<JsonObject>()
                val body = response.bodyAsText().take(500)
                val retryable = response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500
                if (retryable && attempt < 2) {
                    val retryAfterMillis = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                        ?.coerceIn(1, 60)?.times(1_000) ?: (500L * (attempt + 1))
                    delay(retryAfterMillis)
                } else {
                    error("Bangumi API 请求失败: ${response.status.value} $url body=$body")
                }
            }
            error("Bangumi API 请求失败: $url")
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    override fun close() = http.close()
}

private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(key: String) = string(key)?.toBooleanStrictOrNull()
private fun JsonObject.imageUrl(): String? = (get("images") as? JsonObject)?.let { it.string("large") ?: it.string("medium") }
    ?.let { if (it.startsWith("//")) "https:$it" else it }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
private fun JsonElement?.leafStrings(): List<String> = when (this) {
    null -> emptyList()
    is JsonArray -> flatMap { it.leafStrings() }
    is JsonObject -> get("v")?.leafStrings() ?: values.flatMap { it.leafStrings() }
    else -> runCatching { listOf(jsonPrimitive.content) }.getOrDefault(emptyList())
}
private fun String?.toGender() = when (this?.trim()?.lowercase()) {
    "female", "女", "女性" -> CharacterGender.FEMALE
    "male", "男", "男性" -> CharacterGender.MALE
    else -> null
}
private fun String?.toChineseDescription(): String? {
    val text = this?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.joinToString("\n")?.takeIf(String::isNotEmpty) ?: return null
    val han = text.codePoints().filter { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }.count()
    val kana = text.codePoints().filter { Character.UnicodeScript.of(it) in setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA) }.count()
    return text.takeIf { han >= 8 && kana <= maxOf(3, han / 5) }
}
private fun String.containsHan() = codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
private fun String.containsKana() = codePoints().anyMatch { Character.UnicodeScript.of(it) in setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA) }
private val CHINESE_KEYS = listOf("简体中文名", "中文名", "第二中文名")
private const val USER_AGENT = "e404/bangumi-data (https://github.com/4o4E/bangumi-data)"
