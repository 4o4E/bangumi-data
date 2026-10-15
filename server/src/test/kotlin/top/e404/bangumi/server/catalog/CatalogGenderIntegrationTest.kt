package top.e404.bangumi.server.catalog

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import top.e404.bangumi.api.CharacterGender
import top.e404.bangumi.server.archive.ArchiveCharacter
import top.e404.bangumi.server.archive.ArchiveFavorite
import top.e404.bangumi.server.archive.ArchiveSubject
import top.e404.bangumi.server.archive.ArchiveSubjectCharacter
import top.e404.bangumi.server.storage.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogGenderIntegrationTest {
    @Test
    fun `双形态同时进入男女目录且分页统计和诊断保持一致`() {
        withIsolatedDatabase { database, _ ->
            val store = fixture(database)
            store.saveCharacterEnrichments("gender", listOf(
                enrichment(1, CharacterGenderScope.FEMALE),
                enrichment(2, CharacterGenderScope.MALE),
                enrichment(3, CharacterGenderScope.BOTH),
            ))
            runBlocking { checkNotNull(store.withSyncLock { store.activate("gender") }) }
            val female = store.characters(CharacterGender.FEMALE, emptySet(), 0, 100)
            val male = store.characters(CharacterGender.MALE, emptySet(), 0, 100)
            assertEquals(listOf(1L, 3L), female.items.map { it.id })
            assertEquals(listOf(2L, 3L), male.items.map { it.id })
            assertEquals(2, female.total)
            assertEquals(2, male.total)
            assertEquals(3, store.status().characterCount)
            for (gender in CharacterGender.entries) {
                val pages = (0..1).flatMap { offset -> store.characters(gender, emptySet(), offset, 1).items }
                assertEquals(if (gender == CharacterGender.FEMALE) listOf(1L, 3L) else listOf(2L, 3L), pages.map { it.id })
                assertTrue(pages.all { it.gender == gender })
                val diagnostic = store.popularityDiagnostics(gender, 3, 10_000, 100)
                assertEquals(1, diagnostic.selectedCharacterCount)
                assertEquals(setOf(3L), diagnostic.samples.map { it.characterId }.toSet())
            }
        }
    }

    @Test
    fun `历史候选重入补充队列且旧目录在分批恢复中保持可读`() {
        withIsolatedDatabase { database, scopedUrl ->
            val store = fixture(database)
            store.saveCharacterEnrichments("gender", listOf(
                enrichment(1, CharacterGenderScope.FEMALE),
                enrichment(2, CharacterGenderScope.MALE),
                enrichment(3, null),
                enrichment(8, CharacterGenderScope.FEMALE),
            ))
            runBlocking { checkNotNull(store.withSyncLock { store.activate("gender") }) }
            assertEquals(emptyList(), store.pendingCharacterIds("gender", 100))
            val sql = requireNotNull(javaClass.classLoader.getResourceAsStream(
                "db/migration/V007__refresh_character_gender_scopes.sql",
            )).bufferedReader().use { it.readText() }
            database.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute(sql) }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT enriched FROM bangumi_character WHERE generation_id='gender' AND id=8").use { row ->
                        assertTrue(row.next() && row.getBoolean(1))
                    }
                }
            }
            assertEquals(listOf(1L, 2L, 3L), store.pendingCharacterIds("gender", 100))
            assertEquals(listOf(1L), store.characters(CharacterGender.FEMALE, emptySet(), 0, 100).items.map { it.id })
            store.saveCharacterEnrichment("gender", enrichment(1, CharacterGenderScope.FEMALE))
            // 迁移历史已登记，正常重启迁移不能再次把刚完成的批次改为待补充。
            database.migrate()
            Database(scopedUrl, dbUser(), dbPassword()).use { resumed ->
                val next = CatalogStore(resumed.dataSource)
                assertEquals(listOf(2L, 3L), next.pendingCharacterIds("gender", 100))
                next.saveCharacterEnrichments("gender", listOf(
                    enrichment(2, CharacterGenderScope.MALE),
                    enrichment(3, CharacterGenderScope.BOTH),
                ))
                assertEquals(emptyList(), next.pendingCharacterIds("gender", 100))
                assertEquals(listOf(1L, 3L), next.characters(CharacterGender.FEMALE, emptySet(), 0, 100).items.map { it.id })
                assertEquals(listOf(2L, 3L), next.characters(CharacterGender.MALE, emptySet(), 0, 100).items.map { it.id })
            }
        }
    }

    private fun fixture(database: Database): CatalogStore {
        val store = CatalogStore(database.dataSource)
        store.beginGeneration("gender", "fixture.zip", "sha256:gender", 1_000)
        val collects = listOf(1_000L, 900, 800, 100, 90, 80, 1, 1)
        store.importArchive(
            "gender",
            sequenceOf(ArchiveSubject(10, 2, "Anime", "动画", favorite = ArchiveFavorite(done = 1_000), date = "2025-01-01", platform = 1)),
            collects.mapIndexed { index, value -> ArchiveCharacter(index + 1L, 1, "C$index", collects = value) }.asSequence(),
            collects.indices.map { ArchiveSubjectCharacter(it + 1L, 10, if (it == 0) 1 else 2, it) }.asSequence(),
            1_000,
        )
        store.applyPopularity("gender", PopularitySelector())
        return store
    }

    private fun enrichment(id: Long, gender: CharacterGenderScope?): CharacterEnrichment {
        return CharacterEnrichment(id, "角色$id", emptyList(), gender, "角色简介", "https://example.com/$id.jpg", false)
    }

    private fun withIsolatedDatabase(block: (Database, String) -> Unit) {
        val url = System.getenv("BANGUMI_TEST_POSTGRES_URL")
        assumeTrue(url != null, "需要隔离 PostgreSQL")
        val baseUrl = requireNotNull(url)
        val schema = "gender_" + UUID.randomUUID().toString().replace("-", "")
        Database(baseUrl, dbUser(), dbPassword()).use { owner ->
            owner.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            }
            try {
                val scopedUrl = baseUrl + if ('?' in baseUrl) "&currentSchema=$schema" else "?currentSchema=$schema"
                Database(scopedUrl, dbUser(), dbPassword()).use { database ->
                    database.migrate()
                    block(database, scopedUrl)
                }
            } finally {
                owner.dataSource.connection.use { connection ->
                    connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
                }
            }
        }
    }

    private fun dbUser(): String = System.getenv("BANGUMI_TEST_POSTGRES_USER") ?: "bangumi"
    private fun dbPassword(): String = System.getenv("BANGUMI_TEST_POSTGRES_PASSWORD") ?: "bangumi"
}
