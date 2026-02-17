package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.muzzle.MuzzleDirective
import org.eclipse.aether.artifact.Artifact

/**
 * Planned unit of muzzle work for task creation.
 *
 * For `coreJdk()` directives, [artifact] is `null`.
 */
internal data class MuzzleTaskPlan(
  val directive: MuzzleDirective,
  val artifact: Artifact?,
)
