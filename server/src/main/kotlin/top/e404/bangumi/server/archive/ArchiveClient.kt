package top.e404.bangumi.server.archive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

class ArchiveClient(
    private val latestUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun latest(): ArchiveRelease {
        val dto = json.decodeFromString<LatestDto>(request(latestUrl).decodeToString())
        return ArchiveRelease(dto.name, dto.browserDownloadUrl, dto.digest, Instant.parse(dto.createdAt).toEpochMilli())
    }

    fun download(release: ArchiveRelease, target: Path): Path {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(target.fileName.toString() + ".part")
        requestStream(release.url).use { input -> Files.newOutputStream(temporary).use(input::copyTo) }
        val expected = release.digest.removePrefix("sha256:").lowercase()
        val actual = sha256(temporary)
        check(actual == expected) { "Archive SHA-256 校验失败: expected=$expected, actual=$actual" }
        return Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun request(url: String): ByteArray = requestStream(url).use { it.readBytes() }

    private fun requestStream(url: String): java.io.InputStream {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.instanceFollowRedirects = true
        check(connection.responseCode in 200..299) { "Archive 请求失败: ${connection.responseCode} $url" }
        return connection.inputStream
    }

    @Serializable
    private data class LatestDto(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val digest: String,
        @SerialName("created_at") val createdAt: String,
    )
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val USER_AGENT = "e404/bangumi-data (https://github.com/4o4E/bangumi-data)"
