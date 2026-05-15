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

rootProject.name = "dd-trace-java-build-logic"
