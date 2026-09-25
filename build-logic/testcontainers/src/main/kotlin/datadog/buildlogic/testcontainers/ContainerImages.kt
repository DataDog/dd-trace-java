package datadog.buildlogic.testcontainers

import org.gradle.api.InvalidUserDataException
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.attributes.Attribute

internal const val IMAGE_DEPENDENCY_GROUP = "datadog.container-image"
internal val IMAGE_REFERENCE = Attribute.of("datadog.container-image.reference", String::class.java)

/** Creates an image declaration for the test JVM property that consumes it. */
fun DependencyHandler.image(
  reference: String,
  systemProperty: String,
): ExternalModuleDependency {
  if (reference.isBlank()) {
    throw InvalidUserDataException("Container image reference must not be empty")
  }
  if (!systemProperty.matches(Regex("[A-Za-z0-9_.-]+"))) {
    throw InvalidUserDataException("Invalid container image system property: $systemProperty")
  }

  // These logical dependencies are read from declaration-only configurations, never resolved by Gradle.
  return (create("$IMAGE_DEPENDENCY_GROUP:$systemProperty") as ExternalModuleDependency).apply {
    attributes { attribute(IMAGE_REFERENCE, reference) }
  }
}
