package datadog.gradle.plugin.muzzle

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.newInstance
import java.util.Locale
import javax.inject.Inject

/**
 * Muzzle extension containing all pass and fail directives.
 */
abstract class MuzzleExtension @Inject constructor(
  private val objectFactory: ObjectFactory,
  providers: ProviderFactory
) {
  val directives: MutableList<MuzzleDirective> = ArrayList()
  private val additionalRepositories: MutableList<Triple<String, String, String>> = ArrayList()

  /** Publication cooldown; explicit DSL configuration overrides property and environment defaults. */
  val minimumDependencyAgeHours: Property<Int> = objectFactory.property(Int::class.java).convention(
    providers.gradleProperty("muzzleMinDependencyAgeHours")
      .orElse(providers.environmentVariable("MIN_DEPENDENCY_AGE_HOURS"))
      .map(::parseMinimumDependencyAgeHours)
      .orElse(48)
  )

  /** Whether an aggregate invocation should plan this module; conventions own selection policy. */
  val includeInAggregate: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(true)

  fun pass(action: Action<in MuzzleDirective>) {
    val pass = objectFactory.newInstance<MuzzleDirective>()
    action.execute(pass)
    postConstruct(pass)
    pass.assertPass = true
    directives.add(pass)
  }

  fun fail(action: Action<in MuzzleDirective>) {
    val fail = objectFactory.newInstance<MuzzleDirective>()
    action.execute(fail)
    postConstruct(fail)
    fail.assertPass = false
    directives.add(fail)
  }

  /**
   * Adds extra repositories to the current muzzle section. Repositories will only be added to directives
   * created after this.
   *
   * @param id the repository id
   * @param url the url of the repository
   * @param type the type of repository, defaults to "default"
   */
  @JvmOverloads
  fun extraRepository(id: String, url: String, type: String = "default") {
    additionalRepositories.add(Triple(id, type, url))
  }

  private fun postConstruct(directive: MuzzleDirective) {
    // Make skipVersions case insensitive.
    directive.skipVersions = directive.skipVersions.map { it.lowercase(Locale.ROOT) }.toMutableSet()
    // Add existing repositories
    directive.additionalRepositories.addAll(additionalRepositories)
  }
}

internal fun parseMinimumDependencyAgeHours(raw: String): Int = raw.toIntOrNull()?.takeIf { it >= 0 }
  ?: throw GradleException(
    "muzzleMinDependencyAgeHours / MIN_DEPENDENCY_AGE_HOURS must be a non-negative integer"
  )
