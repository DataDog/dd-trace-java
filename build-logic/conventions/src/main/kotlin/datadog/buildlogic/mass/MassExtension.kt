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

  fun artifactUrl(upstreamArtifactUrl: String): String {
    val massReadUrl = readUrl.orNull ?: return "https://$upstreamArtifactUrl"
    val baseUrl = if (massReadUrl.endsWith("/")) massReadUrl else "$massReadUrl/"
    return "${baseUrl}internal/artifact/$upstreamArtifactUrl"
  }
}
