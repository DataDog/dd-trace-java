package datadog.buildlogic.smoketest

import java.net.URI

internal const val MASS_READ_URL_ENV = "MASS_READ_URL"

// Published at https://gradle.org/release-checksums/; update with DEFAULT_NESTED_GRADLE_VERSION.
internal const val DEFAULT_NESTED_GRADLE_SHA256_SUM =
  "903bfc4bc7622323124054c10f509246212a1902948bdaf26c379cca8735e856"

internal fun gradleDistributionUri(massReadUrl: String?, gradleVersion: String): URI {
  if (massReadUrl.isNullOrBlank()) {
    return URI.create("https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip")
  }
  val baseUrl = if (massReadUrl.endsWith("/")) massReadUrl else "$massReadUrl/"
  return URI.create(
    "${baseUrl}internal/artifact/services.gradle.org/distributions/gradle-$gradleVersion-bin.zip",
  )
}
