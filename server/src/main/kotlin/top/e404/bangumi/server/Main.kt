package top.e404.bangumi.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import top.e404.bangumi.server.archive.ArchiveClient
import top.e404.bangumi.server.archive.ArchiveReader
import top.e404.bangumi.server.bangumi.BangumiApiClient
import top.e404.bangumi.server.catalog.CatalogStore
import top.e404.bangumi.server.catalog.CatalogSyncService
import top.e404.bangumi.server.storage.Database
import java.nio.file.Path
import java.time.Duration

fun main() {
    val config = ServerConfig.fromEnvironment()
    val database = Database(config.databaseUrl, config.databaseUser, config.databasePassword)
    database.migrate()
    val store = CatalogStore(database.dataSource)
    val sync = CatalogSyncService(
        store = store,
        archive = ArchiveClient(config.archiveLatestUrl),
        reader = ArchiveReader(),
        enricher = BangumiApiClient(config.requestDelayMillis),
        dataDirectory = Path.of(config.dataDirectory),
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    if (config.syncEnabled) sync.startScheduler(scope, Duration.ofHours(config.syncIntervalHours))
    val server = embeddedServer(
        factory = Netty,
        host = config.host,
        port = config.port,
    ) {
        configureApplication(config, store) { force -> sync.launch(scope, force) }
    }
    try {
        server.start(wait = true)
    } finally {
        scope.cancel()
        sync.close()
        database.close()
    }
}
