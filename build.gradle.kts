import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "devicemonitor"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jfree:jfreechart:1.5.4")
    implementation("org.apache.pdfbox:pdfbox:2.0.31")
}

kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("devicemonitor.MainKt")
}

// Self-contained runnable jar: java -jar build/libs/adb-device-monitor-1.0.0-all.jar
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val buildTime = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())
    manifest {
        attributes(
            "Main-Class" to "devicemonitor.MainKt",
            "Implementation-Version" to project.version.toString(),
            "Build-Time" to buildTime
        )
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
