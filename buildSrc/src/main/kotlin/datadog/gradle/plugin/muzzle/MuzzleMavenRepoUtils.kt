package datadog.gradle.plugin.muzzle

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.version.Version
import java.nio.file.Files

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
  fun resolveInstrumentationAndJarVersions(
    directive: MuzzleDirective,
    cl: ClassLoader,
    lowVersion: Version,
    highVersion: Version
  ): Map<String, TestedArtifact> {
    val scanPluginClass = cl.loadClass("datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin")
    val listMethod = scanPluginClass.getMethod("listInstrumentationNames", ClassLoader::class.java, String::class.java)
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
}
