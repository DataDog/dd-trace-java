package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.muzzle.MuzzleDirective
import org.eclipse.aether.artifact.Artifact

/**
 * Resolves muzzle directives into concrete artifacts and inverse directives.
 */
internal interface MuzzleResolutionService {
  /**
   * Resolves all dependency artifacts to test for the given directive.
   */
  fun resolveArtifacts(directive: MuzzleDirective): Set<Artifact>

  /**
   * Computes directives representing the inverse of the given directive.
   */
  fun inverseOf(directive: MuzzleDirective): Set<MuzzleDirective>
}
