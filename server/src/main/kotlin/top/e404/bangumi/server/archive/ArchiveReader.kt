package top.e404.bangumi.server.archive

import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.zip.ZipFile

class ArchiveReader(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun subjects(path: Path): Sequence<ArchiveSubject> = lines(path, "subject.jsonlines") { json.decodeFromString(it) }
    fun characters(path: Path): Sequence<ArchiveCharacter> = lines(path, "character.jsonlines") { json.decodeFromString(it) }
    fun relations(path: Path): Sequence<ArchiveSubjectCharacter> = lines(path, "subject-characters.jsonlines") { json.decodeFromString(it) }

    private fun <T> lines(path: Path, entryName: String, decode: (String) -> T): Sequence<T> = sequence {
        ZipFile(path.toFile()).use { zip ->
            val entry = checkNotNull(zip.getEntry(entryName)) { "Archive 中缺少 $entryName" }
            BufferedReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8), 1024 * 1024).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) yield(decode(line))
                }
            }
        }
    }
}
