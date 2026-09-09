package top.e404.bangumi.server.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import top.e404.bangumi.server.archive.ArchiveClient
import top.e404.bangumi.server.archive.ArchiveReader
import top.e404.bangumi.server.bangumi.CatalogEnricher
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

data class CatalogSyncResult(
    val generation: String?,
    val skipped: Boolean,
    val selectedCharacters: Int = 0,
    val eligibleCharacters: Long = 0,
)

class CatalogSyncService(
    private val store: CatalogStore,
    private val archive: ArchiveClient,
    private val reader: ArchiveReader,
    private val enricher: CatalogEnricher,
    private val dataDirectory: Path,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val localRunning = AtomicBoolean(false)
    private val launchPending = AtomicBoolean(false)

    suspend fun sync(force: Boolean = false): CatalogSyncResult {
        if (!localRunning.compareAndSet(false, true)) return CatalogSyncResult(null, skipped = true)
        var generation: String? = null
        try {
            return store.withSyncLock {
                store.updateSyncState(true, "CHECKING_RELEASE", null)
                val release = withContext(Dispatchers.IO) { archive.latest() }
                if (!force && store.status().sourceVersion == release.name) {
                    store.updateSyncState(false, null, null)
                    return@withSyncLock CatalogSyncResult(store.status().generation, skipped = true)
                }
                generation = release.name.removeSuffix(".zip").replace(Regex("[^A-Za-z0-9._-]"), "-")
                val zip = dataDirectory.resolve("incoming").resolve(release.name)
                store.beginGeneration(generation!!, release.name, release.digest, release.createdAtEpochMillis)
                store.updateSyncState(true, "DOWNLOADING", null)
                withContext(Dispatchers.IO) { archive.download(release, zip) }

                store.updateSyncState(true, "IMPORTING", null)
                val summary = withContext(Dispatchers.IO) {
                    store.importArchive(
                        generation!!, reader.subjects(zip), reader.characters(zip), reader.relations(zip),
                        release.createdAtEpochMillis,
                    )
                }
                check(summary.subjects > 0 && summary.characters > 0 && summary.relations > 0) {
                    "Archive 导入结果为空或不完整: $summary"
                }

                store.updateSyncState(true, "CALCULATING_POPULARITY", null)
                store.applyPopularity(generation!!, PopularitySelector())
                val characterIds = store.selectedCharacterIds(generation!!)
                check(characterIds.isNotEmpty()) { "人气算法没有选出任何角色" }

                store.updateSyncState(true, "ENRICHING_CHARACTERS", null)
                characterIds.forEachIndexed { index, id ->
                    val value = enricher.character(id) ?: CharacterEnrichment(id, "", emptyList(), null, null, null, true)
                    store.saveCharacterEnrichment(generation!!, value)
                    if ((index + 1) % 100 == 0) log.info("角色详情补充进度: {}/{}", index + 1, characterIds.size)
                }
                val subjectIds = store.selectedEligibleSubjectIds(generation!!)
                store.updateSyncState(true, "ENRICHING_SUBJECTS", null)
                subjectIds.forEachIndexed { index, id ->
                    enricher.subject(id)?.let { store.saveSubjectEnrichment(generation!!, it) }
                    if ((index + 1) % 100 == 0) log.info("作品详情补充进度: {}/{}", index + 1, subjectIds.size)
                }

                store.updateSyncState(true, "ACTIVATING", null)
                store.activate(generation!!)
                Files.deleteIfExists(zip)
                val status = store.status()
                log.info("Bangumi 目录同步完成: generation={}, characters={}", generation, status.characterCount)
                CatalogSyncResult(generation, skipped = false, selectedCharacters = characterIds.size, eligibleCharacters = status.characterCount)
            } ?: CatalogSyncResult(null, skipped = true)
        } catch (error: Throwable) {
            store.failGeneration(generation, error)
            throw error
        } finally {
            localRunning.set(false)
        }
    }

    fun launch(scope: CoroutineScope, force: Boolean = false): Boolean {
        if (localRunning.get() || !launchPending.compareAndSet(false, true)) return false
        scope.launch {
            try {
                runCatching { sync(force) }.onFailure { log.error("手动同步 Bangumi 目录失败", it) }
            } finally {
                launchPending.set(false)
            }
        }
        return true
    }

    fun startScheduler(scope: CoroutineScope, interval: Duration, initialDelay: Duration = Duration.ofSeconds(30)): Job = scope.launch {
        delay(initialDelay.toMillis())
        while (true) {
            runCatching { sync() }.onFailure { log.error("定时同步 Bangumi 目录失败", it) }
            delay(interval.toMillis())
        }
    }

    override fun close() = enricher.close()
}
