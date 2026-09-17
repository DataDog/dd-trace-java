import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.Settings
import java.net.URI

gradle.beforeSettings(Action<Settings> {
  val gradlePluginProxy = providers.gradleProperty("gradlePluginProxy").orNull
  val mavenRepositoryProxy = providers.gradleProperty("mavenRepositoryProxy").orNull
  val mavenCentralUrls = setOf(
    "https://repo.maven.apache.org/maven2",
    "https://repo1.maven.org/maven2",
  )
  // Marks the proxy repository this script injects, so the deduplication below can tell it apart
  // from a redirected mavenCentral() the nested build declared itself.
  val injectedProxyName = "ddSmokeTestMavenRepositoryProxy"

  fun RepositoryHandler.redirectMavenCentral() {
    val proxy = mavenRepositoryProxy?.takeIf { it.isNotBlank() } ?: return
    withType(MavenArtifactRepository::class.java).configureEach {
      // A repository declared without a URL has a null one until Gradle validates it; leave it be
      // so the nested build reports that itself instead of failing inside this init script.
      val repositoryUrl = url?.toString()?.trimEnd('/')
      if (repositoryUrl != null && repositoryUrl in mavenCentralUrls) {
        url = URI(proxy)
        isAllowInsecureProtocol = true
      }
    }
  }

  fun RepositoryHandler.removeDuplicateMavenProxy() {
    val proxyUrl = mavenRepositoryProxy?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: return
    val proxies = withType(MavenArtifactRepository::class.java)
      .filter { it.url?.toString()?.trimEnd('/') == proxyUrl }
    // Keep the injected repository: it is the only one known to be unrestricted, since a declared
    // mavenCentral() may carry content filters that redirectMavenCentral() does not lift.
    if (proxies.none { it.name == injectedProxyName }) {
      return
    }
    proxies.filter { it.name != injectedProxyName }.forEach { remove(it) }
  }

  buildscript.repositories.redirectMavenCentral()
  pluginManagement.repositories.redirectMavenCentral()
  dependencyResolutionManagement.repositories.redirectMavenCentral()

  pluginManagement {
    repositories {
      mavenLocal()
      gradlePluginProxy?.takeIf { it.isNotBlank() }?.let { proxy ->
        maven {
          url = URI(proxy)
          isAllowInsecureProtocol = true
        }
      }
      mavenRepositoryProxy?.takeIf { it.isNotBlank() }?.let { proxy ->
        maven {
          name = injectedProxyName
          url = URI(proxy)
          isAllowInsecureProtocol = true
        }
      }
      gradlePluginPortal()
      if (mavenRepositoryProxy.isNullOrBlank()) {
        mavenCentral()
      }
    }
  }

  gradle.settingsEvaluated(Action<Settings> {
    pluginManagement.repositories.removeDuplicateMavenProxy()
  })

  gradle.beforeProject(Action<Project> {
    repositories.redirectMavenCentral()
    buildscript.repositories.redirectMavenCentral()
    repositories {
      mavenLocal()
      mavenRepositoryProxy?.takeIf { it.isNotBlank() }?.let { proxy ->
        maven {
          name = injectedProxyName
          url = URI(proxy)
          isAllowInsecureProtocol = true
        }
      }
      if (mavenRepositoryProxy.isNullOrBlank()) {
        mavenCentral()
      }
    }
  })

  gradle.afterProject(Action<Project> {
    repositories.removeDuplicateMavenProxy()
  })
})
