package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.inverseOf
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.muzzleDirectiveToArtifacts
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils.resolveVersionRange
import datadog.gradle.plugin.muzzle.tasks.MuzzleEndTask
import datadog.gradle.plugin.muzzle.tasks.MuzzleGenerateReportTask
import datadog.gradle.plugin.muzzle.tasks.MuzzleGetReferencesTask
import datadog.gradle.plugin.muzzle.tasks.MuzzleMergeReportsTask
import datadog.gradle.plugin.muzzle.tasks.MuzzleTask
import org.eclipse.aether.artifact.Artifact
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.register

/**
 * muzzle task plugin which runs muzzle validation against a range of dependencies.
 */
class MuzzlePlugin : Plugin<Project> {
  /**
   * Applies the Muzzle plugin to the given project.
   *
   * This function sets up the necessary configurations, dependencies, and tasks for muzzle validation.
   * It registers the muzzle extension, configures bootstrap/tooling dependencies, and sets up tasks for
   * compiling, running, printing, and reporting muzzle checks. It also optimizes configuration overhead
   * by only proceeding if relevant muzzle tasks are requested.
   *
   * @param project The Gradle project to apply the plugin to.
   */
  override fun apply(project: Project) {
    // create extension first, if java plugin is applied after muzzle
    project.extensions.create<MuzzleExtension>("muzzle", project.objects)

    // Configure muzzle only when java plugin is applied, because this plugin requires
    // the project's SourceSetContainer, which created by the java plugin (via the JvmEcosystemPlugin)
    project.pluginManager.withPlugin("java") {
      configureMuzzle(project)
    }
  }

  private fun configureMuzzle(project: Project) {
    val rootProjects = project.rootProject.childProjects
    val ddJavaAgent = rootProjects["dd-java-agent"]?.childProjects ?: error(":dd-java-agent child projects not found")
    val bootstrapProject = ddJavaAgent["agent-bootstrap"] ?: error(":dd-java-agent:agent-bootstrap project not found")
    val toolingProject = ddJavaAgent["agent-tooling"] ?: error(":dd-java-agent:agent-tooling project not found")

    val muzzleBootstrap = project.configurations.register("muzzleBootstrap") {
      isCanBeConsumed = false
      isCanBeResolved = true

      dependencies.add(project.dependencies.project(bootstrapProject.path))
    }

    val muzzleTooling = project.configurations.register("muzzleTooling") {
      isCanBeConsumed = false
      isCanBeResolved = true

      dependencies.add(project.dependencies.project(toolingProject.path))
    }

    project.evaluationDependsOn(bootstrapProject.path)
    project.evaluationDependsOn(toolingProject.path)

    // compileMuzzle compiles all projects required to run muzzle validation.
    // Not adding group and description to keep this task from showing in `gradle tasks`.
    val compileMuzzle = project.tasks.register("compileMuzzle") {
      inputs.files(project.providers.provider { project.allMainSourceSet })
      dependsOn(bootstrapProject.tasks.named("compileJava"))
      dependsOn(bootstrapProject.tasks.named("compileMain_java11Java"))
      dependsOn(toolingProject.tasks.named("compileJava"))
    }

    val muzzleTask = project.tasks.register<MuzzleTask>("muzzle") {
      this.muzzleBootstrap.set(muzzleBootstrap)
      this.muzzleTooling.set(muzzleTooling)
      dependsOn(compileMuzzle)
    }

    project.tasks.register<MuzzleGetReferencesTask>("printReferences") {
      dependsOn(compileMuzzle)
    }.also {
      val printReferencesTask = project.tasks.register("actuallyPrintReferences") {
        doLast {
          println(it.get().outputFile.get().asFile.readText())
        }
      }
      it.configure { finalizedBy(printReferencesTask) }
    }

    project.tasks.register<MuzzleGenerateReportTask>("generateMuzzleReport") {
      dependsOn(compileMuzzle)
    }

    project.tasks.register<MuzzleMergeReportsTask>("mergeMuzzleReports")

    val hasRelevantTask = project.gradle.startParameter.taskNames.any { taskName ->
      // removing leading ':' if present
      val muzzleTaskName = taskName.removePrefix(":")
      val projectPath = project.path.removePrefix(":")
      muzzleTaskName == "muzzle" || "$projectPath:muzzle" == muzzleTaskName ||
          muzzleTaskName == "runMuzzle"
    }
    if (!hasRelevantTask) {
      // Adding muzzle dependencies has a large config overhead. Stop unless muzzle is explicitly run.
      return
    }

    // We only get here if we are running muzzle, so let's start timing things
    val startTime = System.currentTimeMillis()

    val system = MuzzleMavenRepoUtils.newRepositorySystem()
    val session = MuzzleMavenRepoUtils.newRepositorySystemSession(system)
    project.afterEvaluate {
      // use runAfter to set up task finalizers in version order
      var runAfter: TaskProvider<MuzzleTask> = muzzleTask
      val muzzleReportTasks = mutableListOf<TaskProvider<MuzzleTask>>()

      project.extensions.getByType<MuzzleExtension>().directives.forEach { directive ->
        project.logger.debug("configuring {}", directive)

        if (directive.isCoreJdk) {
          runAfter = addMuzzleTask(directive, null, project, runAfter, muzzleBootstrap, muzzleTooling)
          muzzleReportTasks.add(runAfter)
        } else {
          val range = resolveVersionRange(directive, system, session)

          muzzleDirectiveToArtifacts(directive, range).forEach {
            runAfter = addMuzzleTask(directive, it, project, runAfter, muzzleBootstrap, muzzleTooling)
            muzzleReportTasks.add(runAfter)
          }

          if (directive.assertInverse) {
            inverseOf(directive, system, session).forEach { inverseDirective ->
              val inverseRange = resolveVersionRange(inverseDirective, system, session)

              muzzleDirectiveToArtifacts(inverseDirective, inverseRange).forEach {
                runAfter = addMuzzleTask(inverseDirective, it, project, runAfter, muzzleBootstrap, muzzleTooling)
                muzzleReportTasks.add(runAfter)
              }
            }
          }
        }
        project.logger.info("configured $directive")
      }

      if (muzzleReportTasks.isEmpty() && !project.extensions.getByType<MuzzleExtension>().directives.any { it.assertPass }) {
        muzzleReportTasks.add(muzzleTask)
      }

      val timingTask = project.tasks.register<MuzzleEndTask>("muzzle-end") {
        startTimeMs.set(startTime)
        muzzleResultFiles.from(muzzleReportTasks.map { it.flatMap { task -> task.result } })
      }
      // last muzzle task to run
      runAfter.configure {
        finalizedBy(timingTask)
      }
    }
  }

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
    private fun addMuzzleTask(
      muzzleDirective: MuzzleDirective,
      versionArtifact: Artifact?,
      instrumentationProject: Project,
      runAfterTask: TaskProvider<MuzzleTask>,
      muzzleBootstrap: NamedDomainObjectProvider<Configuration>,
      muzzleTooling: NamedDomainObjectProvider<Configuration>
    ): TaskProvider<MuzzleTask> {
      val muzzleTaskName = buildString {
        append("muzzle-Assert")
        when {
            muzzleDirective.isCoreJdk -> {
              append(muzzleDirective)
            }
            else -> {
              append(if (muzzleDirective.assertPass) "Pass" else "Fail")
              append("-")
              append(versionArtifact?.groupId)
              append("-")
              append(versionArtifact?.artifactId)
              append("-")
              append(versionArtifact?.version)
              append(if (muzzleDirective.name != null) "-${muzzleDirective.nameSlug}" else "")
            }
        }
      }
      instrumentationProject.configurations.register(muzzleTaskName) {
        if (!muzzleDirective.isCoreJdk && versionArtifact != null) {
          val depId = buildString {
            append("${versionArtifact.groupId}:${versionArtifact.artifactId}:${versionArtifact.version}")

            versionArtifact.classifier?.let {
              append(":")
              append(it)
            }
          }

          val dep = instrumentationProject.dependencies.create(depId) {
            isTransitive = true

            // The following optional transitive dependencies are brought in by some legacy module such as log4j 1.x but are no
            // longer bundled with the JVM and have to be excluded for the muzzle tests to be able to run.
            exclude(group = "com.sun.jdmk", module = "jmxtools")
            exclude(group = "com.sun.jmx", module = "jmxri")

            // Also exclude specifically excluded dependencies
            muzzleDirective.excludedDependencies.forEach {
              val parts = it.split(":")
              exclude(group = parts[0], module = parts[1])
            }
          }
          dependencies.add(dep)
        }

        muzzleDirective.additionalDependencies.forEach {
          val dep = instrumentationProject.dependencies.create(it) {
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
        this.muzzleDirective.set(muzzleDirective)
        this.muzzleBootstrap.set(muzzleBootstrap)
        this.muzzleTooling.set(muzzleTooling)
      }

      runAfterTask.configure {
        finalizedBy(muzzleTask)
      }
      return muzzleTask
    }
  }
}
