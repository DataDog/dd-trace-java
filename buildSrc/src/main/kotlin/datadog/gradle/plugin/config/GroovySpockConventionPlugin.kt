package datadog.gradle.plugin.config

import groovy.lang.Closure
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.named
import javax.inject.Inject

/**
 * Convention plugin that will configure Groovy and Spock.
 *
 * If nothing configured, then default configuration will be applied.
 *
 *  Following dependencies will be added to `testImplementation`:
 *   * groovy-core
 *   * groovy-json
 *   * spock-core
 *   * objenesis
 */
@Suppress("unused")
class GroovySpockConventionPlugin : Plugin<Project> {
  abstract class GroovySpockProperties @Inject constructor(objects: ObjectFactory) {
    internal val compilerSettings: MapProperty<Int, List<String>> = objects.mapProperty()

    internal val configurations: MapProperty<String, List<Any>> = objects.mapProperty()

    fun configureGroovyCompiler(toolchainVersion: Int, vararg taskNames: String) {
      compilerSettings.put(toolchainVersion, taskNames.toList())
    }

    fun configureDependencies(taskName: String, vararg dependencies: Any) {
      configurations.put(taskName, dependencies.toList())
    }
  }

  override fun apply(project: Project) {
    if (project == project.rootProject) {
      project.subprojects {
        this.pluginManager.apply(GroovySpockConventionPlugin::class.java)
      }
      return
    }

    val extension = project.extensions.create("groovySpock", GroovySpockProperties::class.java)

    configureCompiler(project, extension)

    project.plugins.withType(JavaPlugin::class.java) {
      configureDependencies(project, extension)
    }

    project.plugins.withType(JavaLibraryPlugin::class.java) {
      configureDependencies(project, extension)
    }
  }

  private fun configureCompiler(project: Project, properties: GroovySpockProperties) {
    val compilerSettings = properties.compilerSettings

    if (compilerSettings.isPresent) {
      compilerSettings.get().forEach { (toolchainVersion: Int, taskNames: List<String>) ->
        taskNames.forEach { taskName ->
          project.tasks.named<GroovyCompile>(taskName) {
            (project.extra["configureCompiler"] as Closure<*>).call(this, toolchainVersion, JavaVersion.VERSION_1_8, null)
          }
        }
      }
    }
  }

  private fun configureDependencies(project: Project, properties: GroovySpockProperties) {
    // TODO: check `afterEvaluate` can be refactored to something else?
    project.afterEvaluate {
      val catalogs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

      fun findLibrary(alias: String) = catalogs.findLibrary(alias)
        .orElseThrow { IllegalArgumentException("Library not found in catalog for alias: $alias") }

      val defaultLibs = listOf(
        findLibrary("groovy"),
        findLibrary("groovy-json"),
        findLibrary("spock-core"),
        findLibrary("objenesis")
      )

      if (properties.configurations.isPresent) {
        val configurations = properties.configurations.get()
        if (configurations.isEmpty()) {
          // If nothing configured, setup default configuration, suitable in most cases.
          defaultLibs.forEach { project.dependencies.add("testImplementation", it) }
        } else {
          configurations.forEach { (cfg, deps) ->
            defaultLibs.forEach { project.dependencies.add(cfg, it) }
            deps.forEach { project.dependencies.add(cfg, it) }
          }
        }
      } else {
        // If nothing configured, setup default configuration, suitable in most cases.
        defaultLibs.forEach { project.dependencies.add("testImplementation", it) }
      }
    }
  }
}
