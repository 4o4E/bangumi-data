package top.e404.bangumi.server.catalog

import top.e404.bangumi.api.CatalogCharacter
import top.e404.bangumi.api.CatalogPage
import top.e404.bangumi.api.CatalogPopularityDiagnostics
import top.e404.bangumi.api.CatalogPopularitySample
import top.e404.bangumi.api.CatalogStatus
import top.e404.bangumi.api.CatalogWork
import top.e404.bangumi.api.CharacterGender
import top.e404.bangumi.api.CharacterRelation
import top.e404.bangumi.api.FamiliarityTier
import top.e404.bangumi.api.PopularityMetric
import top.e404.bangumi.server.archive.ArchiveCharacter
import top.e404.bangumi.server.archive.ArchiveImportSummary
import top.e404.bangumi.server.archive.ArchiveSubject
import top.e404.bangumi.server.archive.ArchiveSubjectCharacter
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

interface CatalogReader {
    fun status(): CatalogStatus
    fun characters(gender: CharacterGender, tiers: Set<FamiliarityTier>, offset: Int, limit: Int): CatalogPage<CatalogCharacter>
    fun popularityDiagnostics(
        gender: CharacterGender,
        characterId: Long?,
        maxCharacterCollects: Long,
        limit: Int,
    ): CatalogPopularityDiagnostics
}

data class CharacterEnrichment(
    val id: Long,
    val name: String,
    val aliases: List<String>,
    val gender: CharacterGender?,
    val description: String?,
    val imageUrl: String?,
    val nsfw: Boolean,
)

data class SubjectEnrichment(val id: Long, val name: String?, val imageUrl: String?, val nsfw: Boolean? = null)

class CatalogStore(private val dataSource: DataSource) : CatalogReader {
    /** 返回 true 表示同一来源数据代已完成 Archive 导入，可直接从未补充记录继续。 */
    fun beginGeneration(id: String, sourceVersion: String, digest: String, sourceCreatedAt: Long, reset: Boolean = false): Boolean {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val existing = connection.prepareStatement(
                    "SELECT source_digest, archive_imported FROM bangumi_catalog_generation WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.getString(1) to result.getBoolean(2) else null
                    }
                }
                val sameSource = !reset && existing?.first == digest
                val archiveImported = sameSource && existing?.second == true
                connection.prepareStatement(
                    """
                    INSERT INTO bangumi_catalog_generation(id, source_version, source_digest, source_created_at, status, created_at)
                    VALUES (?, ?, ?, ?, 'IMPORTING', ?)
                    ON CONFLICT(id) DO UPDATE SET source_version = EXCLUDED.source_version,
                        source_digest = EXCLUDED.source_digest, source_created_at = EXCLUDED.source_created_at, error = NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, id)
                    statement.setString(2, sourceVersion)
                    statement.setString(3, digest)
                    statement.setLong(4, sourceCreatedAt)
                    statement.setLong(5, System.currentTimeMillis())
                    statement.executeUpdate()
                }
                if (sameSource) {
                    connection.prepareStatement(
                        """
                        UPDATE bangumi_catalog_generation generation
                        SET status = CASE WHEN EXISTS (
                            SELECT 1 FROM bangumi_catalog_state state WHERE state.active_generation_id = generation.id
                        ) THEN 'ACTIVE' ELSE 'IMPORTING' END
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { it.setString(1, id); it.executeUpdate() }
                } else {
                    connection.prepareStatement("DELETE FROM bangumi_subject WHERE generation_id = ?").use {
                        it.setString(1, id); it.executeUpdate()
                    }
                    connection.prepareStatement("DELETE FROM bangumi_character WHERE generation_id = ?").use {
                        it.setString(1, id); it.executeUpdate()
                    }
                    connection.prepareStatement(
                        "UPDATE bangumi_catalog_generation SET status = 'IMPORTING', archive_imported = FALSE WHERE id = ?",
                    ).use { it.setString(1, id); it.executeUpdate() }
                }
                connection.commit()
                return archiveImported
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    suspend fun <T> withSyncLock(block: suspend () -> T): T? = dataSource.connection.use { connection ->
        val acquired = connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use {
            it.setLong(1, SYNC_LOCK_ID)
            it.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }
        if (!acquired) return@use null
        try {
            block()
        } finally {
            connection.prepareStatement("SELECT pg_advisory_unlock(?)").use {
                it.setLong(1, SYNC_LOCK_ID)
                it.executeQuery().close()
            }
        }
    }

    fun failGeneration(generation: String?, error: Throwable) {
        dataSource.connection.use { connection ->
            if (generation != null) connection.prepareStatement(
                """
                UPDATE bangumi_catalog_generation generation
                SET status = CASE WHEN EXISTS (
                    SELECT 1 FROM bangumi_catalog_state state WHERE state.active_generation_id = generation.id
                ) THEN 'ACTIVE' ELSE 'FAILED' END, error = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { it.setString(1, error.message); it.setString(2, generation); it.executeUpdate() }
        }
        updateSyncState(false, null, error.stackTraceToString().take(8_000))
    }

    fun importArchive(
        generation: String,
        subjects: Sequence<ArchiveSubject>,
        characters: Sequence<ArchiveCharacter>,
        relations: Sequence<ArchiveSubjectCharacter>,
        sourceTime: Long,
    ): ArchiveImportSummary = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val subjectCount = importSubjects(connection, generation, subjects, sourceTime)
            val characterCount = importCharacters(connection, generation, characters, sourceTime)
            val relationCount = importRelations(connection, generation, relations)
            connection.prepareStatement(
                """
                DELETE FROM bangumi_character c
                WHERE c.generation_id = ? AND NOT EXISTS (
                    SELECT 1 FROM bangumi_subject_character sc
                    WHERE sc.generation_id = c.generation_id AND sc.character_id = c.id
                )
                """.trimIndent(),
            ).use { it.setString(1, generation); it.executeUpdate() }
            connection.prepareStatement(
                "UPDATE bangumi_catalog_generation SET archive_imported = TRUE WHERE id = ?",
            ).use { it.setString(1, generation); it.executeUpdate() }
            connection.commit()
            ArchiveImportSummary(subjectCount, characterCount, relationCount)
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    fun applyPopularity(generation: String, selector: PopularitySelector): Int {
        val grouped = linkedMapOf<Long, MutableList<PopularityCandidate>>()
        val subjectTypes = mutableMapOf<Long, Int>()
        val subjectFavorites = mutableMapOf<Long, Long>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT sc.subject_id, s.type, sc.character_id, c.collects,
                       COALESCE(s.favorite_wish, 0) + COALESCE(s.favorite_done, 0) +
                       COALESCE(s.favorite_doing, 0) + COALESCE(s.favorite_on_hold, 0) +
                       COALESCE(s.favorite_dropped, 0)
                FROM bangumi_subject_character sc
                JOIN bangumi_character c ON c.generation_id = sc.generation_id AND c.id = sc.character_id
                JOIN bangumi_subject s ON s.generation_id = sc.generation_id AND s.id = sc.subject_id
                WHERE sc.generation_id = ? AND sc.relation_type IN (1, 2)
                ORDER BY sc.subject_id, c.collects DESC, c.id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, generation)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val subjectId = result.getLong(1)
                        subjectTypes[subjectId] = result.getInt(2)
                        subjectFavorites[subjectId] = result.getLong(5)
                        grouped.getOrPut(subjectId, ::mutableListOf)
                            .add(PopularityCandidate(result.getLong(3), result.getLong(4)))
                    }
                }
            }
        }
        val globallyFamiliarByType = grouped.entries.groupBy { subjectTypes.getValue(it.key) }.mapValues { (_, entries) ->
            selector.selectGlobal(entries.flatMap { it.value }).associateBy(PopularitySelection::characterId)
        }
        val familiarWorksByType = subjectTypes.entries.groupBy { it.value }.mapValues { (_, subjects) ->
            selector.selectGlobal(subjects.map { subject ->
                PopularityCandidate(subject.key, subjectFavorites.getValue(subject.key))
            }).associateBy(PopularitySelection::characterId)
        }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    UPDATE bangumi_subject_character
                    SET popularity_rank = ?, familiarity = ?, selection_reason = ?, selection_version = ?
                    WHERE generation_id = ? AND subject_id = ? AND character_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    grouped.forEach { (subjectId, candidates) ->
                        val fullRank = candidates.sortedWith(compareByDescending<PopularityCandidate> { it.collects }.thenBy { it.characterId })
                            .mapIndexed { index, item -> item.characterId to index + 1 }.toMap()
                        val locallyFamiliar = selector.select(candidates).associateBy(PopularitySelection::characterId)
                        candidates.forEach { candidate ->
                            val localChoice = locallyFamiliar[candidate.characterId]
                            val globalChoice = globallyFamiliarByType.getValue(subjectTypes.getValue(subjectId))[candidate.characterId]
                            val workChoice = familiarWorksByType.getValue(subjectTypes.getValue(subjectId))[subjectId]
                            val familiarity = when {
                                localChoice == null || globalChoice == null || workChoice == null -> FamiliarityTier.LONG_TAIL
                                localChoice.tier == FamiliarityTier.CORE && globalChoice.tier == FamiliarityTier.CORE &&
                                    workChoice.tier == FamiliarityTier.CORE -> FamiliarityTier.CORE
                                else -> FamiliarityTier.FAMILIAR
                            }
                            val reason = when {
                                localChoice == null -> "LOCAL_TAIL"
                                globalChoice == null -> "GLOBAL_TAIL"
                                workChoice == null -> "WORK_TAIL"
                                familiarity == FamiliarityTier.CORE -> "ALL_CORE"
                                else -> "BOUNDARY"
                            }
                            statement.setInt(1, fullRank.getValue(candidate.characterId))
                            statement.setString(2, familiarity.name)
                            statement.setString(3, reason)
                            statement.setInt(4, selector.algorithmVersion)
                            statement.setString(5, generation)
                            statement.setLong(6, subjectId)
                            statement.setLong(7, candidate.characterId)
                            statement.addBatch()
                        }
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
        return grouped.size
    }

    fun selectedCharacterIds(generation: String): List<Long> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT DISTINCT character_id FROM bangumi_subject_character
            WHERE generation_id = ? AND familiarity IN ('CORE', 'FAMILIAR') ORDER BY character_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, generation)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getLong(1)) } }
        }
    }

    fun hasPopularity(
        generation: String,
        algorithmVersion: Int = PopularitySelector.ALGORITHM_VERSION,
    ): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT EXISTS(
                SELECT 1 FROM bangumi_subject_character
                WHERE generation_id = ? AND selection_version = ? AND familiarity IN ('CORE', 'FAMILIAR')
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, generation)
            statement.setInt(2, algorithmVersion)
            statement.executeQuery().use { it.next(); it.getBoolean(1) }
        }
    }

    fun pendingCharacterIds(generation: String, limit: Int): List<Long> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT DISTINCT sc.character_id
            FROM bangumi_subject_character sc
            JOIN bangumi_character c ON c.generation_id = sc.generation_id AND c.id = sc.character_id
            WHERE sc.generation_id = ? AND sc.familiarity IN ('CORE', 'FAMILIAR') AND c.enriched = FALSE
            ORDER BY sc.character_id LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, generation); statement.setInt(2, limit)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getLong(1)) } }
        }
    }

    fun selectedSubjectIds(generation: String): List<Long> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT DISTINCT subject_id FROM bangumi_subject_character
            WHERE generation_id = ? AND familiarity IN ('CORE', 'FAMILIAR') ORDER BY subject_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, generation)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getLong(1)) } }
        }
    }

    fun selectedEligibleSubjectIds(generation: String): List<Long> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT DISTINCT sc.subject_id FROM bangumi_subject_character sc
            JOIN bangumi_character c ON c.generation_id = sc.generation_id AND c.id = sc.character_id
            WHERE sc.generation_id = ? AND sc.familiarity IN ('CORE', 'FAMILIAR') AND c.eligible = TRUE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, generation)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getLong(1)) } }
        }
    }

    fun pendingEligibleSubjectIds(generation: String, limit: Int): List<Long> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT DISTINCT sc.subject_id
            FROM bangumi_subject_character sc
            JOIN bangumi_character c ON c.generation_id = sc.generation_id AND c.id = sc.character_id
            JOIN bangumi_subject s ON s.generation_id = sc.generation_id AND s.id = sc.subject_id
            WHERE sc.generation_id = ? AND sc.familiarity IN ('CORE', 'FAMILIAR')
              AND c.eligible = TRUE AND s.enriched = FALSE
            ORDER BY sc.subject_id LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, generation); statement.setInt(2, limit)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getLong(1)) } }
        }
    }

    fun saveCharacterEnrichment(generation: String, value: CharacterEnrichment) =
        saveCharacterEnrichments(generation, listOf(value))

    fun saveCharacterEnrichments(generation: String, values: List<CharacterEnrichment>) {
        if (values.isEmpty()) return
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    UPDATE bangumi_character SET name_cn = ?, aliases = ?, gender = ?, summary = ?, image_url = ?,
                        nsfw = ?, eligible = ?, enriched = TRUE, source_updated_at = ?
                    WHERE generation_id = ? AND id = ?
                    """.trimIndent(),
                ).use { statement ->
                    val updatedAt = System.currentTimeMillis()
                    values.forEach { value ->
                        statement.setString(1, value.name)
                        statement.setArray(2, connection.createArrayOf("text", value.aliases.toTypedArray()))
                        statement.setString(3, value.gender?.name)
                        statement.setString(4, value.description)
                        statement.setString(5, value.imageUrl)
                        statement.setBoolean(6, value.nsfw)
                        statement.setBoolean(7, !value.nsfw && value.gender != null && value.name.isNotBlank() && value.imageUrl != null)
                        statement.setLong(8, updatedAt)
                        statement.setString(9, generation)
                        statement.setLong(10, value.id)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun saveSubjectEnrichment(generation: String, value: SubjectEnrichment) =
        saveSubjectEnrichments(generation, listOf(value))

    fun saveSubjectEnrichments(generation: String, values: List<SubjectEnrichment>) {
        if (values.isEmpty()) return
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    UPDATE bangumi_subject
                    SET name_cn = COALESCE(NULLIF(?, ''), name_cn),
                        nsfw = COALESCE(?, nsfw),
                        -- Bangumi 只有条目级 NSFW 标记，没有图片级审核结果；已标记条目不得下发封面。
                        image_url = CASE WHEN COALESCE(?, nsfw) THEN NULL ELSE ? END,
                        enriched = TRUE,
                        source_updated_at = ?
                    WHERE generation_id = ? AND id = ?
                    """.trimIndent(),
                ).use { statement ->
                    val updatedAt = System.currentTimeMillis()
                    values.forEach { value ->
                        statement.setString(1, value.name)
                        statement.setObject(2, value.nsfw, Types.BOOLEAN)
                        statement.setObject(3, value.nsfw, Types.BOOLEAN)
                        statement.setString(4, value.imageUrl)
                        statement.setLong(5, updatedAt)
                        statement.setString(6, generation)
                        statement.setLong(7, value.id)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /** 调用方必须已持有 [withSyncLock]；这里只用事务保证数据代切换原子提交。 */
    fun activate(generation: String, syncComplete: Boolean = true) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val eligible = connection.prepareStatement(
                    """
                    SELECT COUNT(*) FROM bangumi_character c
                    WHERE c.generation_id = ? AND c.eligible = TRUE
                      AND EXISTS (
                        SELECT 1 FROM bangumi_subject_character sc
                        WHERE sc.generation_id=c.generation_id AND sc.character_id=c.id
                          AND sc.familiarity IN ('CORE', 'FAMILIAR')
                      )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, generation)
                    statement.executeQuery().use { it.next(); it.getLong(1) }
                }
                check(eligible > 0) { "拒绝激活空角色目录" }
                connection.prepareStatement(
                    "UPDATE bangumi_catalog_generation SET status = 'ACTIVE', activated_at = ? WHERE id = ?",
                ).use { it.setLong(1, System.currentTimeMillis()); it.setString(2, generation); it.executeUpdate() }
                val stateSql = if (syncComplete) {
                    "UPDATE bangumi_catalog_state SET active_generation_id = ?, sync_running = FALSE, sync_phase = NULL, last_error = NULL WHERE singleton = TRUE"
                } else {
                    "UPDATE bangumi_catalog_state SET active_generation_id = ?, last_error = NULL WHERE singleton = TRUE"
                }
                connection.prepareStatement(stateSql).use { it.setString(1, generation); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM bangumi_catalog_generation WHERE id <> ?").use {
                    it.setString(1, generation)
                    it.executeUpdate()
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun updateSyncState(running: Boolean, phase: String?, error: String?) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE bangumi_catalog_state SET sync_running = ?, sync_phase = ?, last_error = ? WHERE singleton = TRUE",
            ).use { it.setBoolean(1, running); it.setString(2, phase); it.setString(3, error); it.executeUpdate() }
        }
    }

    override fun status(): CatalogStatus = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT s.active_generation_id, s.sync_running, s.sync_phase, s.last_error,
                   g.source_version, g.source_created_at, g.activated_at,
                   (SELECT COUNT(*) FROM bangumi_character c
                    WHERE c.generation_id = s.active_generation_id AND c.eligible = TRUE
                      AND EXISTS (
                        SELECT 1 FROM bangumi_subject_character sc
                        WHERE sc.generation_id=c.generation_id AND sc.character_id=c.id
                          AND sc.familiarity IN ('CORE', 'FAMILIAR')
                      ))
            FROM bangumi_catalog_state s LEFT JOIN bangumi_catalog_generation g ON g.id = s.active_generation_id
            WHERE s.singleton = TRUE
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                result.next()
                CatalogStatus(
                    generation = result.getString(1), syncRunning = result.getBoolean(2), syncPhase = result.getString(3),
                    lastError = result.getString(4), sourceVersion = result.getString(5),
                    sourceCreatedAt = result.nullableLong(6), activatedAt = result.nullableLong(7), characterCount = result.getLong(8),
                )
            }
        }
    }

    override fun characters(gender: CharacterGender, tiers: Set<FamiliarityTier>, offset: Int, limit: Int): CatalogPage<CatalogCharacter> {
        val status = status()
        val generation = status.generation ?: return CatalogPage(emptyList(), offset, limit, 0, "")
        val tierNames = tiers.ifEmpty { setOf(FamiliarityTier.CORE, FamiliarityTier.FAMILIAR) }.map(FamiliarityTier::name).toTypedArray()
        return dataSource.connection.use { connection ->
            val total = connection.prepareStatement(
                """
                SELECT COUNT(DISTINCT c.id) FROM bangumi_character c JOIN bangumi_subject_character sc
                  ON sc.generation_id = c.generation_id AND sc.character_id = c.id
                WHERE c.generation_id = ? AND c.gender = ? AND c.eligible = TRUE AND sc.familiarity = ANY(?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, generation); statement.setString(2, gender.name)
                statement.setArray(3, connection.createArrayOf("text", tierNames))
                statement.executeQuery().use { it.next(); it.getLong(1) }
            }
            val rows = connection.prepareStatement(
                """
                SELECT DISTINCT c.id, c.name_cn, c.aliases, c.summary, c.image_url, c.collects, c.source_updated_at
                FROM bangumi_character c JOIN bangumi_subject_character sc
                  ON sc.generation_id = c.generation_id AND sc.character_id = c.id
                WHERE c.generation_id = ? AND c.gender = ? AND c.eligible = TRUE AND sc.familiarity = ANY(?)
                ORDER BY c.id LIMIT ? OFFSET ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, generation); statement.setString(2, gender.name)
                statement.setArray(3, connection.createArrayOf("text", tierNames)); statement.setInt(4, limit); statement.setInt(5, offset)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toCharacterRow()) } }
            }
            val ids = rows.map(CharacterRow::id)
            val works = if (ids.isEmpty()) emptyMap() else loadWorks(connection, generation, ids)
            CatalogPage(
                items = rows.map { row -> CatalogCharacter(
                    id = row.id, name = row.name, aliases = row.aliases, gender = gender,
                    description = row.description, imageUrl = row.imageUrl, works = works[row.id].orEmpty(),
                    popularities = listOf(PopularityMetric("bangumi", "collects", row.collects)), sourceUpdatedAt = row.sourceUpdatedAt,
                ) },
                offset = offset, limit = limit, total = total, generation = generation,
            )
        }
    }

    /** 对照角色自身热度与所属作品热度，用于识别冷门角色来自哪一层筛选。 */
    override fun popularityDiagnostics(
        gender: CharacterGender,
        characterId: Long?,
        maxCharacterCollects: Long,
        limit: Int,
    ): CatalogPopularityDiagnostics {
        val generation = status().generation
            ?: return CatalogPopularityDiagnostics("", 0, gender, characterId, maxCharacterCollects, false, 0, 0, emptyList())
        return dataSource.connection.use { connection ->
            val workPopularityAvailable = connection.prepareStatement(
                """
                SELECT EXISTS(SELECT 1 FROM bangumi_subject WHERE generation_id=? AND favorite_wish IS NOT NULL)
                   AND NOT EXISTS(SELECT 1 FROM bangumi_subject WHERE generation_id=? AND favorite_wish IS NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, generation)
                statement.setString(2, generation)
                statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
            }
            val counts = connection.prepareStatement(
                """
                SELECT COUNT(DISTINCT c.id), COUNT(DISTINCT c.id) FILTER (WHERE c.collects <= ?)
                FROM bangumi_character c
                WHERE c.generation_id = ? AND c.gender = ? AND c.eligible = TRUE
                  AND (CAST(? AS BIGINT) IS NULL OR c.id = ?)
                  AND EXISTS (
                    SELECT 1 FROM bangumi_subject_character sc
                    WHERE sc.generation_id = c.generation_id AND sc.character_id = c.id
                      AND sc.familiarity IN ('CORE', 'FAMILIAR')
                  )
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, maxCharacterCollects)
                statement.setString(2, generation)
                statement.setString(3, gender.name)
                statement.setObject(4, characterId, java.sql.Types.BIGINT)
                statement.setObject(5, characterId, java.sql.Types.BIGINT)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) to result.getLong(2) }
            }
            val samples = connection.prepareStatement(
                """
                WITH work_stats AS (
                    SELECT subject_id,
                           COUNT(*) FILTER (WHERE relation_type IN (1, 2)) AS candidate_count,
                           COUNT(*) FILTER (WHERE familiarity IN ('CORE', 'FAMILIAR')) AS selected_count
                    FROM bangumi_subject_character
                    WHERE generation_id = ?
                    GROUP BY subject_id
                )
                SELECT c.id,c.name_cn,c.collects,s.id,s.name_cn,s.type,
                       s.favorite_wish,s.favorite_done,s.favorite_doing,s.favorite_on_hold,s.favorite_dropped,
                       sc.relation_type,sc.familiarity,sc.popularity_rank,stats.candidate_count,stats.selected_count,
                       sc.selection_reason
                FROM bangumi_subject_character sc
                JOIN bangumi_character c ON c.generation_id=sc.generation_id AND c.id=sc.character_id
                JOIN bangumi_subject s ON s.generation_id=sc.generation_id AND s.id=sc.subject_id
                JOIN work_stats stats ON stats.subject_id=sc.subject_id
                WHERE sc.generation_id=? AND c.gender=? AND c.eligible=TRUE AND c.collects<=?
                  AND (CAST(? AS BIGINT) IS NULL OR c.id = ?)
                  AND (CAST(? AS BIGINT) IS NOT NULL OR sc.familiarity IN ('CORE', 'FAMILIAR'))
                ORDER BY c.collects,
                         COALESCE(s.favorite_wish+s.favorite_done+s.favorite_doing+s.favorite_on_hold+s.favorite_dropped, -1) DESC,
                         c.id, s.id
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, generation)
                statement.setString(2, generation)
                statement.setString(3, gender.name)
                statement.setLong(4, maxCharacterCollects)
                statement.setObject(5, characterId, java.sql.Types.BIGINT)
                statement.setObject(6, characterId, java.sql.Types.BIGINT)
                statement.setObject(7, characterId, java.sql.Types.BIGINT)
                statement.setInt(8, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(
                            CatalogPopularitySample(
                                characterId = result.getLong(1),
                                characterName = result.getString(2),
                                characterCollects = result.getLong(3),
                                workId = result.getLong(4),
                                workName = result.getString(5),
                                workType = result.getInt(6),
                                workPopularities = result.workPopularities(7),
                                relation = relation(result.getInt(12)),
                                familiarity = FamiliarityTier.valueOf(result.getString(13)),
                                popularityRank = result.getInt(14),
                                candidateCharacterCount = result.getInt(15),
                                selectedCharacterCount = result.getInt(16),
                                selectionReason = result.getString(17),
                            ),
                        )
                    }
                }
            }
            CatalogPopularityDiagnostics(
                generation, PopularitySelector.ALGORITHM_VERSION, gender, characterId, maxCharacterCollects,
                workPopularityAvailable, counts.first, counts.second, samples,
            )
        }
    }

    private fun importSubjects(connection: Connection, generation: String, values: Sequence<ArchiveSubject>, sourceTime: Long): Long {
        var count = 0L
        connection.prepareStatement(
            """
            INSERT INTO bangumi_subject(
                generation_id,id,type,name,name_cn,summary,image_url,nsfw,
                favorite_wish,favorite_done,favorite_doing,favorite_on_hold,favorite_dropped,source_updated_at
            ) VALUES (?,?,?,?,?,?,NULL,?,?,?,?,?,?,?)
            ON CONFLICT (generation_id,id) DO UPDATE SET
                type=EXCLUDED.type,
                name=EXCLUDED.name,
                name_cn=EXCLUDED.name_cn,
                summary=EXCLUDED.summary,
                image_url=CASE WHEN EXCLUDED.nsfw THEN NULL ELSE bangumi_subject.image_url END,
                nsfw=EXCLUDED.nsfw,
                favorite_wish=EXCLUDED.favorite_wish,
                favorite_done=EXCLUDED.favorite_done,
                favorite_doing=EXCLUDED.favorite_doing,
                favorite_on_hold=EXCLUDED.favorite_on_hold,
                favorite_dropped=EXCLUDED.favorite_dropped,
                enriched=CASE
                    WHEN bangumi_subject.nsfw = TRUE AND EXCLUDED.nsfw = FALSE THEN FALSE
                    ELSE bangumi_subject.enriched
                END,
                source_updated_at=EXCLUDED.source_updated_at
            """.trimIndent(),
        ).use { statement ->
            values.filter { it.type in setOf(2, 4) && !it.childOriented() }.forEach { value ->
                // 中文名不是数据完整性的前提；缺失时保留原名，避免系统性漏掉海外游戏和 Galgame。
                val displayName = value.nameCn.trim().ifBlank { value.name.trim() }
                if (displayName.isBlank()) return@forEach
                statement.setString(1, generation); statement.setLong(2, value.id); statement.setInt(3, value.type)
                statement.setString(4, value.name); statement.setString(5, displayName); statement.setString(6, value.summary)
                statement.setBoolean(7, value.nsfw); statement.setLong(8, value.favorite.wish)
                statement.setLong(9, value.favorite.done); statement.setLong(10, value.favorite.doing)
                statement.setLong(11, value.favorite.onHold); statement.setLong(12, value.favorite.dropped)
                statement.setLong(13, sourceTime); statement.addBatch(); count++
                if (count % BATCH_SIZE == 0L) {
                    statement.executeBatch()
                    connection.commit()
                }
            }
            statement.executeBatch()
            connection.commit()
        }
        return count
    }

    private fun importCharacters(connection: Connection, generation: String, values: Sequence<ArchiveCharacter>, sourceTime: Long): Long {
        var count = 0L
        connection.prepareStatement(
            "INSERT INTO bangumi_character(generation_id,id,entity_type,name,summary,comments,collects,source_updated_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (generation_id,id) DO NOTHING",
        ).use { statement ->
            values.filter { it.role == 1 }.forEach { value ->
                statement.setString(1, generation); statement.setLong(2, value.id); statement.setInt(3, value.role)
                statement.setString(4, value.name); statement.setString(5, value.summary); statement.setLong(6, value.comments)
                statement.setLong(7, value.collects); statement.setLong(8, sourceTime); statement.addBatch(); count++
                if (count % BATCH_SIZE == 0L) {
                    statement.executeBatch()
                    connection.commit()
                }
            }
            statement.executeBatch()
            connection.commit()
        }
        return count
    }

    private fun importRelations(connection: Connection, generation: String, values: Sequence<ArchiveSubjectCharacter>): Long {
        var attempted = 0L
        connection.prepareStatement(
            """
            INSERT INTO bangumi_subject_character(generation_id,subject_id,character_id,relation_type,relation_order)
            SELECT ?,?,?,?,? WHERE EXISTS (SELECT 1 FROM bangumi_subject WHERE generation_id=? AND id=?)
              AND EXISTS (SELECT 1 FROM bangumi_character WHERE generation_id=? AND id=?) ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            values.filter { it.type in 1..3 }.forEach { value ->
                statement.setString(1, generation); statement.setLong(2, value.subjectId); statement.setLong(3, value.characterId)
                statement.setInt(4, value.type); statement.setInt(5, value.order); statement.setString(6, generation)
                statement.setLong(7, value.subjectId); statement.setString(8, generation); statement.setLong(9, value.characterId)
                statement.addBatch(); attempted++
                if (attempted % BATCH_SIZE == 0L) {
                    statement.executeBatch()
                    connection.commit()
                }
            }
            statement.executeBatch()
            connection.commit()
        }
        return connection.prepareStatement(
            "SELECT COUNT(*) FROM bangumi_subject_character WHERE generation_id = ?",
        ).use { statement ->
            statement.setString(1, generation)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
    }

    private fun loadWorks(connection: Connection, generation: String, characterIds: List<Long>): Map<Long, List<CatalogWork>> {
        return connection.prepareStatement(
            """
            SELECT sc.character_id,s.id,s.type,s.name_cn,s.image_url,sc.relation_type,sc.familiarity,sc.popularity_rank,
                   s.favorite_wish,s.favorite_done,s.favorite_doing,s.favorite_on_hold,s.favorite_dropped
            FROM bangumi_subject_character sc JOIN bangumi_subject s ON s.generation_id=sc.generation_id AND s.id=sc.subject_id
            WHERE sc.generation_id=? AND sc.character_id=ANY(?)
              AND sc.familiarity IN ('CORE', 'FAMILIAR')
            ORDER BY sc.character_id,
                     COALESCE(s.favorite_wish, 0) + COALESCE(s.favorite_done, 0) + COALESCE(s.favorite_doing, 0) +
                     COALESCE(s.favorite_on_hold, 0) + COALESCE(s.favorite_dropped, 0) DESC,
                     sc.popularity_rank,s.id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, generation); statement.setArray(2, connection.createArrayOf("int8", characterIds.toTypedArray()))
            statement.executeQuery().use { result ->
                buildMap<Long, MutableList<CatalogWork>> {
                    while (result.next()) getOrPut(result.getLong(1), ::mutableListOf).add(
                        CatalogWork(result.getLong(2), result.getInt(3), result.getString(4), result.getString(5),
                            relation(result.getInt(6)), FamiliarityTier.valueOf(result.getString(7)), result.getInt(8),
                            result.workPopularities(9)),
                    )
                }
            }
        }
    }

    private data class CharacterRow(val id: Long, val name: String, val aliases: List<String>, val description: String?, val imageUrl: String, val collects: Long, val sourceUpdatedAt: Long)
    private fun ResultSet.toCharacterRow() = CharacterRow(getLong(1), getString(2), (getArray(3).array as Array<*>).map { it.toString() }, getString(4), getString(5), getLong(6), getLong(7))
    private fun ResultSet.workPopularities(start: Int): List<PopularityMetric> {
        val values = (start until start + 5).map { index -> getLong(index).let { if (wasNull()) null else it } }
        if (values.any { it == null }) return emptyList()
        val favorites = values.filterNotNull()
        return listOf(
            PopularityMetric("bangumi", "favorites", favorites.sum()),
            PopularityMetric("bangumi", "favorite_wish", favorites[0]),
            PopularityMetric("bangumi", "favorite_done", favorites[1]),
            PopularityMetric("bangumi", "favorite_doing", favorites[2]),
            PopularityMetric("bangumi", "favorite_on_hold", favorites[3]),
            PopularityMetric("bangumi", "favorite_dropped", favorites[4]),
        )
    }
    private fun ResultSet.nullableLong(index: Int): Long? = getLong(index).let { if (wasNull()) null else it }
    private fun relation(value: Int) = when (value) { 1 -> CharacterRelation.PROTAGONIST; 2 -> CharacterRelation.SUPPORTING; else -> CharacterRelation.CAMEO }

    companion object { const val SYNC_LOCK_ID = 0x42474D44L; private const val BATCH_SIZE = 5_000L }
}

private fun ArchiveSubject.childOriented() = "子供向" in metaTags || tags.any { it.name == "子供向" && it.count >= 2 }
private fun String.containsHan() = codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
