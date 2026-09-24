package datadog.buildlogic.testcontainers

import groovy.lang.Closure
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import java.io.File

/** Declares container images alongside the dependencies of the test suite that consumes them. */
class TestcontainersPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val configurations = project.configurations
    // dependencyScope express exactly what is needed it is equivalent to
    //   configurations.register("testContainerImage") {
    //     isCanBeResolved = false
    //     isCanBeConsumed = false
    //   }
    // Additionally, it is lazy.
    configurations.dependencyScope("testContainerImage")
    configurations.named { it.endsWith("Implementation") }.all {
      val imageConfigurationName = "${name.removeSuffix("Implementation")}ContainerImage"
      if (imageConfigurationName !in configurations.names) {
        configurations.dependencyScope(imageConfigurationName)
      }
    }

    // Groovy needs a bridge to call the Kotlin receiver extension with the same syntax.
    project.dependencies.extensions.extraProperties.set(
      "image",
      object : Closure<ExternalModuleDependency>(null) {
        fun doCall(
          reference: String,
          systemProperty: String,
        ): ExternalModuleDependency = project.dependencies.image(reference, systemProperty)
      },
    )

    val resolver =
      project.gradle.sharedServices.registerIfAbsent(
        "testContainerImageResolver",
        ImageResolver::class.java,
      ) {}

    project.pluginManager.withPlugin("java") {
      val sourceSets = project.extensions.getByType<SourceSetContainer>()

      project.tasks.withType<Test>().configureEach {
        // ProviderFactory.provider snapshots project-local configuration for the configuration cache.
        // Keep registry access in the nested input getter, so every build refreshes moving tags.
        val containers =
          project.providers.provider {
            val testClasses = testClassesDirs.files
            val implementationNames =
              sourceSets
                .filter { suite ->
                  suite.output.classesDirs.files
                    .any(testClasses::contains)
                }.map { it.implementationConfigurationName }
                .ifEmpty { listOf("${name}Implementation") }
            val hierarchy = implementationNames.flatMap { configurations.findByName(it)?.hierarchy.orEmpty() }
            val imageConfigurationNames =
              setOf("${name}ContainerImage") +
                hierarchy.map { "${it.name.removeSuffix("Implementation")}ContainerImage" }

            val images = linkedMapOf<String, String>()
            imageConfigurationNames.mapNotNull(configurations::findByName).forEach { configuration ->
              configuration.allDependencies.forEach { dependency ->
                val reference = (dependency as? ExternalModuleDependency)?.attributes?.getAttribute(IMAGE_REFERENCE)
                if (dependency.group != IMAGE_DEPENDENCY_GROUP || reference == null) {
                  throw InvalidUserDataException("Use image(reference, systemProperty) for dependencies in ${configuration.name}")
                }

                val previous = images.putIfAbsent(dependency.name, reference)
                if (previous != null && previous != reference) {
                  throw InvalidUserDataException("Conflicting container images for '${dependency.name}' in $path")
                }
              }
            }

            if (images.isNotEmpty()) {
              val configurationFiles =
                listOf(File(System.getProperty("user.home"), ".testcontainers.properties")) +
                  // Keep output directories that processResources has not created yet.
                  classpath.files.map { File(it, "testcontainers.properties") }.sortedBy { it.toURI().toString() }

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
