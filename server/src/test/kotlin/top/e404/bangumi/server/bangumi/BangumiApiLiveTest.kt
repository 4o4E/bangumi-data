package top.e404.bangumi.server.bangumi

import top.e404.bangumi.api.CharacterGender
import top.e404.bangumi.server.archive.ArchiveClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BangumiApiLiveTest {
    @Test
    fun `读取真实角色与作品详情`() {
        if (!liveTestEnabled("BANGUMI_LIVE_TEST", "bangumi.liveTest")) return
        BangumiApiClient(0).use { client ->
            kotlinx.coroutines.runBlocking {
                val character = assertNotNull(client.character(3))
                assertEquals(CharacterGender.FEMALE, character.gender)
                assertTrue(character.name.isNotBlank())
                assertTrue(assertNotNull(character.imageUrl).startsWith("https://"))
                assertTrue(assertNotNull(client.subject(8)).imageUrl?.startsWith("https://") == true)
            }
        }
    }

    @Test
    fun `读取真实 Archive 元数据`() {
        if (!liveTestEnabled("BANGUMI_ARCHIVE_LIVE_TEST", "bangumi.archiveLiveTest")) return
        val release = ArchiveClient("https://raw.githubusercontent.com/bangumi/Archive/master/aux/latest.json").latest()
        assertTrue(release.name.endsWith(".zip"))
        assertTrue(release.digest.startsWith("sha256:"))
    }
}

private fun liveTestEnabled(environment: String, property: String): Boolean =
    System.getenv(environment) == "true" || System.getProperty(property) == "true"
