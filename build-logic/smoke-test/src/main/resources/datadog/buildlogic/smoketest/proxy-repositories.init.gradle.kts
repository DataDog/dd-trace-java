import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.Settings

gradle.beforeSettings(Action<Settings> {
  val gradlePluginProxy = providers.gradleProperty("gradlePluginProxy").orNull
  val mavenRepositoryProxy = providers.gradleProperty("mavenRepositoryProxy").orNull
  val mavenCentralUrls = setOf(
    "https://repo.maven.apache.org/maven2",
    "https://repo1.maven.org/maven2",
  )

  fun RepositoryHandler.redirectMavenCentral() {
    val proxy = mavenRepositoryProxy?.takeIf { it.isNotBlank() } ?: return
    withType(MavenArtifactRepository::class.java).configureEach {
      if (url.toString().trimEnd('/') in mavenCentralUrls) {
        url = java.net.URI(proxy)
        isAllowInsecureProtocol = true
      }
    }
  }

  buildscript.repositories.redirectMavenCentral()
  pluginManagement.repositories.redirectMavenCentral()
  dependencyResolutionManagement.repositories.redirectMavenCentral()

  pluginManagement {
    repositories {
      mavenLocal()
      gradlePluginProxy?.takeIf { it.isNotBlank() }?.let { proxy ->
        maven {
          url = java.net.URI(proxy)
          isAllowInsecureProtocol = true
        }
      }
      mavenRepositoryProxy?.takeIf { it.isNotBlank() }?.let { proxy ->
        maven {
          url = java.net.URI(proxy)
          isAllowInsecureProtocol = true
        }
      }
      gradlePluginPortal()
      if (mavenRepositoryProxy.isNullOrBlank()) {
        mavenCentral()
      }
    }
  }

  gradle.beforeProject(Action<Project> {
    repositories.redirectMavenCentral()
    buildscript.repositories.redirectMavenCentral()
    repositories {
      mavenLocal()
      mavenRepositoryProxy?.takeIf { it.isNotBlank() }?.let { proxy ->
        maven {
          url = java.net.URI(proxy)
          isAllowInsecureProtocol = true
        }
      }
      if (mavenRepositoryProxy.isNullOrBlank()) {
        mavenCentral()
      }
    }
  })
})
