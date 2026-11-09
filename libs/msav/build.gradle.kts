// The detached .msav reader: decodes Mindustry maps without the engine, its content mapper or
// any deserializer. Used by the plugin (inspection, previews) and by services/map-renderer.
//
// It needs no Mindustry classes. Arc's JSON parser is compileOnly: the game server provides Arc
// to the plugin, and the renderer declares it itself.
plugins {
    `java-library`
}

group = "org.xcore"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://maven.xpdustry.com/mindustry")
    maven("https://raw.githubusercontent.com/Zelaux/MindustryRepo/master/repository")
    maven("https://www.jitpack.io")
}

dependencies {
    compileOnly(libs.arc.core)
}
