package datadog.gradle.plugin.muzzle

import org.eclipse.aether.repository.RemoteRepository

/**
 * A pass or fail directive for a single dependency.
 */
class MuzzleDirective {

  /**
   * Name is optional and is used to further define the scope of a directive. The motivation for this is that this
   * plugin creates a config for each of the dependencies under test with name '...-<group_id>-<artifact_id>-<version>'.
   * The problem is that if we want to test multiple times the same configuration under different conditions, e.g.
   * with different extra dependencies, the plugin would throw an error as it would try to create several times the
   * same config. This property can be used to differentiate those config names for different directives.
   */
  String name

  String group
  String module
  String classifier
  String versions
  Set<String> skipVersions = new HashSet<>()
  List<String> additionalDependencies = new ArrayList<>()
  List<RemoteRepository> additionalRepositories = new ArrayList<>()
  List<String> excludedDependencies = new ArrayList<>()
  boolean assertPass
  boolean assertInverse = false
  boolean skipFromReport = false
  boolean coreJdk = false
  boolean includeSnapshots = false
  String javaVersion

  void coreJdk(version = null) {
    coreJdk = true
    javaVersion = version
  }

  /**
   * Adds extra dependencies to the current muzzle test.
   *
   * @param compileString An extra dependency in the gradle canonical form: '<group_id>:<artifact_id>:<version_id>'.
   */
  void extraDependency(String compileString) {
    additionalDependencies.add(compileString)
  }

  /**
   * Adds extra repositories to the current muzzle test.
   *
   * @param id the repository id
   * @param url the url of the repository
   * @param type the type of repository, defaults to "default"
   */
  void extraRepository(String id, String url, String type = "default") {
    additionalRepositories.add(new RemoteRepository.Builder(id, type, url).build())
  }

  /**
   * Adds transitive dependencies to exclude from the current muzzle test.
   *
   * @param excludeString A dependency in the gradle canonical form: '<group_id>:<artifact_id>'.
   */
  void excludeDependency(String excludeString) {
    excludedDependencies.add(excludeString)
  }

  /**
   * Get the list of repositories to use for this muzzle directive.
   *
   * @param defaults the default repositories
   * @return a list of the default repositories followed by any additional repositories
   */
  List<RemoteRepository> getRepositories(List<RemoteRepository> defaults) {
    if (additionalRepositories.isEmpty()) {
      return defaults
    } else {
      def repositories = new ArrayList<RemoteRepository>(defaults.size() + additionalRepositories.size())
      repositories.addAll(defaults)
      repositories.addAll(additionalRepositories)
      return repositories
    }
  }

  /**
   * Slug of directive name.
   *
   * @return A slug of the name or an empty string if name is empty. E.g. 'My Directive' --> 'My-Directive'
   */
  String getNameSlug() {
    if (null == name) {
      return ""
    }

    return name.trim().replaceAll("[^a-zA-Z0-9]+", "-")
  }

  String toString() {
    if (coreJdk) {
      return "${assertPass ? 'Pass' : 'Fail'}-core-jdk"
    } else {
      return "${assertPass ? 'pass' : 'fail'} $group:$module:$versions"
    }
  }
}
