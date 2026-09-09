package top.e404.bangumi.server.bangumi

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.e404.bangumi.api.CharacterGender
import top.e404.bangumi.server.catalog.CharacterEnrichment
import top.e404.bangumi.server.catalog.SubjectEnrichment
import top.e404.bangumi.server.net.httpProxyUrl
import top.e404.bangumi.server.net.openHttpConnection
import top.e404.bangumi.server.net.toHttpProxy
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

interface CatalogEnricher : AutoCloseable {
    suspend fun character(id: Long): CharacterEnrichment?
    suspend fun subject(id: Long): SubjectEnrichment?
    override fun close() = Unit
}

class BangumiApiClient(
    private val requestDelayMillis: Long,
    proxyUrl: String? = httpProxyUrl(),
    private val apiBaseUrl: String = "https://api.bgm.tv/v0",
) : CatalogEnricher {
    private val jsonCodec = Json { ignoreUnknownKeys = true }
    private val proxy = proxyUrl?.takeIf(String::isNotBlank)?.toHttpProxy()
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    override suspend fun character(id: Long): CharacterEnrichment? {
        val root = request("$apiBaseUrl/characters/$id") ?: return null
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
        val root = request("$apiBaseUrl/subjects/$id") ?: return null
        return SubjectEnrichment(id, root.string("name_cn")?.trim()?.takeIf(String::isNotEmpty), root.imageUrl())
    }

    private suspend fun request(url: String): JsonObject? = mutex.withLock {
        val wait = requestDelayMillis - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        try {
            repeat(3) { attempt ->
                val response = try {
                    withContext(Dispatchers.IO) { execute(url) }
                } catch (error: IOException) {
                    if (attempt < 2) {
                        delay(500L * (attempt + 1))
                        return@repeat
                    }
                    throw error
                }
                if (response.status == 404) return@withLock null
                if (response.status in 200..299) return@withLock jsonCodec.decodeFromString<JsonObject>(response.body)
                val body = response.body.take(500)
                val retryable = response.status == 429 || response.status >= 500
                if (retryable && attempt < 2) {
                    val retryAfterMillis = response.retryAfter?.toLongOrNull()
                        ?.coerceIn(1, 60)?.times(1_000) ?: (500L * (attempt + 1))
                    delay(retryAfterMillis)
                } else {
                    error("Bangumi API 请求失败: ${response.status} $url body=$body")
                }
            }
            error("Bangumi API 请求失败: $url")
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun execute(url: String): ApiResponse {
        val connection = URI(url).toURL().openHttpConnection(proxy)
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            ApiResponse(status, body, connection.getHeaderField("Retry-After"))
        } finally {
            connection.disconnect()
        }
    }

    override fun close() = Unit
}

private data class ApiResponse(val status: Int, val body: String, val retryAfter: String?)

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
