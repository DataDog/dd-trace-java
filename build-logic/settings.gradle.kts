pluginManagement {
  repositories {
    mavenLocal()
    if (settings.extra.has("gradlePluginProxy")) {
      maven {
        url = uri(settings.extra["gradlePluginProxy"] as String)
        isAllowInsecureProtocol = true
      }
    }
    if (settings.extra.has("mavenRepositoryProxy")) {
      maven {
        url = uri(settings.extra["mavenRepositoryProxy"] as String)
        isAllowInsecureProtocol = true
      }
    }
    gradlePluginPortal()
    // TODO: temporary fix for Maven Central rate limiting
    if (!settings.extra.has("mavenRepositoryProxy")) {
      mavenCentral()
    }
  }
}

dependencyResolutionManagement {
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
  repositories {
    mavenLocal()
    if (settings.extra.has("mavenRepositoryProxy")) {
      maven {
        url = uri(settings.extra["mavenRepositoryProxy"] as String)
        isAllowInsecureProtocol = true
      }
    }
    gradlePluginPortal()
    // TODO: temporary fix for Maven Central rate limiting
    if (!settings.extra.has("mavenRepositoryProxy")) {
      mavenCentral()
    }
    // Hosts gradle-tooling-api; used by the smoke-test plugin to run nested Gradle builds
    // pinned to older Gradle versions.
    maven {
      url = uri("https://repo.gradle.org/gradle/libs-releases")
      content {
        includeGroup("org.gradle")
      }
    }
  }
}

rootProject.name = "build-logic"

include(":conventions")
include(":smoke-test")
