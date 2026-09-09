import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

application {
    mainClass.set("top.e404.bangumi.server.MainKt")
    applicationName = "bangumi-data"
}

dependencies {
    implementation(project(":api-model"))
    implementation("io.ktor:ktor-server-core-jvm:2.3.13")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.13")
    implementation("io.ktor:ktor-server-auth-jvm:2.3.13")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.13")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.13")
    implementation("io.ktor:ktor-client-cio-jvm:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:2.3.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:5.0.1")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.13")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:2.3.13")
}

tasks.test {
    useJUnitPlatform()
    providers.systemProperty("bangumi.liveTest").orNull?.let { systemProperty("bangumi.liveTest", it) }
    providers.systemProperty("bangumi.fullArchive").orNull?.let { systemProperty("bangumi.fullArchive", it) }
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
}
