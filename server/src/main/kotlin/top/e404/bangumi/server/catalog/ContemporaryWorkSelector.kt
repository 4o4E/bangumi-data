package top.e404.bangumi.server.catalog

import top.e404.bangumi.api.FamiliarityTier
import java.time.LocalDate

data class WorkPopularityCandidate(
    val subjectId: Long,
    val type: Int,
    val platform: Int?,
    val releaseDate: LocalDate?,
    val favorites: Long,
)

/** 在全局作品门槛之后，按作品形态和发行时期识别同期长尾。 */
class ContemporaryWorkSelector(
    private val selector: PopularitySelector,
    private val minimumCohortSize: Int = 30,
) {
    fun select(
        candidates: Collection<WorkPopularityCandidate>,
        globalSelections: Map<Long, PopularitySelection>,
    ): Set<Long> {
        val globalCandidates = candidates.filter { it.subjectId in globalSelections }
        val groups = globalCandidates.mapNotNull { candidate ->
            candidate.periodKey()?.let { key -> key to candidate }
        }.groupBy({ it.first }, { it.second })
        val selected = buildSet {
            groups.forEach { (key, ownCandidates) ->
                val comparison = comparisonCandidates(key, groups)
                if (comparison.distinctPositiveCount() < minimumCohortSize) {
                    ownCandidates.forEach { add(it.subjectId) }
                    return@forEach
                }
                val familiarIds = selector.selectContemporary(
                    comparison.map { PopularityCandidate(it.subjectId, it.favorites) },
                ).mapTo(hashSetOf(), PopularitySelection::characterId)
                ownCandidates.filterTo(mutableListOf()) { it.subjectId in familiarIds }
                    .forEach { add(it.subjectId) }
            }
            // 没有日期就无法声称“同期熟悉”；仅保留全局核心层，不让边界扩展结果绕过新门槛。
            globalCandidates.filter { it.releaseDate == null }
                .filter { globalSelections.getValue(it.subjectId).tier == FamiliarityTier.CORE }
                .forEach { add(it.subjectId) }
        }
        return selected
    }

    private fun comparisonCandidates(
        key: WorkPeriodKey,
        groups: Map<WorkPeriodKey, List<WorkPopularityCandidate>>,
    ): List<WorkPopularityCandidate> {
        val samePlatformLimit = when (key.kind) {
            WorkPeriodKind.ANIMATION_QUARTER -> 8
            WorkPeriodKind.ANIMATION_YEAR -> 2
            WorkPeriodKind.GAME_YEAR -> 2
        }
        val samePlatform = expandingWindow(key, groups, samePlatformLimit) { candidateKey ->
            candidateKey.kind == key.kind && candidateKey.platform == key.platform
        }
        if (samePlatform.distinctPositiveCount() >= minimumCohortSize || key.kind != WorkPeriodKind.ANIMATION_YEAR) {
            return samePlatform
        }
        // OVA、剧场版和 WEB 的单一载体在早期可能过少；先在同一时代合并非 TV 动画，再决定是否切尾。
        return expandingWindow(key, groups, 5) { candidateKey ->
            candidateKey.kind == WorkPeriodKind.ANIMATION_YEAR
        }
    }

    private fun expandingWindow(
        key: WorkPeriodKey,
        groups: Map<WorkPeriodKey, List<WorkPopularityCandidate>>,
        maximumRadius: Int,
        accepts: (WorkPeriodKey) -> Boolean,
    ): List<WorkPopularityCandidate> {
        val result = mutableListOf<WorkPopularityCandidate>()
        var radius = 0
        while (radius <= maximumRadius) {
            groups.filterKeys { candidateKey -> accepts(candidateKey) && kotlin.math.abs(candidateKey.period - key.period) == radius }
                .values.forEach(result::addAll)
            if (result.distinctPositiveCount() >= minimumCohortSize) break
            radius++
        }
        return result
    }

    private fun Collection<WorkPopularityCandidate>.distinctPositiveCount() =
        asSequence().filter { it.favorites > 0 }.distinctBy(WorkPopularityCandidate::subjectId).count()
}

private enum class WorkPeriodKind { ANIMATION_QUARTER, ANIMATION_YEAR, GAME_YEAR }

private data class WorkPeriodKey(
    val kind: WorkPeriodKind,
    val platform: Int?,
    val period: Int,
)

private fun WorkPopularityCandidate.periodKey(): WorkPeriodKey? {
    val date = releaseDate ?: return null
    return when {
        type == 2 && platform == 1 -> WorkPeriodKey(
            WorkPeriodKind.ANIMATION_QUARTER,
            platform,
            date.year * 4 + (date.monthValue - 1) / 3,
        )
        type == 2 -> WorkPeriodKey(WorkPeriodKind.ANIMATION_YEAR, platform, date.year)
        type == 4 -> WorkPeriodKey(WorkPeriodKind.GAME_YEAR, null, date.year)
        else -> null
    }
}
