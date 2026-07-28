package datadog.buildlogic.mass

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory

open class MassExtension
@Inject
constructor(objects: ObjectFactory, providers: ProviderFactory) {
  val readUrl: Property<String> =
    objects.property(String::class.java).convention(providers.environmentVariable("MASS_READ_URL"))

  fun artifactUrl(upstreamArtifactUrl: String): String = artifactUrls(upstreamArtifactUrl).first()

  /**
   * Ordered repository URLs: MASS, then upstream. Gradle advances only for missing artifacts, so
   * this covers MASS cache misses, not connection failures. Without MASS, returns only upstream.
   */
  fun artifactUrls(upstreamArtifactUrl: String): List<String> {
    val upstreamUrl = "https://$upstreamArtifactUrl"
    val massReadUrl = readUrl.orNull?.takeIf { it.isNotBlank() } ?: return listOf(upstreamUrl)
    val baseUrl = if (massReadUrl.endsWith("/")) massReadUrl else "$massReadUrl/"
    return listOf("${baseUrl}internal/artifact/$upstreamArtifactUrl", upstreamUrl)
  }
}
