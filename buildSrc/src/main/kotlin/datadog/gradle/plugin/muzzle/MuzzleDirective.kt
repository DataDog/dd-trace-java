package datadog.gradle.plugin.muzzle

import groovy.lang.Closure
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.util.version.GenericVersionScheme
import org.eclipse.aether.version.InvalidVersionSpecificationException
import org.gradle.api.Action
import java.io.Serializable

/**
 * A pass or fail directive for a single dependency.
 */
open class MuzzleDirective : Serializable {
  /**
   * Name is optional and is used to further define the scope of a directive. The motivation for this is that this
   * plugin creates a config for each of the dependencies under test with name '...-<group_id>-<artifact_id>-<version>'.
   * The problem is that if we want to test multiple times the same configuration under different conditions, e.g.
   * with different extra dependencies, the plugin would throw an error as it would try to create several times the
   * same config. This property can be used to differentiate those config names for different directives.
   */
  var name: String? = null
  var group: String? = null
  var module: String? = null
  var classifier: String? = null
  var versions: String? = null
  var skipVersions: MutableSet<String> = HashSet()
  var additionalDependencies: MutableList<String> = ArrayList()
  internal var additionalRepositories: MutableList<Triple<String, String, String>> = ArrayList()
  internal var excludedDependencies: MutableList<String> = ArrayList()
  internal var mavenPomOverrideConfig: MavenPomOverrides? = null
  var assertPass: Boolean = false
  var assertInverse: Boolean = false
  var skipFromReport: Boolean = false
  internal var isCoreJdk: Boolean = false
  var includeSnapshots: Boolean = false
  var javaVersion: String? = null

  fun coreJdk(version: String? = null) {
    isCoreJdk = true
    javaVersion = version
  }

  /**
   * Adds extra dependencies to the current muzzle test.
   *
   * @param compileString An extra dependency in the gradle canonical form: '<group_id>:<artifact_id>:<version_id>'.
   */
  fun extraDependency(compileString: String) {
    additionalDependencies.add(compileString)
  }

  /**
   * Adds extra repositories to the current muzzle test.
   *
   * @param id the repository id
   * @param url the url of the repository
   * @param type the type of repository, defaults to "default"
   */
  fun extraRepository(id: String, url: String, type: String = "default") {
    additionalRepositories.add(Triple(id, type, url))
  }

  /**
   * Adds transitive dependencies to exclude from the current muzzle test.
   *
   * @param excludeString A dependency in the gradle canonical form: '<group_id>:<artifact_id>'.
   */
  fun excludeDependency(excludeString: String) {
    excludedDependencies.add(excludeString)
  }

  /**
   * Rewrites dependency versions declared in the published POMs of this directive's artifacts.
   *
   * Use this when a dependency muzzle does not care about makes a POM unresolvable, for instance when
   * upstream references a version it never published. Gradle resolution rules cannot help there: a bad
   * version in a parent POM or an imported BOM breaks POM parsing before any rule runs, so the POM is
   * patched after download and before Maven builds the model. Artifacts matched by
   * [MavenPomOverrides.artifactVersions] are consequently resolved by Maven instead of Gradle.
   * Overrides apply only to this directive, not to inverse directives generated from it.
   */
  fun mavenPomOverrides(action: Action<in MavenPomOverrides>) {
    mavenPomOverrideConfig = MavenPomOverrides().also(action::execute).also(MavenPomOverrides::validate)
  }

  internal fun requiresMavenResolution(version: String): Boolean {
    val overrides = mavenPomOverrideConfig ?: return false
    val versionScheme = GenericVersionScheme()
    return versionScheme.parseVersionConstraint(overrides.artifactVersions)
      .containsVersion(versionScheme.parseVersion(version))
  }

  /**
   * Get the list of repositories to use for this muzzle directive.
   *
   * @param defaults the default repositories
   * @return a list of the default repositories followed by any additional repositories
   */
  internal fun getRepositories(defaults: List<RemoteRepository>): List<RemoteRepository> {
    return if (additionalRepositories.isEmpty()) {
      defaults
    } else {
      ArrayList<RemoteRepository>(defaults.size + additionalRepositories.size).apply {
        addAll(defaults)
        addAll(additionalRepositories.map { (id, type, url) ->
          RemoteRepository.Builder(id, type, url).build()
        })
      }
    }
  }

  /**
   * Slug of directive name.
   *
   * @return A slug of the name or an empty string if name is empty. E.g. 'My Directive' --> 'My-Directive'
   */
  val nameSlug: String
    get() = name?.trim()?.replace(Regex("[^a-zA-Z0-9]+"), "-") ?: ""

  override fun toString(): String = if (isCoreJdk) {
    "${if (assertPass) "Pass" else "Fail"}-core-jdk"
  } else {
    "${if (assertPass) "pass" else "fail"} $group:$module:$versions"
  }
}

/** Overrides dependency versions embedded in published Maven POMs for selected artifact versions. */
open class MavenPomOverrides : Serializable {
  /**
   * Maven version range selecting which of this directive's artifact versions get their POMs rewritten,
   * for example `"[7.4,8)"`. Versions outside the range are resolved by Gradle as usual. Required, so
   * that rewriting POMs of versions that do not need it is always a deliberate choice; use `"[,)"` to
   * opt every version of the directive in.
   */
  var artifactVersions: String = ""
  internal val dependencyVersionOverrides: MutableMap<String, MutableMap<String, String>> =
    LinkedHashMap()
  internal val dependencyVersionRangeOverrides:
    MutableMap<String, MutableList<MavenVersionRangeOverride>> = LinkedHashMap()

  /** Declares the version replacements to apply to dependencies of [group]. Matched exactly. */
  fun dependency(group: String, action: Action<in MavenDependencyOverride>) {
    val dependencyOverride = MavenDependencyOverride().also(action::execute)
    addDependencyOverride(group, dependencyOverride)
  }

  /** Groovy DSL overload of [dependency], so assignments delegate to the override. */
  fun dependency(group: String, closure: Closure<*>) {
    val dependencyOverride = MavenDependencyOverride()
    closure.delegate = dependencyOverride
    closure.resolveStrategy = Closure.DELEGATE_FIRST
    closure.call(dependencyOverride)
    addDependencyOverride(group, dependencyOverride)
  }

  private fun addDependencyOverride(
    group: String,
    dependencyOverride: MavenDependencyOverride
  ) {
    require(
      dependencyOverride.matchVersions.isNotEmpty() ||
        dependencyOverride.matchPattern.isNotBlank() ||
        dependencyOverride.matchVersionRanges.isNotEmpty()
    ) {
      "At least one matchVersions, matchPattern, or matchVersionRanges entry is required " +
        "for dependency group '$group'"
    }

    require(dependencyOverride.replacement.isNotBlank()) {
      "A replacement is required for dependency group '$group'"
    }

    require("*" !in dependencyOverride.matchVersions) {
      "'*' is not supported in matchVersions for dependency group '$group'; " +
        "use matchVersionRanges for concrete versions"
    }

    val versionScheme = GenericVersionScheme()
    dependencyOverride.matchVersionRanges.forEach { range ->
      try {
        versionScheme.parseVersionConstraint(range)
      } catch (e: InvalidVersionSpecificationException) {
        throw IllegalArgumentException(
          "Invalid matchVersionRanges entry '$range' for dependency group '$group'",
          e
        )
      }
    }

    if (dependencyOverride.matchVersions.isNotEmpty() || dependencyOverride.matchPattern.isNotBlank()) {
      val groupOverrides = dependencyVersionOverrides.getOrPut(group, ::LinkedHashMap)
      dependencyOverride.matchVersions.forEach { groupOverrides[it] = dependencyOverride.replacement }
      if (dependencyOverride.matchPattern.isNotBlank()) {
        groupOverrides[dependencyOverride.matchPattern] = dependencyOverride.replacement
      }
    }
    if (dependencyOverride.matchVersionRanges.isNotEmpty()) {
      val groupOverrides = dependencyVersionRangeOverrides.getOrPut(group, ::ArrayList)
      dependencyOverride.matchVersionRanges.forEach { range ->
        groupOverrides.add(MavenVersionRangeOverride(range, dependencyOverride.replacement))
      }
    }
  }

  /** Checks the block is complete, once the whole `mavenPomOverrides { }` closure has been evaluated. */
  internal fun validate() {
    require(artifactVersions.isNotBlank()) {
      "artifactVersions is required in mavenPomOverrides, e.g. artifactVersions = \"[7.4,8)\", " +
        "or \"[,)\" to cover every version of the directive"
    }

    try {
      GenericVersionScheme().parseVersionConstraint(artifactVersions)
    } catch (e: InvalidVersionSpecificationException) {
      throw IllegalArgumentException(
        "Invalid artifactVersions Maven version range '$artifactVersions' in mavenPomOverrides",
        e
      )
    }

    require(dependencyVersionOverrides.isNotEmpty() || dependencyVersionRangeOverrides.isNotEmpty()) {
      "At least one dependency(group) { } override is required in mavenPomOverrides"
    }
  }
}

  /** Version replacement values for a dependency group in [MavenPomOverrides]. */
open class MavenDependencyOverride : Serializable {
  /**
   * Concrete version strings to replace, matched exactly, for example `"9.4.59"`.
   *
   * Use [matchPattern] for raw non-version text such as an unexpanded Maven property placeholder,
   * or [matchVersionRanges] to select concrete versions with a Maven version range.
   */
  var matchVersions: MutableList<String> = ArrayList()

  /** Raw POM version text to replace literally, for example `'${jetty.version}'`. */
  var matchPattern: String = ""

  /** Maven version ranges to replace, for example `"[9.4.59,9.5)"`. */
  var matchVersionRanges: MutableList<String> = ArrayList()

  /** Version to write into the POM in place of every match. Required. */
  var replacement: String = ""
}

internal data class MavenVersionRangeOverride(
  val range: String,
  val replacement: String
) : Serializable
