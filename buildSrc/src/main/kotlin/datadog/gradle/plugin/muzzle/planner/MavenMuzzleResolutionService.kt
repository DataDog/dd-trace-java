package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.muzzle.MuzzleDependencyAge
import datadog.gradle.plugin.muzzle.MuzzleDirective
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.version.Version
import org.gradle.api.GradleException
import kotlin.random.Random

/**
 * Default [MuzzleResolutionService] implementation backed by Maven/Aether resolution.
 */
internal class MavenMuzzleResolutionService(
  private val system: RepositorySystem,
  private val session: RepositorySystemSession,
  private val dependencyAge: MuzzleDependencyAge,
  private val defaultRepos: List<RemoteRepository> = MuzzleMavenRepoUtils.defaultMuzzleRepos(),
  private val random: Random = Random.Default
) : MuzzleResolutionService {
  override fun resolveArtifacts(directive: MuzzleDirective): Set<Artifact> {
    val range = MuzzleMavenRepoUtils.resolveVersionRange(directive, system, session, defaultRepos)
    return MuzzleMavenRepoUtils.muzzleDirectiveToArtifacts(directive, range, eligibility(directive), random).ifEmpty {
      throw GradleException(
        "No eligible muzzle artifacts for ${directive.group}:${directive.module} ${directive.versions} " +
          "after version exclusions and ${dependencyAge.minimumAgeHours}h publication cooldown. " +
          "See deferred-version warnings; use -PmuzzleMinDependencyAgeHours=0 to bypass the cooldown."
      )
    }
  }

  override fun inverseOf(directive: MuzzleDirective): Set<MuzzleDirective> = MuzzleMavenRepoUtils.inverseOf(directive, system, session, defaultRepos, eligibility(directive), random)

  private fun eligibility(directive: MuzzleDirective): (Version) -> Boolean {
    val group = requireNotNull(directive.group)
    val module = requireNotNull(directive.module)
    val repositories = directive.getRepositories(defaultRepos)
    return { version -> dependencyAge.isEligible(group, module, version.toString(), repositories) }
  }
}
