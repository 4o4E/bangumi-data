package top.e404.bangumi.server.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.security.MessageDigest
import javax.sql.DataSource

class Database(
    jdbcUrl: String,
    username: String,
    password: String,
) : AutoCloseable {
    private val pool = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = username
        this.password = password
        maximumPoolSize = 6
        minimumIdle = 1
        poolName = "bangumi-data"
    })

    val dataSource: DataSource get() = pool

    fun migrate() {
        pool.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS bangumi_schema_history (
                            version VARCHAR(100) PRIMARY KEY,
                            checksum VARCHAR(64) NOT NULL,
                            installed_at BIGINT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
                val migrations = resourceText("db/migration/index.txt")
                    .lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
                migrations.forEach { file ->
                    val sql = resourceText("db/migration/$file")
                    val checksum = sha256(sql)
                    val installed = connection.prepareStatement(
                        "SELECT checksum FROM bangumi_schema_history WHERE version = ?",
                    ).use { statement ->
                        statement.setString(1, file)
                        statement.executeQuery().use { result -> result.takeIf { it.next() }?.getString(1) }
                    }
                    if (installed != null) {
                        check(installed == checksum) { "数据库 migration 校验和不一致: $file" }
                        return@forEach
                    }
                    connection.createStatement().use { it.execute(sql) }
                    connection.prepareStatement(
                        "INSERT INTO bangumi_schema_history(version, checksum, installed_at) VALUES (?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, file)
                        statement.setString(2, checksum)
                        statement.setLong(3, System.currentTimeMillis())
                        statement.executeUpdate()
                    }
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

    override fun close() = pool.close()
}

private fun resourceText(path: String): String =
    checkNotNull(Database::class.java.classLoader.getResourceAsStream(path)) { "缺少资源 $path" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
