pluginManagement {
  repositories {
    mavenLocal()
    providers.gradleProperty("gradlePluginProxy").orNull?.let { proxy ->
      maven {
        url = uri(proxy)
        isAllowInsecureProtocol = true
      }
    }
    val mavenRepositoryProxy = providers.gradleProperty("mavenRepositoryProxy").orNull
    mavenRepositoryProxy?.let { proxy ->
      maven {
        url = uri(proxy)
        isAllowInsecureProtocol = true
      }
    }
    gradlePluginPortal()
    // TODO: temporary fix for Maven Central rate limiting
    if (mavenRepositoryProxy == null) {
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
    val mavenRepositoryProxy = providers.gradleProperty("mavenRepositoryProxy").orNull
    mavenRepositoryProxy?.let { proxy ->
      maven {
        url = uri(proxy)
        isAllowInsecureProtocol = true
      }
    }
    gradlePluginPortal()
    // TODO: temporary fix for Maven Central rate limiting
    if (mavenRepositoryProxy == null) {
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
