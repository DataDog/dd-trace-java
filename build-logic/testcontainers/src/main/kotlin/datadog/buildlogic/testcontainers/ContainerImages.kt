package datadog.buildlogic.testcontainers

import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.plugins.ExtensionAware

/** Creates an image declaration for the test JVM property that consumes it. */
fun image(
  reference: String,
  systemProperty: String,
): ContainerImage = ContainerImage(reference, systemProperty)

/** Declares a container image for the test source set. */
fun DependencyHandler.testContainerImage(image: ContainerImage) = containerImage("test", image)

/** Declares a container image for an existing source set. */
fun DependencyHandler.containerImage(
  sourceSet: String,
  image: ContainerImage,
) {
  val images = (this as ExtensionAware).extensions.extraProperties.get("testContainerImages") as ContainerImages
  images.add(sourceSet, image)
}

internal class ContainerImages {
  val declarations = mutableMapOf<String, MutableMap<String, String>>()

  fun add(
    sourceSet: String,
    image: ContainerImage,
  ) {
    val images = requireNotNull(declarations[sourceSet]) { "Unknown container image source set '$sourceSet'" }
    require(images.putIfAbsent(image.systemProperty, image.reference) == null) {
      "Container image system property '${image.systemProperty}' is declared twice in $sourceSet"
    }
  }
}

data class ContainerImage(
  val reference: String,
  val systemProperty: String,
) {
  init {
    require(reference.isNotBlank()) { "Container image reference must not be empty" }
    require(systemProperty.matches(Regex("[A-Za-z0-9_.-]+"))) {
      "Invalid container image system property: $systemProperty"
    }
  }
}
