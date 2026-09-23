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
      // Suite inheritance is configured by build scripts after the plugin is applied.
      project.afterEvaluate {
        project.tasks.withType<Test>().configureEach {
          val suiteName = if (name == "forkedTest") "test" else name.removeSuffix("ForkedTest")
          val suite =
            sourceSets.findByName(name) ?: sourceSets.findByName(suiteName)
              ?: return@configureEach
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
            usesService(resolver)
            val configurationFiles =
              listOf(File(System.getProperty("user.home"), ".testcontainers.properties")) +
                inheritedSourceSets.flatMap { it.resources.srcDirs }.map { File(it, "testcontainers.properties") }
            val imageEnvironment =
              (project.providers.environmentVariablesPrefixedBy("TESTCONTAINERS_").get() + environment)
                .filterKeys {
                  it == "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX" || it == "TESTCONTAINERS_IMAGE_SUBSTITUTOR" ||
                    (it.startsWith("TESTCONTAINERS_") && it.endsWith("_CONTAINER_IMAGE"))
                }.mapValues { it.value.toString() }
            jvmArgumentProviders.add(ContainerImageArguments(images, imageEnvironment, configurationFiles, resolver))
          }
        }
      }
    }
  }
}

data class ContainerImage(
  val reference: String,
  val systemProperty: String,
)
