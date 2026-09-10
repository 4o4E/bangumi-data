plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
}

group = "top.e404.bangumi"
version = providers.environmentVariable("GITHUB_REF_NAME")
    .zip(providers.environmentVariable("GITHUB_REF_TYPE")) { name, type ->
        if (type == "tag") name.removePrefix("v") else "0.1.0-SNAPSHOT"
    }
    .getOrElse("0.1.0-SNAPSHOT")

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

val projectJarPrefixes = subprojects.map { "${it.name}-" }
val serverLibraries = project(":server").layout.buildDirectory.dir("install/bangumi-data/lib")

val prepareDockerRuntimeLibraries by tasks.registering(Sync::class) {
    dependsOn(":server:installDist")
    from(serverLibraries) {
        exclude { details -> projectJarPrefixes.any { prefix -> details.name.startsWith(prefix) } }
    }
    into(layout.buildDirectory.dir("docker/runtime-libs"))
}

val prepareDockerApplicationLibraries by tasks.registering(Sync::class) {
    dependsOn(":server:installDist")
    from(serverLibraries) {
        include { details -> projectJarPrefixes.any { prefix -> details.name.startsWith(prefix) } }
    }
    into(layout.buildDirectory.dir("docker/application-libs"))
}

/** 将稳定依赖和频繁变化的项目产物拆层，避免每次发布重复下载全部运行库。 */
tasks.register("prepareDockerContext") {
    dependsOn(prepareDockerRuntimeLibraries, prepareDockerApplicationLibraries)
}
