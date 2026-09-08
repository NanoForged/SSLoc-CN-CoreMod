pluginManagement {
    repositories {
        // SDG 插件（io.github.nanoforged.sectordevgradle.*）以 SNAPSHOT 发布在 mavenLocal
        mavenLocal()
        gradlePluginPortal()
    }
}

plugins {
    // Java toolchain 自动解析（本机无匹配 JDK 时经 foojay 下载）
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "SSLoc-CN-CoreMod"

include(":tablegen")
