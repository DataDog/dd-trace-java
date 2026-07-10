package datadog.buildlogic.smoketest

import java.net.URI

internal const val MASS_READ_URL_ENV = "MASS_READ_URL"

internal fun gradleDistributionUri(massReadUrl: String, gradleVersion: String): URI {
  val baseUrl = if (massReadUrl.endsWith("/")) massReadUrl else "$massReadUrl/"
  return URI.create(
    "${baseUrl}internal/artifact/services.gradle.org/distributions/gradle-$gradleVersion-bin.zip",
  )
}
