package top.e404.bangumi.server.catalog

import top.e404.bangumi.api.FamiliarityTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PopularitySelectorTest {
    @Test
    fun `按快速跌落保留高热度与熟悉角色`() {
        val selected = PopularitySelector().select(
            listOf(1000L, 900, 800, 100, 90, 80, 1, 1).mapIndexed { index, collects ->
                PopularityCandidate(index.toLong() + 1, collects)
            },
        )

        assertEquals(setOf(1L, 2, 3, 4, 5, 6), selected.map { it.characterId }.toSet())
        assertTrue(selected.any { it.tier == FamiliarityTier.CORE })
        assertTrue(selected.any { it.tier == FamiliarityTier.FAMILIAR })
    }

    @Test
    fun `结果只由热度分布决定而不依赖作品`() {
        val candidates = listOf(500L, 300, 100, 10, 3, 1).mapIndexed { index, collects ->
            PopularityCandidate(index.toLong() + 10, collects)
        }
        assertEquals(PopularitySelector().select(candidates), PopularitySelector().select(candidates))
    }
}
