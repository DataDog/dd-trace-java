package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.muzzle.MuzzleDirective
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils
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
    val requests: ListProperty<MuzzlePlanningRequest>
    val samplingSeed: Property<Long>
  }

  override fun getDisplayName() = "Muzzle coordinates"

  override fun obtain(): List<MuzzlePlannedVersion> {
    val startNanos = System.nanoTime()
    val system = MuzzleMavenRepoUtils.newRepositorySystem()
    val session = MuzzleMavenRepoUtils.newRepositorySystemSession(system)
    val random = Random(parameters.samplingSeed.get())
    val requests = parameters.requests.get()
    val planner = MuzzleTaskPlanner(MavenMuzzleResolutionService(system, session, random = random))
    val plans = requests.flatMap { request ->
      request.directives.flatMapIndexed { index, directive ->
        planner.plan(listOf(directive)).map { plan ->
          MuzzlePlannedVersion(request.projectPath, index, plan.artifact?.version, plan.directive.assertPass)
        }
      }
    }
    Logging.getLogger(MuzzlePlansValueSource::class.java).info(
      "Muzzle planned ${plans.size} checks for ${requests.size} modules in " +
        "${(System.nanoTime() - startNanos) / 1_000_000}ms"
    )
    return plans
  }
}

internal data class MuzzlePlanningRequest(
  val projectPath: String,
  val directives: List<MuzzleDirective>
) : Serializable

internal data class MuzzlePlannedVersion(
  val projectPath: String,
  val directiveIndex: Int,
  val version: String?,
  val assertPass: Boolean
) : Serializable
