package datadog.gradle.plugin.muzzle

import org.eclipse.aether.repository.RemoteRepository

/**
 * A pass or fail directive for a single dependency.
 */
open class MuzzleDirective {
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
  var additionalRepositories: MutableList<RemoteRepository> = ArrayList()
  var excludedDependencies: MutableList<String> = ArrayList()
  var assertPass: Boolean = false
  var assertInverse: Boolean = false
  var skipFromReport: Boolean = false
  var coreJdk: Boolean = false
  var includeSnapshots: Boolean = false
  var javaVersion: String? = null

  fun coreJdk(version: String? = null) {
    coreJdk = true
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
    additionalRepositories.add(RemoteRepository.Builder(id, type, url).build())
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
   * Get the list of repositories to use for this muzzle directive.
   *
   * @param defaults the default repositories
   * @return a list of the default repositories followed by any additional repositories
   */
  fun getRepositories(defaults: List<RemoteRepository>): List<RemoteRepository> {
    return if (additionalRepositories.isEmpty()) {
      defaults
    } else {
      ArrayList<RemoteRepository>(defaults.size + additionalRepositories.size).apply {
        addAll(defaults)
        addAll(additionalRepositories)
      }
    }
  }

  /**
   * Slug of directive name.
   *
   * @return A slug of the name or an empty string if name is empty. E.g. 'My Directive' --> 'My-Directive'
   */
  fun getNameSlug(): String {
    return name?.trim()?.replace(Regex("[^a-zA-Z0-9]+"), "-") ?: ""
  }

  override fun toString(): String {
    return if (coreJdk) {
      "${if (assertPass) "Pass" else "Fail"}-core-jdk"
    } else {
      "${if (assertPass) "pass" else "fail"} $group:$module:$versions"
    }
  }
}

