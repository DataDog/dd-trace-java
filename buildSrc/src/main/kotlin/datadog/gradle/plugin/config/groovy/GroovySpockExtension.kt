package datadog.gradle.plugin.config.groovy

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Configuration for Groovy and Spock.
 */
abstract class GroovySpockExtension @Inject constructor(
  objects: ObjectFactory
) {
  internal val configurations: MutableMap<String, MutableList<Any>> = ConcurrentHashMap()

  internal val groovyVersion: Property<Int> =
    objects.property(Int::class.java)
      .convention(4)

  fun groovyVersion(version: Int) {
    groovyVersion.set(version)
  }

  fun configureDependency(taskName: String, dependency: Any) {
    configurations.computeIfAbsent(taskName) { mutableListOf() }.add(dependency)
  }

  fun configureDefaultDependencies(taskName: String) {
    configureDependency(taskName, "default")
  }
}

