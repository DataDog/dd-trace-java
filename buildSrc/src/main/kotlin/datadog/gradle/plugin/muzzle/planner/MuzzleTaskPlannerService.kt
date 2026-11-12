package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.muzzle.MuzzleDirective
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Shares Maven planners within a build while isolating modules with different repository settings. */
internal abstract class MuzzleTaskPlannerService : BuildService<BuildServiceParameters.None> {
  private data class RepositorySettings(val repositories: List<List<Triple<String, String, String>>>)

  private val planners = mutableMapOf<RepositorySettings, MuzzleTaskPlanner>()

  /** Serializes cache access and planning so callers never use a Maven session concurrently. */
  @Synchronized
  fun plan(directives: List<MuzzleDirective>): List<MuzzleTaskPlan> {
    // Repository IDs can be reused for different URLs, so keep their local metadata separate.
    val settings = RepositorySettings(directives.map { it.additionalRepositories.toList() }.distinct())
    val planner = planners.getOrPut(settings) {
      val system = MuzzleMavenRepoUtils.newRepositorySystem()
      val session = MuzzleMavenRepoUtils.newRepositorySystemSession(system)
      MuzzleTaskPlanner.from(system, session)
    }
    return planner.plan(directives)
  }
}
