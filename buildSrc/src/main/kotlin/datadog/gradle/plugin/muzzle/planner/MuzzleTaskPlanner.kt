package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.muzzle.MuzzleDirective
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession

/**
 * Expands configured directives into ordered task plans.
 */
internal class MuzzleTaskPlanner(
  private val resolutionService: MuzzleResolutionService,
) {
  companion object {
    fun from(system: RepositorySystem, session: RepositorySystemSession): MuzzleTaskPlanner =
      MuzzleTaskPlanner(MavenMuzzleResolutionService(system, session))
  }

  /**
   * Expands declared muzzle directives into executable task plans.
   *
   * Planning rules:
   * - Core-JDK directives (`coreJdk()`) create exactly one [MuzzleTaskPlan] with `artifact = null`.
   * - Non-core directives are resolved with [MuzzleResolutionService.resolveArtifacts], creating one
   *   plan per resolved artifact.
   * - If a non-core directive has `assertInverse = true`, inverse directives are obtained from
   *   [MuzzleResolutionService.inverseOf], then each inverse directive is resolved and expanded with
   *   the same one-plan-per-artifact rule.
   *
   * Ordering:
   * - The input [directives] order is preserved.
   * - Direct plans for a directive are emitted before its inverse plans.
   * - Artifact plan order follows the iteration order returned by the resolution service.
   *
   * No de-duplication is performed here. If needed, de-duplication must be handled by callers or by
   * the resolution service implementation.
   */
  fun plan(directives: List<MuzzleDirective>): List<MuzzleTaskPlan> = buildList {
    directives.forEach { directive ->
      if (directive.isCoreJdk) {
        add(MuzzleTaskPlan(directive, null))
      } else {
        resolutionService.resolveArtifacts(directive).forEach { artifact ->
          add(MuzzleTaskPlan(directive, artifact))
        }
        if (directive.assertInverse) {
          resolutionService.inverseOf(directive).forEach { inverseDirective ->
            resolutionService.resolveArtifacts(inverseDirective).forEach { artifact ->
              add(MuzzleTaskPlan(inverseDirective, artifact))
            }
          }
        }
      }
    }
  }
}
