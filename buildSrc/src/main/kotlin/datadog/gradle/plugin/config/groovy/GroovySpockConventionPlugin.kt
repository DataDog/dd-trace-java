package datadog.gradle.plugin.config.groovy

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPlugin

@Suppress("unused")
class GroovySpockConventionPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    if (project == project.rootProject) {
      project.subprojects {
        this.pluginManager.apply(GroovySpockConventionPlugin::class.java)
      }
      return
    }

    val extension = project.extensions.create("groovySpock", GroovySpockExtension::class.java)

    project.plugins.withType(JavaPlugin::class.java) {
      configure(project, extension)
    }

    project.plugins.withType(JavaLibraryPlugin::class.java) {
      configure(project, extension)
    }
  }

  private fun configure(
    project: Project,
    extension: GroovySpockExtension
  ) {
    // TODO: check `afterEvaluate` is really needed.
    project.afterEvaluate {
      val groovyAlias = when (val groovyVersion = extension.groovyVersion.get()) {
        3 -> "groovy3"
        4 -> "groovy4"
        else -> error("Unsupported groovyVersion=$groovyVersion (only 3 or 4 supported)")
      }

      val catalogs = project.extensions.getByType(VersionCatalogsExtension::class.java)
      val libsCatalog = catalogs.named("libs")

      fun findLibrary(alias: String) =
        libsCatalog.findLibrary(alias)
          .orElseThrow { IllegalArgumentException("No library '$alias' in version catalog 'libs'") }

      val defaultLibs = listOf(
        findLibrary(groovyAlias),
        findLibrary("$groovyAlias-json"),
        findLibrary("spock-core-$groovyAlias"),
        findLibrary("objenesis")
      )

      fun configureDependencies(cfg: String, deps: List<Any>) =
        deps.forEach { project.dependencies.add(cfg, it) }

      // If nothing configured, setup default configuration
      val configurations = extension.configurations

      if (configurations.isEmpty()) {
          configureDependencies("testImplementation", defaultLibs)
      } else {
        configurations.forEach { (cfg, deps) ->
          deps.forEach { dep ->
            if (dep is String && "default".equals(dep, ignoreCase = true)) {
              configureDependencies(cfg, defaultLibs)
            }
            else {
              project.dependencies.add(cfg, dep)
            }
          }
        }
      }
    }
  }
}
