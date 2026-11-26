package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.muzzle.MuzzleDirective
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils
import org.eclipse.aether.DefaultRepositorySystemSession
import org.gradle.api.Describable
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.Serializable
import kotlin.random.Random

/** Tracks the selected coordinates as configuration inputs. */
internal abstract class MuzzlePlansValueSource :
  ValueSource<List<MuzzlePlannedVersion>, MuzzlePlansValueSource.Parameters>,
  Describable {
  interface Parameters : ValueSourceParameters {
    val directives: ListProperty<MuzzleDirective>
    val samplingSeed: Property<Long>
  }

  override fun getDisplayName() = "Muzzle coordinates"

  override fun obtain(): List<MuzzlePlannedVersion> {
    val startNanos = System.nanoTime()
    val system = MuzzleMavenRepoUtils.newRepositorySystem()
    val session = DefaultRepositorySystemSession(MuzzleMavenRepoUtils.newRepositorySystemSession(system)).apply {
      // Keep lookups in the ValueSource thread so Gradle does not track temporary Maven files.
      setConfigProperty("aether.metadataResolver.threads", 1)
    }
    val planner = MuzzleTaskPlanner(MavenMuzzleResolutionService(system, session, Random(parameters.samplingSeed.get())))
    val plans = parameters.directives.get().flatMapIndexed { index, directive ->
      planner.plan(listOf(directive)).map { plan ->
        MuzzlePlannedVersion(index, plan.artifact?.version, plan.directive.assertPass)
      }
    }
    Logging.getLogger(MuzzlePlansValueSource::class.java).info(
      "Muzzle planned ${plans.size} checks in ${(System.nanoTime() - startNanos) / 1_000_000}ms"
    )
    return plans
  }
}

internal data class MuzzlePlannedVersion(
  val directiveIndex: Int,
  val version: String?,
  val assertPass: Boolean,
) : Serializable
