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
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenLocal()
    if (settings.extra.has("mavenRepositoryProxy")) {
      maven {
        url = uri(settings.extra["mavenRepositoryProxy"] as String)
        isAllowInsecureProtocol = true
      }
    }
    gradlePluginPortal()
    mavenCentral()
    // Hosts the Gradle Tooling API artifact, which the smoke-test plugin uses to
    // run nested Gradle builds without committing per-application wrappers.
    maven {
      url = uri("https://repo.gradle.org/gradle/libs-releases")
      content {
        includeGroup("org.gradle")
      }
    }
  }
}

rootProject.name = "build-logic"

include(":smoke-test")
