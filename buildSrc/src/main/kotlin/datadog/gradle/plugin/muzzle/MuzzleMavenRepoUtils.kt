package datadog.gradle.plugin.muzzle

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.version.Version
import org.gradle.api.GradleException
import java.nio.file.Files

internal object MuzzleMavenRepoUtils {
  /**
   * Remote repositories used to query version ranges and fetch dependencies
   */
  @JvmStatic
  val MUZZLE_REPOS: List<RemoteRepository> by lazy {
    val central = RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build()
    val mavenProxyUrl = System.getenv("MAVEN_REPOSITORY_PROXY")
    if (mavenProxyUrl == null) {
      listOf(central)
    } else {
      val proxy = RemoteRepository.Builder("central-proxy", "default", mavenProxyUrl).build()
      listOf(proxy, central)
    }
  }

  /**
   * Create new RepositorySystem for muzzle's Maven/Aether resolutions.
   * Supports both HTTP/HTTPS and file:// repositories.
   */
  @JvmStatic
  fun newRepositorySystem(): RepositorySystem {
    val locator = MavenRepositorySystemUtils.newServiceLocator().apply {
      addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
      addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
      addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
    }
    return locator.getService(RepositorySystem::class.java)
  }

  /**
   * Returns a new RepositorySystemSession for Muzzle's repo session.
   */
  @JvmStatic
  fun newRepositorySystemSession(system: RepositorySystem): RepositorySystemSession {
    val session = MavenRepositorySystemUtils.newSession().apply {
      val tmpDir = Files.createTempDirectory("muzzle-generated-tmpdir-").toFile().apply {
        deleteOnExit()
      }
      val localRepo = LocalRepository(tmpDir)
      localRepositoryManager = system.newLocalRepositoryManager(this, localRepo)
    }
    return session
  }

  /**
   * Create a list of muzzle directives which assert the opposite of the given MuzzleDirective.
   */
  fun inverseOf(
    muzzleDirective: MuzzleDirective,
    system: RepositorySystem,
    session: RepositorySystemSession
  ): Set<MuzzleDirective> {
    val allVersionsArtifact = DefaultArtifact(
      muzzleDirective.group,
      muzzleDirective.module,
      "jar",
      "[,)"
    )
    val repos = muzzleDirective.getRepositories(MUZZLE_REPOS)
    val allRangeRequest = VersionRangeRequest().apply {
      repositories = repos
      artifact = allVersionsArtifact
    }
    val allRangeResult = system.resolveVersionRange(session, allRangeRequest)

    val directiveArtifact = DefaultArtifact(
      muzzleDirective.group,
      muzzleDirective.module,
      "jar",
      muzzleDirective.versions
    )
    val rangeRequest = VersionRangeRequest().apply {
      repositories = repos
      artifact = directiveArtifact
    }
    val rangeResult = system.resolveVersionRange(session, rangeRequest)

    val rangeResultVersions = rangeResult.versions.toSet()
    allRangeResult.versions.removeAll(rangeResultVersions)
    return MuzzleVersionUtils.filterAndLimitVersions(
      allRangeResult,
      muzzleDirective.skipVersions,
      muzzleDirective.includeSnapshots
    ).map { version ->
      MuzzleDirective().apply {
        name = muzzleDirective.name
        group = muzzleDirective.group
        module = muzzleDirective.module
        versions = version.toString()
        assertPass = !muzzleDirective.assertPass
        excludedDependencies = muzzleDirective.excludedDependencies
        includeSnapshots = muzzleDirective.includeSnapshots
      }
    }.toSet()
  }

  /**
   * Resolves the version range for a given MuzzleDirective using the provided RepositorySystem and RepositorySystemSession.
   * Equivalent to the Groovy implementation in MuzzlePlugin.
   */
  fun resolveVersionRange(
    muzzleDirective: MuzzleDirective,
    system: RepositorySystem,
    session: RepositorySystemSession
  ): VersionRangeResult {
    val directiveArtifact: Artifact = DefaultArtifact(
      muzzleDirective.group,
      muzzleDirective.module,
      muzzleDirective.classifier ?: "",
      "jar",
      muzzleDirective.versions
    )
    val rangeRequest = VersionRangeRequest().apply {
      repositories = muzzleDirective.getRepositories(MUZZLE_REPOS)
      artifact = directiveArtifact
    }

    // In rare cases, the version resolution range silently failed with the maven proxy,
    // retries 3 times at most then suggest to restart the job later.
    var range = system.resolveVersionRange(session, rangeRequest)
    for (i in 0..3) {
      if (range.lowestVersion != null && range.highestVersion != null) {
        return range
      }
      range = system.resolveVersionRange(session, rangeRequest)
    }

    throw IllegalStateException("The version range resolution failed during report, this is not expected. Advised course of action: Restart the job later.")
  }

  /**
   * Resolves instrumentation names and their corresponding artifact versions for a given directive.
   *
   * Loads the `MuzzleVersionScanPlugin` class using the provided `ClassLoader`, invokes its
   * `listInstrumentationNames` method to get all instrumentation names for the directive, and
   * constructs a map of `TestedArtifact` objects keyed by their unique identifier. For each
   * instrumentation name, the lowest and highest versions are determined and stored.
   *
   * @param directive the `MuzzleDirective` containing group, module, and name information
   * @param cl the `ClassLoader` used to load the scan plugin class
   * @param lowVersion the lowest version to consider
   * @param highVersion the highest version to consider
   * @return a map of instrumentation name keys to their corresponding `TestedArtifact` objects
   */
  fun resolveInstrumentationAndJarVersions(
    directive: MuzzleDirective,
    cl: ClassLoader,
    lowVersion: Version,
    highVersion: Version
  ): Map<String, TestedArtifact> {
    val scanPluginClass = cl.loadClass("datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin")
    val listMethod = scanPluginClass.getMethod("listInstrumentationNames", ClassLoader::class.java, String::class.java)

    @Suppress("UNCHECKED_CAST")
    val names = listMethod.invoke(null, cl, directive.name) as Set<String>

    val ret = mutableMapOf<String, TestedArtifact>()
    for (n in names) {
      val testedArtifact = TestedArtifact(n, directive.group ?: "", directive.module ?: "", lowVersion, highVersion)
      val value = ret[testedArtifact.key()] ?: testedArtifact
      ret[testedArtifact.key()] = TestedArtifact(
        value.instrumentation,
        value.group,
        value.module,
        lowest(lowVersion, value.lowVersion),
        highest(highVersion, value.highVersion)
      )
    }
    return ret
  }

  /**
   * Returns the highest of two Version objects.
   */
  fun highest(a: Version, b: Version): Version = if (a > b) a else b

  /**
   * Returns the lowest of two Version objects.
   */
  fun lowest(a: Version, b: Version): Version = if (a < b) a else b

  /**
   * Convert a muzzle directive to a set of artifacts for all filtered versions.
   * Throws GradleException if no artifacts are found.
   */
  fun muzzleDirectiveToArtifacts(
    muzzleDirective: MuzzleDirective,
    rangeResult: VersionRangeResult
  ): Set<Artifact> {
    val versions = MuzzleVersionUtils.filterAndLimitVersions(
      rangeResult,
      muzzleDirective.skipVersions,
      muzzleDirective.includeSnapshots
    )
    val allVersionArtifacts = versions.map { version ->
      DefaultArtifact(
        muzzleDirective.group,
        muzzleDirective.module,
        muzzleDirective.classifier ?: "",
        "jar",
        version.toString()
      )
    }.toSet()
    if (allVersionArtifacts.isEmpty()) {
      throw GradleException("No muzzle artifacts found for ${muzzleDirective.group}:${muzzleDirective.module} ${muzzleDirective.versions} ${muzzleDirective.classifier}")
    }
    return allVersionArtifacts
  }
}
