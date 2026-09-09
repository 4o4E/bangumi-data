package top.e404.bangumi.server.archive

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchiveReaderTest {
    @Test
    fun `逐条读取 Archive 所需文件`() {
        val zip = Files.createTempFile("bangumi-archive-", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(zip)).use { output ->
                output.entry("subject.jsonlines", """{"id":1,"type":4,"name":"Game","name_cn":"游戏"}""")
                output.entry("character.jsonlines", """{"id":2,"role":1,"name":"角色","collects":99}""")
                output.entry("subject-characters.jsonlines", """{"character_id":2,"subject_id":1,"type":2,"order":3}""")
            }
            val reader = ArchiveReader()
            assertEquals(4, reader.subjects(zip).single().type)
            assertEquals(99, reader.characters(zip).single().collects)
            assertEquals(2, reader.relations(zip).single().type)
        } finally {
            Files.deleteIfExists(zip)
        }
    }

    private fun ZipOutputStream.entry(name: String, body: String) {
        putNextEntry(ZipEntry(name))
        write((body + "\n").toByteArray())
        closeEntry()
    }
}
