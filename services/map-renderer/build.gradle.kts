import java.security.MessageDigest

// Prototype of the detailed map renderer (services/discord-bot/docs/map-images.md).
//
// Two source sets with different classpaths, on purpose:
// - `export` runs at build time with Mindustry's content loaded and writes the content table
//   and the sprite pack from the client's atlas;
// - `main` is the renderer. It has no Mindustry classes at all: it reads those two files and
//   decodes maps with libs/msav.
plugins {
    java
    application
}

val mindustryVersion = libs.versions.mindustry.get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    maven { url = uri("https://maven.x-core.org/releases") }
    mavenCentral()
    maven("https://maven.xpdustry.com/mindustry")
    maven("https://raw.githubusercontent.com/Zelaux/MindustryRepo/master/repository")
    maven(url = "https://www.jitpack.io")
}

val export = sourceSets.create("export")

dependencies {
    "exportImplementation"(libs.mindustry.core)
    "exportImplementation"(libs.arc.core)

    // The map reader, and Arc for its JSON parser and the engine's random generator.
    implementation(project(":libs:msav"))
    implementation(libs.arc.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("org.xcore.maprenderer.Bench")
}

// The client release of the same Mindustry version: its atlas is the only source of sprites.
// Pass -PmindustryClientJar=<path> to use a local copy instead of downloading it.
val clientJarSha256 = "3f0afdf1fc2e7ac82be5d20217e52bdcb3d423a7caf50b28c124ef81b156d16a"
val clientJar = layout.buildDirectory.file("client/Mindustry-$mindustryVersion.jar")

val downloadClient = tasks.register("downloadClient") {
    description = "Downloads the Mindustry client release and checks its SHA-256."
    val local = providers.gradleProperty("mindustryClientJar")
    outputs.file(clientJar)
    doLast {
        val target = clientJar.get().asFile
        target.parentFile.mkdirs()
        if (local.isPresent) {
            file(local.get()).copyTo(target, overwrite = true)
        } else {
            uri("https://github.com/Anuken/Mindustry/releases/download/$mindustryVersion/Mindustry.jar")
                .toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(target.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        check(digest == clientJarSha256) { "Unexpected Mindustry client checksum: $digest" }
    }
}

val renderData = layout.buildDirectory.dir("render-data")

val exportContent = tasks.register<JavaExec>("exportContent") {
    description = "Writes content.json and sprites.pack for the renderer."
    dependsOn(downloadClient)
    classpath = export.runtimeClasspath
    mainClass.set("org.xcore.maprenderer.export.ContentExport")
    systemProperty("mindustry.version", mindustryVersion)
    inputs.property("mindustryVersion", mindustryVersion)
    inputs.file(clientJar)
    outputs.dir(renderData)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(clientJar.get().asFile.absolutePath, renderData.get().asFile.absolutePath)
    })
}

tasks.named<JavaExec>("run") {
    dependsOn(exportContent)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(exportContent)
    systemProperty("renderData", renderData.get().asFile.absolutePath)
    systemProperty("clientJar", clientJar.get().asFile.absolutePath)
}
