import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.Settings
import java.net.URI

// Routes the public repositories declared by TestKit builds (see GradleFixture) through the
// mirrors CI configures, so buildSrc tests do not hit Maven Central and get rate limited.
//
// This deliberately replaces the well-known public URLs rather than prepending a mirror. Several
// tests point MAVEN_REPOSITORY_PROXY at a fake file: repository and rely on it fully shadowing
// Maven Central.
//
// Covers project, buildscript and settings-level repository containers. It does not reach
// included builds (no TestKit fixture uses one) or the implicit gradlePluginPortal() default.

val mavenProxyUrl = System.getenv("MAVEN_REPOSITORY_PROXY")?.trim()?.takeIf { it.isNotEmpty() }
val pluginProxyUrl = System.getenv("GRADLE_PLUGIN_PROXY")?.trim()?.takeIf { it.isNotEmpty() }

if (mavenProxyUrl != null || pluginProxyUrl != null) {
  val mavenCentralUrls = setOf(
    "https://repo.maven.apache.org/maven2",
    "https://repo1.maven.org/maven2",
  )
  val pluginPortalUrls = setOf("https://plugins.gradle.org/m2")

  fun RepositoryHandler.redirectPublicRepositories() {
    withType(MavenArtifactRepository::class.java).configureEach {
      val repositoryUrl = url.toString().trimEnd('/')
      val target = when {
        mavenProxyUrl != null && repositoryUrl in mavenCentralUrls -> mavenProxyUrl
        pluginProxyUrl != null && repositoryUrl in pluginPortalUrls -> pluginProxyUrl
        else -> null
      }
      if (target != null) {
        url = URI(target)
        isAllowInsecureProtocol = target.startsWith("http://")
      }
    }
  }

  gradle.beforeSettings(Action<Settings> {
    buildscript.repositories.redirectPublicRepositories()
    pluginManagement.repositories.redirectPublicRepositories()
    dependencyResolutionManagement.repositories.redirectPublicRepositories()
  })

  gradle.beforeProject(Action<Project> {
    repositories.redirectPublicRepositories()
    buildscript.repositories.redirectPublicRepositories()
  })
}
