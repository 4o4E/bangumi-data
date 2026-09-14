package top.e404.bangumi.server.catalog

import top.e404.bangumi.api.FamiliarityTier
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ContemporaryWorkSelectorTest {
    @Test
    fun `TV动画按季度扩展后剔除有明显断层的同期低簇`() {
        val candidates = buildList {
            listOf(120L, 110, 100, 42, 38, 35, 12, 11, 10).forEachIndexed { index, favorites ->
                add(
                    WorkPopularityCandidate(
                        subjectId = index + 1L,
                        type = 2,
                        platform = 1,
                        releaseDate = LocalDate.of(2025, 1, index + 1),
                        favorites = favorites,
                    ),
                )
            }
        }
        val global = candidates.associate { candidate ->
            candidate.subjectId to PopularitySelection(
                candidate.subjectId,
                FamiliarityTier.CORE,
                "HIGH",
                candidate.subjectId.toInt(),
            )
        }

        val selected = ContemporaryWorkSelector(PopularitySelector(), minimumCohortSize = 6).select(candidates, global)

        assertEquals(setOf(1L, 2, 3, 4, 5, 6), selected)
    }

    @Test
    fun `缺失日期只允许全局核心作品通过`() {
        val candidates = listOf(
            WorkPopularityCandidate(1, 2, 1, null, 100),
            WorkPopularityCandidate(2, 2, 1, null, 90),
        )
        val global = mapOf(
            1L to PopularitySelection(1, FamiliarityTier.CORE, "HIGH", 1),
            2L to PopularitySelection(2, FamiliarityTier.FAMILIAR, "BOUNDARY", 2),
        )

        assertEquals(setOf(1L), ContemporaryWorkSelector(PopularitySelector()).select(candidates, global))
    }

    @Test
    fun `游戏同期比较忽略平台编号`() {
        val favorites = listOf(120L, 110, 100, 42, 38, 35, 12, 11, 10)
        val candidates = favorites.mapIndexed { index, value ->
            val id = index + 1L
            WorkPopularityCandidate(id, 4, if (id % 2L == 0L) 4001 else 4003, LocalDate.of(2025, 1, 1), value)
        }
        val global = candidates.associate { candidate ->
            candidate.subjectId to PopularitySelection(candidate.subjectId, FamiliarityTier.CORE, "HIGH", 1)
        }

        val selected = ContemporaryWorkSelector(PopularitySelector(), minimumCohortSize = 6).select(candidates, global)

        assertEquals(setOf(1L, 2, 3, 4, 5, 6), selected)
    }

    @Test
    fun `同期样本不足时不强制切尾`() {
        val candidates = listOf(
            WorkPopularityCandidate(1, 2, 1, LocalDate.of(1980, 1, 1), 100),
            WorkPopularityCandidate(2, 2, 1, LocalDate.of(1980, 1, 2), 10),
        )
        val global = candidates.associate { candidate ->
            candidate.subjectId to PopularitySelection(candidate.subjectId, FamiliarityTier.CORE, "HIGH", 1)
        }

        assertEquals(
            setOf(1L, 2L),
            ContemporaryWorkSelector(PopularitySelector(), minimumCohortSize = 6).select(candidates, global),
        )
    }
}
