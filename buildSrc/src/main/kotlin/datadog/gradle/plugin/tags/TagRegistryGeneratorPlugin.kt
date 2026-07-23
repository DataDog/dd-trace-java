package datadog.gradle.plugin.tags

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory

/** Extension configuring the tag-registry generator inputs/outputs. */
abstract class TagRegistryExtension @Inject constructor(objects: ObjectFactory) {
  val domainYaml: RegularFileProperty = objects.fileProperty()
  val overlayYaml: RegularFileProperty = objects.fileProperty()
  val destinationDirectory: DirectoryProperty = objects.directoryProperty()
}

/**
 * Registers {@code generateKnownTags} (emits the committed tag registry) and {@code verifyKnownTags}
 * (a freshness gate that regenerates and byte-compares against the committed output). The verify task
 * is wired into {@code check} so stale generated sources fail CI.
 */
class TagRegistryGeneratorPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create("tagRegistry", TagRegistryExtension::class.java)
    project.tasks.register("generateKnownTags", GenerateKnownTagsTask::class.java) {
      domainYaml.set(ext.domainYaml)
      overlayYaml.set(ext.overlayYaml)
      destinationDirectory.set(ext.destinationDirectory)
    }
    val verify =
      project.tasks.register("verifyKnownTags", VerifyKnownTagsTask::class.java) {
        domainYaml.set(ext.domainYaml)
        overlayYaml.set(ext.overlayYaml)
        committedDirectory.set(ext.destinationDirectory)
      }
    // `check` is contributed by lifecycle-base (via java-library); wait for it before wiring.
    project.pluginManager.withPlugin("lifecycle-base") {
      project.tasks.named("check").configure { dependsOn(verify) }
    }
  }
}
