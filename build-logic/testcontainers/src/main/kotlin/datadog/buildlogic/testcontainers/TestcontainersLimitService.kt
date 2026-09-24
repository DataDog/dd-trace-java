package datadog.buildlogic.testcontainers

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Limits container test tasks across projects, including consumers of the legacy convention. */
abstract class TestcontainersLimitService : BuildService<BuildServiceParameters.None> {
  companion object {
    fun register(project: Project): Provider<TestcontainersLimitService> =
      project.gradle.sharedServices.registerIfAbsent("testcontainersLimit", TestcontainersLimitService::class.java) {
        maxParallelUsages.set(
          project.providers
            .gradleProperty("testcontainersMaxParallelUsages")
            .map(String::toInt)
            .orElse(2),
        )
      }
  }
}

/** Keeps the legacy usesService(testcontainersLimit) DSL backed by the plugin's shared quota. */
class TestcontainersLimitPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.extensions.extraProperties.set("testcontainersLimit", TestcontainersLimitService.register(project))
  }
}
