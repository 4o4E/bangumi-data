package top.e404.bangumi.server.catalog

import top.e404.bangumi.api.FamiliarityTier
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln1p

data class PopularityCandidate(val characterId: Long, val collects: Long)

data class PopularitySelection(
    val characterId: Long,
    val tier: FamiliarityTier,
    val reason: String,
    val rank: Int,
)

data class PopularitySelectionConfig(
    val middleTailRatio: Double = 4.0,
    val boundarySimilarity: Double = 0.8,
    val fallbackRatio: Double = 0.25,
)

/** 按候选集合的热度分布识别熟悉角色，不依赖作品名称、固定收藏阈值或主配角标签。 */
class PopularitySelector(private val config: PopularitySelectionConfig = PopularitySelectionConfig()) {
    val algorithmVersion: Int = ALGORITHM_VERSION

    fun select(candidates: Collection<PopularityCandidate>): List<PopularitySelection> {
        val ranked = candidates.filter { it.collects > 0 }.distinctBy { it.characterId }
            .sortedWith(compareByDescending<PopularityCandidate> { it.collects }.thenBy { it.characterId })
        if (ranked.isEmpty()) return emptyList()
        if (ranked.size < 6) return fallback(ranked)

        val split = naturalSplit(ranked.map { ln1p(it.collects.toDouble()) }) ?: return fallback(ranked)
        val keepMiddle = exp(split.middleMean - split.tailMean) >= config.middleTailRatio
        val baseEnd = if (keepMiddle) split.middleEnd else split.highEnd
        val boundary = ranked[baseEnd - 1].collects
        var end = baseEnd
        while (end < ranked.size && ranked[end].collects >= boundary * config.boundarySimilarity) end++
        return ranked.take(end).mapIndexed { index, candidate ->
            PopularitySelection(
                characterId = candidate.characterId,
                tier = if (index < split.highEnd) FamiliarityTier.CORE else FamiliarityTier.FAMILIAR,
                reason = when {
                    index < split.highEnd -> "HIGH"
                    index < baseEnd -> "FAMILIAR"
                    else -> "BOUNDARY"
                },
                rank = index + 1,
            )
        }
    }

    /** 全站集合只保留最高热度簇，作为作品内相对筛选之外的绝对长尾保护。 */
    fun selectGlobal(candidates: Collection<PopularityCandidate>): List<PopularitySelection> {
        val ranked = candidates.filter { it.collects > 0 }.distinctBy { it.characterId }
            .sortedWith(compareByDescending<PopularityCandidate> { it.collects }.thenBy { it.characterId })
        if (ranked.isEmpty()) return emptyList()
        if (ranked.size < 6) return fallback(ranked)

        val split = fastNaturalSplit(ranked.map { ln1p(it.collects.toDouble()) }) ?: return fallback(ranked)
        val boundary = ranked[split.highEnd - 1].collects
        var end = split.highEnd
        while (end < ranked.size && ranked[end].collects >= boundary * config.boundarySimilarity) end++
        return ranked.take(end).mapIndexed { index, candidate ->
            PopularitySelection(
                characterId = candidate.characterId,
                tier = if (index < split.highEnd) FamiliarityTier.CORE else FamiliarityTier.FAMILIAR,
                reason = if (index < split.highEnd) "HIGH" else "BOUNDARY",
                rank = index + 1,
            )
        }
    }

    private fun fallback(ranked: List<PopularityCandidate>): List<PopularitySelection> {
        val count = ceil(ranked.size * config.fallbackRatio.coerceIn(0.0, 1.0)).toInt().coerceAtLeast(1)
        return ranked.take(count).mapIndexed { index, candidate ->
            PopularitySelection(candidate.characterId, FamiliarityTier.FAMILIAR, "FALLBACK", index + 1)
        }
    }

    private fun naturalSplit(values: List<Double>): Split? {
        val prefix = DoubleArray(values.size + 1)
        val squares = DoubleArray(values.size + 1)
        values.forEachIndexed { index, value ->
            prefix[index + 1] = prefix[index] + value
            squares[index + 1] = squares[index] + value * value
        }
        var best: Split? = null
        var bestScore = Double.POSITIVE_INFINITY
        for (highEnd in 2..values.size - 4) {
            for (middleEnd in highEnd + 2..values.size - 2) {
                val score = sse(prefix, squares, 0, highEnd) +
                    sse(prefix, squares, highEnd, middleEnd) + sse(prefix, squares, middleEnd, values.size)
                if (score < bestScore) {
                    bestScore = score
                    best = Split(highEnd, middleEnd, mean(prefix, highEnd, middleEnd), mean(prefix, middleEnd, values.size))
                }
            }
        }
        return best
    }

    /** 线性复杂度的三簇计算，避免在全站角色集合上使用二次复杂度的穷举分段。 */
    private fun fastNaturalSplit(values: List<Double>): Split? {
        var centers = doubleArrayOf(values[values.size * 5 / 6], values[values.size / 2], values[values.size / 6])
            .sortedArray()
        for (iteration in 0 until 32) {
            val sums = DoubleArray(3)
            val counts = IntArray(3)
            values.forEach { value ->
                val cluster = centers.indices.minBy { index -> abs(value - centers[index]) }
                sums[cluster] += value
                counts[cluster]++
            }
            if (counts.any { count -> count == 0 }) return null
            val updated = DoubleArray(3) { index -> sums[index] / counts[index] }.sortedArray()
            val converged = centers.indices.all { index -> abs(updated[index] - centers[index]) < 1e-6 }
            centers = updated
            if (converged) break
        }
        val highBoundary = (centers[1] + centers[2]) / 2
        val middleBoundary = (centers[0] + centers[1]) / 2
        val highEnd = values.count { it >= highBoundary }
        val middleEnd = values.count { it >= middleBoundary }
        if (highEnd < 2 || middleEnd - highEnd < 2 || values.size - middleEnd < 2) return null
        return Split(highEnd, middleEnd, centers[1], centers[0])
    }

    private fun sse(prefix: DoubleArray, squares: DoubleArray, start: Int, end: Int): Double {
        val count = end - start
        val sum = prefix[end] - prefix[start]
        return squares[end] - squares[start] - sum * sum / count
    }

    private fun mean(prefix: DoubleArray, start: Int, end: Int) = (prefix[end] - prefix[start]) / (end - start)
    private data class Split(val highEnd: Int, val middleEnd: Int, val middleMean: Double, val tailMean: Double)

    companion object {
        const val ALGORITHM_VERSION = 4
    }
}
