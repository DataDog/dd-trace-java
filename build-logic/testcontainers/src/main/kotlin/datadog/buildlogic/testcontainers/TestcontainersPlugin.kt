package datadog.buildlogic.testcontainers

import groovy.lang.Closure
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import java.io.File

/** Declares container images alongside the dependencies of the test suite that consumes them. */
class TestcontainersPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val declarations = mutableMapOf<String, MutableMap<String, String>>()
    val dependencyDsl = (project.dependencies as ExtensionAware).extensions.extraProperties
    dependencyDsl.set(
      "image",
      object : Closure<ContainerImage>(null) {
        fun doCall(
          reference: String,
          systemProperty: String,
        ): ContainerImage {
          require(reference.isNotBlank()) { "Container image reference must not be empty" }
          require(systemProperty.matches(Regex("[A-Za-z0-9_.-]+"))) {
            "Invalid container image system property: $systemProperty"
          }
          return ContainerImage(reference, systemProperty)
        }
      },
    )

    val resolver =
      project.gradle.sharedServices.registerIfAbsent(
        "testContainerImageResolver",
        ImageResolver::class.java,
      ) {}

    project.pluginManager.withPlugin("java") {
      val sourceSets = project.extensions.getByType<SourceSetContainer>()
      sourceSets.all {
        val images = declarations.getOrPut(name) { linkedMapOf() }
        dependencyDsl.set(
          "${name}ContainerImage",
          object : Closure<Unit>(null) {
            fun doCall(image: ContainerImage) {
              require(images.putIfAbsent(image.systemProperty, image.reference) == null) {
                "Container image system property '${image.systemProperty}' is declared twice in $name"
              }
            }
          },
        )
      }
      project.tasks.withType<Test>().configureEach {
        // ProviderFactory.provider snapshots project-local configuration for the configuration cache.
        // Keep registry access in the nested input getter, so every build refreshes moving tags.
        val containers =
          project.providers.provider {
            val suiteName = if (name == "forkedTest") "test" else name.removeSuffix("ForkedTest")
            val suite =
              sourceSets.findByName(name) ?: sourceSets.findByName(suiteName)
                ?: return@provider null
            val hierarchy = project.configurations.getByName(suite.implementationConfigurationName).hierarchy
            val images = linkedMapOf<String, String>()
            val inheritedSourceSets =
              sourceSets.filter { sourceSet ->
                hierarchy.any { it.name == sourceSet.implementationConfigurationName }
              }
            inheritedSourceSets.forEach { sourceSet ->
              declarations[sourceSet.name]?.forEach { (property, reference) ->
                require(images.putIfAbsent(property, reference).let { it == null || it == reference }) {
                  "Conflicting container images for '$property' in $path"
                }
              }
            }
            if (images.isNotEmpty()) {
              val configurationFiles =
                listOf(File(System.getProperty("user.home"), ".testcontainers.properties")) +
                  inheritedSourceSets.flatMap { it.resources.srcDirs }.map { File(it, "testcontainers.properties") }
              // Track inherited values for configuration-cache invalidation, but use the task's
              // effective environment below so explicit overrides and removals are respected.
              project.providers.environmentVariablesPrefixedBy("TESTCONTAINERS_").get()
              val imageEnvironment =
                environment
                  .filterKeys {
                    it == "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX" || it == "TESTCONTAINERS_IMAGE_SUBSTITUTOR" ||
                      (it.startsWith("TESTCONTAINERS_") && it.endsWith("_CONTAINER_IMAGE"))
                  }.mapValues { it.value.toString() }
              ContainerImageInputs(images, imageEnvironment, configurationFiles, resolver)
            } else {
              null
            }
          }
        jvmArgumentProviders.add(ContainerImageArguments(containers))
      }
    }
  }
}

data class ContainerImage(
  val reference: String,
  val systemProperty: String,
)
