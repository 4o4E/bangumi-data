package top.e404.bangumi.server

data class ServerConfig(
    val host: String,
    val port: Int,
    val token: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfig {
            val token = environment.required("BANGUMI_DATA_TOKEN")
            require(token.length >= 32) { "BANGUMI_DATA_TOKEN 至少需要 32 个字符" }

            return ServerConfig(
                host = environment["BANGUMI_DATA_HOST"]?.takeIf(String::isNotBlank) ?: "0.0.0.0",
                port = environment["BANGUMI_DATA_PORT"]?.toIntOrNull()
                    ?.takeIf { it in 1..65535 }
                    ?: 8080,
                token = token,
                databaseUrl = environment.required("BANGUMI_DATA_DATABASE_URL"),
                databaseUser = environment.required("BANGUMI_DATA_DATABASE_USER"),
                databasePassword = environment.required("BANGUMI_DATA_DATABASE_PASSWORD"),
            )
        }
    }
}

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank)
        ?: error("缺少必需环境变量 $name")
