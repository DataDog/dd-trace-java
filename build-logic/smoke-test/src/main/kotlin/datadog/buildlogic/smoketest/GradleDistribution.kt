package datadog.buildlogic.smoketest

import java.net.URI

internal const val MASS_READ_URL_ENV = "MASS_READ_URL"

/** Public Gradle distribution source. */
internal const val UPSTREAM_DISTRIBUTIONS_BASE_URL = "https://services.gradle.org/distributions"

internal fun gradleDistributionUri(massReadUrl: String, gradleVersion: String): URI {
  val baseUrl = if (massReadUrl.endsWith("/")) massReadUrl else "$massReadUrl/"
  return URI.create(
    "${baseUrl}internal/artifact/services.gradle.org/distributions/gradle-$gradleVersion-bin.zip",
  )
}

internal fun upstreamGradleDistributionUri(gradleVersion: String): URI =
  URI.create("$UPSTREAM_DISTRIBUTIONS_BASE_URL/gradle-$gradleVersion-bin.zip")

/** Nested Gradle sources, preferring MASS and falling back upstream. */
internal fun gradleDistributionUris(massReadUrl: String?, gradleVersion: String): List<URI> =
  if (massReadUrl.isNullOrBlank()) {
    listOf(upstreamGradleDistributionUri(gradleVersion))
  } else {
    listOf(
      gradleDistributionUri(massReadUrl, gradleVersion),
      upstreamGradleDistributionUri(gradleVersion),
    )
  }
