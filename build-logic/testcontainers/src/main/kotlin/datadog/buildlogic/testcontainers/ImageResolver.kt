package datadog.buildlogic.testcontainers

import com.google.cloud.tools.jib.api.ImageReference
import com.google.cloud.tools.jib.api.RegistryUnauthorizedException
import com.google.cloud.tools.jib.event.EventHandlers
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory
import com.google.cloud.tools.jib.http.FailoverHttpClient
import com.google.cloud.tools.jib.registry.RegistryClient
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.nio.file.Paths
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves manifests without a Docker daemon or image-layer downloads. Memoized for this build only.
 */
abstract class ImageResolver : BuildService<BuildServiceParameters.None> {
  private val resolved = ConcurrentHashMap<String, String>()

  fun resolve(
    declarations: Map<String, String>,
    environment: Map<String, String>,
    configurationFiles: List<File>,
  ): Map<String, String> {
    val configuration = Properties()
    configurationFiles.asReversed().filter { it.isFile }.forEach { file ->
      file.inputStream().use { configuration.load(it) }
    }
    val customSubstitution =
      configuration
        .stringPropertyNames()
        .filter {
          it == "image.substitutor" || it.endsWith(".container.image")
        }.any { configuration.getProperty(it).isNotBlank() } ||
        environment.any { (key, value) ->
          (key == "TESTCONTAINERS_IMAGE_SUBSTITUTOR" || key.endsWith("_CONTAINER_IMAGE")) && value.isNotBlank()
        }
    if (customSubstitution) {
      throw InvalidUserDataException(
        "Custom Testcontainers image substitutions can replace a fingerprinted digest; move image overrides into testContainerImage declarations",
      )
    }

    val prefix =
      environment["TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX"]?.takeIf { it.isNotEmpty() }
        ?: configuration.getProperty("hub.image.name.prefix", "")
    return declarations.mapValues { (_, reference) -> resolve(reference, prefix) }
  }

  private fun resolve(
    reference: String,
    hubPrefix: String,
  ): String {
    val firstComponent = reference.substringBefore('/')
    val explicitRegistry =
      reference.contains('/') &&
        (firstComponent.contains('.') || firstComponent.contains(':') || firstComponent == "localhost")
    val effective = if (explicitRegistry) reference else hubPrefix + reference
    return resolved.computeIfAbsent(effective) { resolveManifest(it) }
  }

  /**
   * Image digest resolution.
   *
   * **Why jib?**
   *
   * Jib provides a daemonless registry client that retrieves the manifest without downloading layers.
   * However, it relies on Jib non-public API. But this is preferable to maintaining registry authentication
   * code in this case.
   * [Jib Core](https://github.com/GoogleContainerTools/jib/blob/master/jib-core/README.md)
   * [RegistryClient](https://github.com/GoogleContainerTools/jib/blob/master/jib-core/src/main/java/com/google/cloud/tools/jib/registry/RegistryClient.java)
   *
   * Considered alternatives:
   * - Testcontainers uses docker-java under the hood, it reuses the local image cache
   *   or pull the complete image, and returns the image name rather than the remote manifest digest.
   *   That is too late and too expensive for Gradle input fingerprinting.
   *   [RemoteDockerImage](https://github.com/testcontainers/testcontainers-java/blob/1.21.4/core/src/main/java/org/testcontainers/images/RemoteDockerImage.java)
   * - Docker’s `/distribution/{name}/json` endpoint can obtain the registry digest without
   *   downloading layers. But it still requires a running Docker daemon, and docker-java 3.7.1
   *   has no typed command for it. [Docker Engine API](https://docs.docker.com/reference/api/engine/version/v1.40/)
   * - A direct HTTP implementation is possible, but it must implement registry naming,
   *   OCI/Docker media types, bearer-token challenges, basic authentication, Docker credential helpers,
   *   redirects, TLS, and digest validation.
   *   [Registry authentication](https://docs.docker.com/reference/api/registry/auth/)
   *   [Registry API specification](https://github.com/distribution/distribution/blob/main/docs/content/spec/api.md)
   */
  private fun resolveManifest(reference: String): String {
    try {
      val image = ImageReference.parse(reference)
      val repository = "${image.registry}/${image.repository}"
      if (image.digest.isPresent) {
        return "$repository@${image.digest.get()}"
      }
      // Local registries are useful for development and hermetic tests. Remote registries require TLS.
      val loopback = image.registry.substringBefore(':') in setOf("localhost", "127.0.0.1")
      val http = FailoverHttpClient(loopback, loopback) {}
      try {
        val factory = RegistryClient.factory(EventHandlers.NONE, image.registry, image.repository, http)
        var client = factory.newRegistryClient()
        val manifest =
          try {
            client.pullManifest(image.tag.orElse("latest"))
          } catch (unauthorized: RegistryUnauthorizedException) {
            val dockerConfig =
              System.getenv("DOCKER_CONFIG")
                ?: Paths.get(System.getProperty("user.home"), ".docker").toString()
            val credential =
              CredentialRetrieverFactory
                .forImage(image) {}
                .dockerConfig(Paths.get(dockerConfig, "config.json"))
                .retrieve()
                .orElse(null)
            client = factory.setCredential(credential).newRegistryClient()
            if (!client.doPullBearerAuth()) {
              if (credential == null) throw unauthorized
              client.configureBasicAuth()
            }
            client.pullManifest(image.tag.orElse("latest"))
          }
        return "$repository@${manifest.digest}"
      } finally {
        http.shutDown()
      }
    } catch (failure: Exception) {
      throw GradleException("Cannot resolve test container image '$reference'; refusing to reuse test results without its digest", failure)
    }
  }
}
