plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
}

group = "top.e404.bangumi"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

tasks.register<Sync>("prepareDockerContext") {
    dependsOn(":server:installDist")
    from(project(":server").layout.buildDirectory.dir("install/bangumi-data"))
    into(layout.buildDirectory.dir("docker/app"))
}
