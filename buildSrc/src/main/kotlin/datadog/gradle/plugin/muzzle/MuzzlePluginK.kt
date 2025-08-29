package datadog.gradle.plugin.muzzle

import org.eclipse.aether.artifact.Artifact
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.register

class MuzzlePluginK {
  companion object {
    /**
     * Registers a new muzzle task for the given directive and artifact.
     *
     * This function creates a new Gradle task and configuration for muzzle validation against a specific dependency version.
     * It sets up the necessary dependencies, excludes legacy and user-specified modules.
     *
     * @param muzzleDirective The directive describing the dependency and test parameters.
     * @param versionArtifact The artifact representing the dependency version to test (may be null when `muzzleDirective.coreJdk` is true).
     * @param instrumentationProject The Gradle project to register the task in.
     * @param runAfterTask The task provider for the task that this muzzle task should run after.
     * @param muzzleBootstrap The configuration provider for agent bootstrap dependencies.
     * @param muzzleTooling The configuration provider for agent tooling dependencies.
     * @return The muzzle task provider.
     */
    @JvmStatic
    fun addMuzzleTask(
      muzzleDirective: MuzzleDirective,
      versionArtifact: Artifact?,
      instrumentationProject: Project,
      runAfterTask: TaskProvider<Task>,
      muzzleBootstrap: NamedDomainObjectProvider<Configuration>,
      muzzleTooling: NamedDomainObjectProvider<Configuration>
    ): TaskProvider<MuzzleTask> {
      val muzzleTaskName = if (muzzleDirective.coreJdk) {
        "muzzle-Assert$muzzleDirective"
      } else {
        "muzzle-Assert${if (muzzleDirective.assertPass) "Pass" else "Fail"}-${versionArtifact?.groupId}-${versionArtifact?.artifactId}-${versionArtifact?.version}${if (muzzleDirective.name != null) "-${muzzleDirective.name}" else ""}"
      }
      instrumentationProject.configurations.register(muzzleTaskName) {
        if (!muzzleDirective.coreJdk) {
          var depId = "${versionArtifact?.groupId}:${versionArtifact?.artifactId}:${versionArtifact?.version}"
          if (versionArtifact?.classifier != null) {
            depId += ":${versionArtifact.classifier}"
          }

          val dep: Dependency = instrumentationProject.dependencies.create(depId) {
            isTransitive = true

            // The following optional transitive dependencies are brought in by some legacy module such as log4j 1.x but are no
            // longer bundled with the JVM and have to be excluded for the muzzle tests to be able to run.
            exclude(group = "com.sun.jdmk", module = "jmxtools")
            exclude(group = "com.sun.jmx", module = "jmxri")

            // Also exclude specifically excluded dependencies
            for (excluded in muzzleDirective.excludedDependencies) {
              val parts = excluded.split(":")
              exclude(group = parts[0], module = parts[1])
            }
          }
          dependencies.add(dep)
        }

        for (additionalDependency in muzzleDirective.additionalDependencies) {
          val dep = instrumentationProject.dependencies.create(additionalDependency) {
            isTransitive = true
            for (excluded in muzzleDirective.excludedDependencies) {
              val parts = excluded.split(":")
              exclude(group = parts[0], module = parts[1])
            }
          }
          dependencies.add(dep)
        }
      }

      val muzzleTask = instrumentationProject.tasks.register<MuzzleTask>(muzzleTaskName) {
        doLast {
          assertMuzzle(muzzleBootstrap, muzzleTooling, instrumentationProject, muzzleDirective)
        }
      }

      runAfterTask.configure {
        finalizedBy(muzzleTask)
      }
      return muzzleTask
    }
  }
}
