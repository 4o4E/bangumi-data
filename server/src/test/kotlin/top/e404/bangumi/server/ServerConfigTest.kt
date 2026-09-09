package top.e404.bangumi.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerConfigTest {
    private val requiredEnvironment = mapOf(
        "BANGUMI_DATA_TOKEN" to "t".repeat(32),
        "BANGUMI_DATA_DATABASE_URL" to "jdbc:postgresql://postgres/bangumi",
        "BANGUMI_DATA_DATABASE_USER" to "bangumi",
        "BANGUMI_DATA_DATABASE_PASSWORD" to "password",
    )

    @Test
    fun `只从环境变量加载 token 和数据库配置`() {
        val config = ServerConfig.fromEnvironment(requiredEnvironment)

        assertEquals("t".repeat(32), config.token)
        assertEquals("jdbc:postgresql://postgres/bangumi", config.databaseUrl)
    }

    @Test
    fun `拒绝过短 token`() {
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(
                requiredEnvironment + ("BANGUMI_DATA_TOKEN" to "too-short"),
            )
        }
    }
}
