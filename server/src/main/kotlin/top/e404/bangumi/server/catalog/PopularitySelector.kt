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
    val boundarySimilarity: Double = 0.9,
    val fallbackRatio: Double = 0.25,
    val globalClusterCount: Int = 4,
    val contemporaryLowMiddleRatio: Double = 2.0,
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

        val values = ranked.map { ln1p(it.collects.toDouble()) }
        val highEnd = fastGlobalHeadEnd(values, config.globalClusterCount)
            ?: fastGlobalHeadEnd(values, 3)
            ?: return fallback(ranked)
        val boundary = ranked[highEnd - 1].collects
        var end = highEnd
        while (end < ranked.size && ranked[end].collects >= boundary * config.boundarySimilarity) end++
        return ranked.take(end).mapIndexed { index, candidate ->
            PopularitySelection(
                characterId = candidate.characterId,
                tier = if (index < highEnd) FamiliarityTier.CORE else FamiliarityTier.FAMILIAR,
                reason = if (index < highEnd) "HIGH" else "BOUNDARY",
                rank = index + 1,
            )
        }
    }

    /**
     * 全局门槛通过后再比较同期作品。只有最低簇与中间簇存在明确断层时才删除最低簇，
     * 避免连续分布被强制截断，也避免固定保留比例把同期中上游作品一并删掉。
     */
    fun selectContemporary(candidates: Collection<PopularityCandidate>): List<PopularitySelection> {
        val ranked = candidates.filter { it.collects > 0 }.distinctBy { it.characterId }
            .sortedWith(compareByDescending<PopularityCandidate> { it.collects }.thenBy { it.characterId })
        if (ranked.size < 6) return retainAll(ranked)
        val values = ranked.map { ln1p(it.collects.toDouble()) }
        val clusters = fastClusters(values, 3) ?: return retainAll(ranked)
        if (exp(clusters.centers[1] - clusters.centers[0]) < config.contemporaryLowMiddleRatio) {
            return retainAll(ranked)
        }
        val lowBoundary = (clusters.centers[0] + clusters.centers[1]) / 2
        val baseEnd = values.count { it >= lowBoundary }
        var end = baseEnd
        val boundary = ranked[baseEnd - 1].collects
        while (end < ranked.size && ranked[end].collects >= boundary * config.boundarySimilarity) end++
        return ranked.take(end).mapIndexed { index, candidate ->
            PopularitySelection(
                characterId = candidate.characterId,
                tier = FamiliarityTier.FAMILIAR,
                reason = if (index < baseEnd) "PERIOD_FAMILIAR" else "PERIOD_BOUNDARY",
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

    /** 线性复杂度识别全站最高热度簇；数据不足以形成细分层级时自动回退三簇。 */
    private fun fastGlobalHeadEnd(values: List<Double>, clusterCount: Int): Int? {
        if (clusterCount < 3) return null
        val clusters = fastClusters(values, clusterCount) ?: return null
        val highBoundary = (clusters.centers[clusterCount - 2] + clusters.centers[clusterCount - 1]) / 2
        val highEnd = values.count { it >= highBoundary }
        if (highEnd < 2 || values.size - highEnd < 2) return null
        return highEnd
    }

    private fun fastClusters(values: List<Double>, clusterCount: Int): Clusters? {
        if (values.size < clusterCount * 2) return null
        var centers = DoubleArray(clusterCount) { index ->
            values[values.size * (index * 2 + 1) / (clusterCount * 2)]
        }.sortedArray()
        for (iteration in 0 until 32) {
            val sums = DoubleArray(clusterCount)
            val counts = IntArray(clusterCount)
            values.forEach { value ->
                val cluster = centers.indices.minBy { index -> abs(value - centers[index]) }
                sums[cluster] += value
                counts[cluster]++
            }
            if (counts.any { count -> count == 0 }) return null
            val updated = DoubleArray(clusterCount) { index -> sums[index] / counts[index] }.sortedArray()
            val converged = centers.indices.all { index -> abs(updated[index] - centers[index]) < 1e-6 }
            centers = updated
            if (converged) break
        }
        val counts = IntArray(clusterCount)
        values.forEach { value -> counts[centers.indices.minBy { index -> abs(value - centers[index]) }]++ }
        return if (counts.any { count -> count < 2 }) null else Clusters(centers)
    }

    private fun retainAll(ranked: List<PopularityCandidate>) = ranked.mapIndexed { index, candidate ->
        PopularitySelection(candidate.characterId, FamiliarityTier.FAMILIAR, "PERIOD_CONTINUOUS", index + 1)
    }

    private fun sse(prefix: DoubleArray, squares: DoubleArray, start: Int, end: Int): Double {
        val count = end - start
        val sum = prefix[end] - prefix[start]
        return squares[end] - squares[start] - sum * sum / count
    }

    private fun mean(prefix: DoubleArray, start: Int, end: Int) = (prefix[end] - prefix[start]) / (end - start)
    private data class Split(val highEnd: Int, val middleEnd: Int, val middleMean: Double, val tailMean: Double)
    private data class Clusters(val centers: DoubleArray)

    companion object {
        const val ALGORITHM_VERSION = 6
    }
}
