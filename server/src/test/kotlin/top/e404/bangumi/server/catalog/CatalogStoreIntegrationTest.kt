package top.e404.bangumi.server.catalog

import top.e404.bangumi.api.CharacterGender
import top.e404.bangumi.api.FamiliarityTier
import top.e404.bangumi.server.archive.ArchiveCharacter
import top.e404.bangumi.server.archive.ArchiveSubject
import top.e404.bangumi.server.archive.ArchiveSubjectCharacter
import top.e404.bangumi.server.archive.ArchiveTag
import top.e404.bangumi.server.storage.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogStoreIntegrationTest {
    @Test
    fun `导入计算补充与切换形成完整当前目录`() {
        val jdbcUrl = System.getenv("BANGUMI_TEST_POSTGRES_URL") ?: return
        Database(
            jdbcUrl,
            System.getenv("BANGUMI_TEST_POSTGRES_USER") ?: "bangumi",
            System.getenv("BANGUMI_TEST_POSTGRES_PASSWORD") ?: "bangumi",
        ).use { database ->
            database.migrate()
            val store = CatalogStore(database.dataSource)
            val generation = "test-${UUID.randomUUID()}"
            store.beginGeneration(generation, "fixture.zip", "sha256:test", 1_000)
            val subjects = sequenceOf(
                ArchiveSubject(10, 2, "Anime", "动画", tags = listOf(ArchiveTag("奇幻", 10))),
                ArchiveSubject(20, 4, "Game", "游戏"),
                ArchiveSubject(30, 2, "Kids", "儿童", metaTags = listOf("子供向")),
            )
            val collects = listOf(1000L, 900, 800, 100, 90, 80, 1, 1)
            val characters = collects.mapIndexed { index, value -> ArchiveCharacter(index + 1L, 1, "C$index", collects = value) }.asSequence()
            val relations = sequence {
                collects.indices.forEach { index ->
                    yield(ArchiveSubjectCharacter(index + 1L, 10, if (index == 0) 1 else 2, index))
                }
                yield(ArchiveSubjectCharacter(1, 20, 2, 0))
                yield(ArchiveSubjectCharacter(2, 30, 1, 0))
            }
            val imported = store.importArchive(generation, subjects, characters, relations, 1_000)
            assertEquals(2, imported.subjects)
            assertEquals(9, imported.relations)

            store.applyPopularity(generation, PopularitySelector())
            val selected = store.selectedCharacterIds(generation)
            assertEquals(setOf(1L, 2, 3, 4, 5, 6), selected.toSet())
            selected.forEach { id ->
                store.saveCharacterEnrichment(
                    generation,
                    CharacterEnrichment(id, "角色$id", listOf("C$id"), CharacterGender.FEMALE, "中文角色简介内容足够长", "https://example.com/$id.jpg", false),
                )
            }
            store.selectedSubjectIds(generation).forEach { id ->
                store.saveSubjectEnrichment(generation, SubjectEnrichment(id, null, "https://example.com/work-$id.jpg"))
            }
            store.activate(generation)

            val status = store.status()
            assertEquals(generation, status.generation)
            assertEquals(6, status.characterCount)
            val page = store.characters(CharacterGender.FEMALE, setOf(FamiliarityTier.CORE, FamiliarityTier.FAMILIAR), 0, 100)
            assertEquals(6, page.total)
            assertTrue(page.items.any { character -> character.works.any { it.id == 20L && it.relation.name == "SUPPORTING" } })
            assertFalse(page.items.any { character -> character.works.any { it.id == 30L } })
            assertNotNull(page.items.first().imageUrl)
        }
    }
}
