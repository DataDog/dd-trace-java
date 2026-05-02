package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.muzzle.MuzzleDirective
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact

/**
 * Default [MuzzleResolutionService] implementation backed by Maven/Aether resolution.
 */
internal class MavenMuzzleResolutionService(
  private val system: RepositorySystem,
  private val session: RepositorySystemSession,
) : MuzzleResolutionService {
  override fun resolveArtifacts(directive: MuzzleDirective): Set<Artifact> {
    val range = MuzzleMavenRepoUtils.resolveVersionRange(directive, system, session)
    return MuzzleMavenRepoUtils.muzzleDirectiveToArtifacts(directive, range)
  }

  override fun inverseOf(directive: MuzzleDirective): Set<MuzzleDirective> =
    MuzzleMavenRepoUtils.inverseOf(directive, system, session)
}
