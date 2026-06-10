@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        // Hosts gg.gemstone:component and other Velocity-CTD artifacts not on Maven Central.
        maven("https://repo.velocityctd.com/releases") {
            name = "velocityctdReleases"
        }
    }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "conduit"

sequenceOf(
    "api",
    "native",
    "proxy",
    "luckperms-integration",
).forEach {
    val project = ":velocity-$it"
    include(project)
    project(project).projectDir = file(it)
}

val deprecatedConfigurateModule = ":deprecated-configurate3"
include(deprecatedConfigurateModule)
project(deprecatedConfigurateModule).projectDir = file("proxy/deprecated/configurate3")
