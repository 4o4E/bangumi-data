package top.e404.bangumi.server.archive

import top.e404.bangumi.server.catalog.CatalogStore
import top.e404.bangumi.server.catalog.PopularitySelector
import top.e404.bangumi.server.storage.Database
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class FullArchiveManualTest {
    @Test
    fun `完整 Archive 可以导入并计算熟悉度`() {
        val archivePath = System.getProperty("bangumi.fullArchive")?.takeIf(String::isNotBlank) ?: return
        val jdbcUrl = checkNotNull(System.getenv("BANGUMI_TEST_POSTGRES_URL"))
        Database(
            jdbcUrl,
            System.getenv("BANGUMI_TEST_POSTGRES_USER") ?: "bangumi",
            System.getenv("BANGUMI_TEST_POSTGRES_PASSWORD") ?: "bangumi",
        ).use { database ->
            database.migrate()
            val store = CatalogStore(database.dataSource)
            val generation = "manual-full"
            val reader = ArchiveReader()
            store.beginGeneration(generation, Path.of(archivePath).fileName.toString(), "manual", System.currentTimeMillis())
            val summary = store.importArchive(
                generation,
                reader.subjects(Path.of(archivePath)),
                reader.characters(Path.of(archivePath)),
                reader.relations(Path.of(archivePath)),
                System.currentTimeMillis(),
            )
            val works = store.applyPopularity(generation, PopularitySelector())
            val selected = store.selectedCharacterIds(generation).size
            println("完整 Archive 验证: $summary, analyzedWorks=$works, selectedCharacters=$selected")
            assertTrue(summary.subjects > 10_000)
            assertTrue(summary.relations > 10_000)
            assertTrue(works > 1_000)
            assertTrue(selected > 1_000)
        }
    }
}
