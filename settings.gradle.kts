pluginManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "bangumi-data"

include(
    "api-model",
    "http-client",
    "server",
)
