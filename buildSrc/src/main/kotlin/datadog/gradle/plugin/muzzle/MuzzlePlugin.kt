package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.planner.MuzzlePlannedVersion
import datadog.gradle.plugin.muzzle.planner.MuzzlePlanningRequest
import datadog.gradle.plugin.muzzle.planner.MuzzlePlansValueSource
import datadog.gradle.plugin.muzzle.tasks.MuzzleEndTask
import datadog.gradle.plugin.muzzle.tasks.MuzzleGenerateReportTask
import datadog.gradle.plugin.muzzle.tasks.MuzzleGetReferencesTask
import datadog.gradle.plugin.muzzle.tasks.MuzzleTask
import kotlin.random.Random
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

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
    val extension = project.extensions.getByType<MuzzleExtension>()
    val compileMuzzle = project.tasks.register("compileMuzzle") {
      inputs.files(project.providers.provider { project.allMainSourceSet.map { it.output } })
      dependsOn(bootstrapProject.tasks.named("compileJava"))
      dependsOn(bootstrapProject.tasks.named("compileMain_java11Java"))
      dependsOn(toolingProject.tasks.named("compileJava"))
    }

    val muzzleTask = project.tasks.register<MuzzleTask>("muzzle") {
      description = "Check instrumentation compatibility against the configured dependency versions"
      this.muzzleBootstrap.from(muzzleBootstrap)
      this.muzzleTooling.from(muzzleTooling)
      dependsOn(compileMuzzle)
    }

    project.tasks.withType<MuzzleTask>().configureEach {
      agentClassPath.from(project.providers.provider { project.allMainSourceSet.map { it.runtimeClasspath } })
      muzzleClassPath.from(project.configurations.named(if (name == "muzzle") "compileClasspath" else name))
    }

    project.tasks.register<MuzzleGetReferencesTask>("printReferences") {
      dependsOn(compileMuzzle)
      classpath.from(project.mainSourceSet.runtimeClasspath)
    }.also {
      val referenceOutput = it.flatMap { task -> task.outputFile }
      val printReferencesTask = project.tasks.register("actuallyPrintReferences") {
        doLast {
          println(referenceOutput.get().asFile.readText())
        }
      }
      it.configure { finalizedBy(printReferencesTask) }
    }

    val runtimeClasspath = project.mainSourceSet.runtimeClasspath
    val report = project.tasks.register<MuzzleGenerateReportTask>("generateMuzzleReport") {
      reportDirectives.convention(project.providers.provider { extension.directives })
      instrumentationClasspath.from(reportingDirectives.map { if (it.isEmpty()) emptyList<Any>() else runtimeClasspath })
      dependsOn(compileMuzzle)
    }
    project.publishMuzzleReport(report.flatMap { it.versionsFile })

    registerPlanning(project)
  }

  private fun hasRelevantTask(project: Project): Boolean = project.gradle.startParameter.taskNames.any { taskName ->
    val taskProjectPath = taskName.substringBeforeLast(":", "")
    val taskNameOnly = taskName.substringAfterLast(":")
    val isAggregate = taskNameOnly.equals("runMuzzle", ignoreCase = true)
    val isRelevantForProject = taskProjectPath.isEmpty() || taskProjectPath == project.path ||
      (isAggregate && project.path.startsWith("$taskProjectPath:"))
    isRelevantForProject && !taskNameOnly.equals("compileMuzzle", ignoreCase = true) &&
      taskNameOnly.endsWith("muzzle", ignoreCase = true) &&
      (!isAggregate || project.extensions.getByType<MuzzleExtension>().includeInAggregate.get())
  }

  private fun registerPlanning(project: Project) {
    val root = project.rootProject
    val marker = "datadogMuzzlePlanningRegistered"
    if (root.extensions.extraProperties.has(marker)) return
    root.extensions.extraProperties.set(marker, true)
    project.gradle.projectsEvaluated {
      val projects = root.allprojects.filter {
        it.plugins.hasPlugin("dd-trace-java.muzzle") && it.plugins.hasPlugin("java")
      }.filter {
        val extension = it.extensions.getByType<MuzzleExtension>()
        it.tasks.named<MuzzleTask>("muzzle").configure {
          checkCompileTimeDependencies.set(!extension.directives.any { it.assertPass })
        }
        val relevant = hasRelevantTask(it)
        if (!relevant) {
          it.logger.info("No muzzle tasks invoked for ${it.path}, skipping muzzle task planification")
        }
        relevant
      }
      if (projects.isEmpty()) return@projectsEvaluated
      val requests = projects.map {
        MuzzlePlanningRequest(it.path, it.extensions.getByType<MuzzleExtension>().directives.toList())
      }
      // Revalidate selected coordinates when loading a cached task graph.
      val plans = root.providers.of(MuzzlePlansValueSource::class.java) {
        parameters.requests.set(requests)
        parameters.samplingSeed.set(Random.nextLong())
      }.get().groupBy { it.projectPath }
      projects.forEachIndexed { index, instrumentation ->
        configureMuzzlePlan(instrumentation, requests[index].directives, plans[instrumentation.path].orEmpty())
      }
    }
  }

  private fun configureMuzzlePlan(
    project: Project,
    directives: List<MuzzleDirective>,
    plans: List<MuzzlePlannedVersion>
  ) {
    val muzzleTask = project.tasks.named<MuzzleTask>("muzzle")
    val muzzleBootstrap = project.configurations.named("muzzleBootstrap")
    val muzzleTooling = project.configurations.named("muzzleTooling")
    var runAfter = muzzleTask
    val muzzleReportTasks = mutableListOf<TaskProvider<MuzzleTask>>()
    plans.forEach { plan ->
      val original = directives[plan.directiveIndex]
      val directive = if (plan.assertPass == original.assertPass) {
        original
      } else {
        original.inverse(requireNotNull(plan.version))
      }
      val artifact = plan.version?.let {
        DefaultArtifact(directive.group, directive.module, directive.classifier ?: "", "jar", it)
      }
      runAfter = registerMuzzleTask(directive, artifact, project, runAfter, muzzleBootstrap, muzzleTooling)
      muzzleReportTasks.add(runAfter)
      project.logger.info("configured $directive")
    }
    if (muzzleReportTasks.isEmpty() && !directives.any { it.assertPass }) {
      muzzleReportTasks.add(muzzleTask)
    }
    val timingTask = project.tasks.register<MuzzleEndTask>("muzzle-end") {
      sourceFile.set(project.projectDir.relativeTo(project.rootProject.projectDir).invariantSeparatorsPath)
      muzzleResultFiles.from(muzzleReportTasks.map { it.flatMap { task -> task.result } })
    }
    runAfter.configure { finalizedBy(timingTask) }
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
    private fun registerMuzzleTask(
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
        this.muzzleBootstrap.from(muzzleBootstrap)
        this.muzzleTooling.from(muzzleTooling)
      }

      runAfterTask.configure {
        finalizedBy(muzzleTask)
      }
      return muzzleTask
    }
  }
}
