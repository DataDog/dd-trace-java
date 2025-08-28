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
}
