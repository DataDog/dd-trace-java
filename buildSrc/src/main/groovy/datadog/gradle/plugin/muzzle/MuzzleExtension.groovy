package datadog.gradle.plugin.muzzle

import org.eclipse.aether.repository.RemoteRepository
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory

/**
 * Muzzle extension containing all pass and fail directives.
 */
class MuzzleExtension {
  final List<MuzzleDirective> directives = new ArrayList<>()
  private final ObjectFactory objectFactory
  private final List<RemoteRepository> additionalRepositories = new ArrayList<>()

  @javax.inject.Inject
  MuzzleExtension(final ObjectFactory objectFactory) {
    this.objectFactory = objectFactory
  }

  void pass(Action<? super MuzzleDirective> action) {
    final MuzzleDirective pass = objectFactory.newInstance(MuzzleDirective)
    action.execute(pass)
    postConstruct(pass)
    pass.assertPass = true
    directives.add(pass)
  }

  void fail(Action<? super MuzzleDirective> action) {
    final MuzzleDirective fail = objectFactory.newInstance(MuzzleDirective)
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
  void extraRepository(String id, String url, String type = "default") {
    additionalRepositories.add(new RemoteRepository.Builder(id, type, url).build())
  }


  private postConstruct(MuzzleDirective directive) {
    // Make skipVersions case insensitive.
    directive.skipVersions = directive.skipVersions.collect {
      it.toLowerCase(Locale.ROOT)
    }
    // Add existing repositories
    directive.additionalRepositories.addAll(additionalRepositories)
  }
}
