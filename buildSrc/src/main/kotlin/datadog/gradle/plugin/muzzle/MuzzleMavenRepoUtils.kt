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
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.version.Version
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.SortedMap
import java.util.TreeMap
import java.util.function.BiFunction

object MuzzleMavenRepoUtils {
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
   * Create new RepositorySystem for muzzle's Maven/Aether resoltions.
   */
  @JvmStatic
  fun newRepositorySystem(): RepositorySystem {
    val locator = MavenRepositorySystemUtils.newServiceLocator().apply {
      addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
      addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
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

  @JvmStatic
  fun dumpVersionRanges(project: Project) {
    val system: RepositorySystem = MuzzleMavenRepoUtils.newRepositorySystem()
    val session: RepositorySystemSession = MuzzleMavenRepoUtils.newRepositorySystemSession(system)
    val versions = TreeMap<String, TestedArtifact>()
    val directives = project.extensions.findByType<MuzzleExtension>()?.directives ?: emptyList()
    directives.filter { !it.coreJdk && !it.skipFromReport }.forEach { directive ->
      val range = MuzzleMavenRepoUtils.resolveVersionRange(directive, system, session)
      val cp = project.files(project.mainSourceSet.runtimeClasspath).map { it.toURI().toURL() }.toTypedArray()
      val cl = URLClassLoader(cp, null)
      val partials = resolveInstrumentationAndJarVersions(directive, cl, range.lowestVersion, range.highestVersion)
      partials.forEach { (key, value) ->
        versions.merge(key, value, BiFunction { x, y ->
          TestedArtifact(
            x.instrumentation, x.group, x.module,
            lowest(x.lowVersion, y.lowVersion),
            highest(x.highVersion, y.highVersion)
          )
        })
      }
    }
    dumpVersionsToCsv(project, versions)
  }

  @JvmStatic
  fun dumpVersionsToCsv(project: Project, versions: SortedMap<String, TestedArtifact>) {
    val filename = project.path.replaceFirst("^:", "").replace(":", "_")
    val dir = project.rootProject.layout.buildDirectory.dir("muzzle-deps-results").get().asFile.apply {
      mkdirs()
    }
    with(File(dir, "$filename.csv")) {
      writeText("instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n")
      versions.values.forEach {
        appendText(
          listOf(
            it.instrumentation,
            it.group,
            it.module,
            it.lowVersion.toString(),
            it.highVersion.toString()
          ).joinToString(",") + "\n"
        )
      }
    }
  }


  /**
   * Resolves the version range for a given MuzzleDirective using the provided RepositorySystem and RepositorySystemSession.
   * Equivalent to the Groovy implementation in MuzzlePlugin.
   */
  @JvmStatic
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
      return system.resolveVersionRange(session, rangeRequest)
  }

  @JvmStatic
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
  @JvmStatic
  fun highest(a: Version, b: Version): Version = if (a.compareTo(b) > 0) a else b

  /**
   * Returns the lowest of two Version objects.
   */
  @JvmStatic
  fun lowest(a: Version, b: Version): Version = if (a.compareTo(b) < 0) a else b

  /**
   * Convert a muzzle directive to a set of artifacts for all filtered versions.
   * Throws GradleException if no artifacts are found.
   */
  @JvmStatic
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
