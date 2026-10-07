package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.planner.MavenMuzzleResolutionService
import datadog.gradle.plugin.muzzle.planner.MuzzlePlannedVersion
import datadog.gradle.plugin.muzzle.planner.MuzzlePlanningRequest
import datadog.gradle.plugin.muzzle.planner.MuzzlePlansValueSource
import datadog.gradle.plugin.muzzle.planner.MuzzleTaskPlanner
import datadog.gradle.plugin.muzzle.tasks.MuzzleEndTask
import datadog.gradle.plugin.muzzle.tasks.MuzzleGenerateReportTask
import datadog.gradle.plugin.muzzle.tasks.MuzzleGetReferencesTask
import datadog.gradle.plugin.muzzle.tasks.MuzzleTask
import kotlin.random.Random
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.time.Instant
import javax.inject.Inject

/**
 * muzzle task plugin which runs muzzle validation against a range of dependencies.
 */
class MuzzlePlugin @Inject constructor(private val buildFeatures: BuildFeatures) : Plugin<Project> {
  /**
   * Applies the Muzzle plugin to the given project.
   *
   * This function sets up the necessary configurations, dependencies, and tasks for muzzle validation.
   * It registers the muzzle extension, configures bootstrap/tooling dependencies, and sets up tasks for
   * compiling, running, printing, and reporting muzzle checks. Validation tasks are registered lazily.
   *
   * @param project The Gradle project to apply the plugin to.
   */
  override fun apply(project: Project) {
    // create extension first, if java plugin is applied after muzzle
    project.extensions.create<MuzzleExtension>("muzzle", project.objects, project.providers)

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

    val validationTasks = MuzzleValidationTasks(project.providers) {
      val directives = extension.directives.toList()
      muzzleTask.configure {
        checkCompileTimeDependencies.set(!directives.any { it.assertPass })
      }
      val checks = muzzlePlans(project, directives).map { plan ->
        val original = directives[plan.directiveIndex]
        val directive = if (plan.assertPass == original.assertPass) {
          original
        } else {
          original.inverse(requireNotNull(plan.version))
        }
        val artifact = plan.version?.let {
          DefaultArtifact(directive.group, directive.module, directive.classifier ?: "", "jar", it)
        }
        val check = registerMuzzleTask(directive, artifact, project, muzzleBootstrap, muzzleTooling)
        project.logger.info("configured $directive")
        check
      }

      val muzzleReportTasks = if (checks.isEmpty() && !directives.any { it.assertPass }) {
        listOf(muzzleTask)
      } else {
        checks
      }
      val timingTask = project.tasks.register<MuzzleEndTask>("muzzle-end") {
        sourceFile.set(project.projectDir.relativeTo(project.rootProject.projectDir).invariantSeparatorsPath)
        muzzleResultFiles.from(muzzleReportTasks.map { it.flatMap { task -> task.result } })
      }
      val executionChain: List<TaskProvider<out Task>> = checks + timingTask
      executionChain.zipWithNext { current, next ->
        current.configure { finalizedBy(next) }
      }
      setOf(executionChain.first().get())
    }
    muzzleTask.configure {
      finalizedBy(validationTasks.tasks)
    }
  }

  private fun muzzlePlans(project: Project, directives: List<MuzzleDirective>): List<MuzzlePlannedVersion> {
    val minimumAgeHours = project.extensions.getByType<MuzzleExtension>().minimumDependencyAgeHours.get()
    if (buildFeatures.configurationCache.active.get()) {
      // Only selected modules become cache inputs; revalidate their coordinates on cache reuse.
      return project.providers.of(MuzzlePlansValueSource::class.java) {
        parameters.requests.set(listOf(MuzzlePlanningRequest(project.path, minimumAgeHours, directives)))
        parameters.samplingSeed.set(Random.nextLong())
      }.get()
    }
    val planner = sharedTaskPlanner(project, minimumAgeHours)
    return directives.flatMapIndexed { index, directive ->
      planner.plan(listOf(directive)).map { plan ->
        MuzzlePlannedVersion(project.path, index, plan.artifact?.version, plan.directive.assertPass)
      }
    }
  }

  private fun sharedTaskPlanner(project: Project, minimumAgeHours: Int): MuzzleTaskPlanner {
    val properties = project.rootProject.extensions.extraProperties
    val key = "datadogMuzzlePlanning"
    if (!properties.has(key)) {
      properties.set(key, SharedMuzzlePlanning())
    }
    val planning = properties.get(key) as SharedMuzzlePlanning
    // Repository IDs can be reused for different URLs, so keep their local metadata separate.
    val repositories = project.extensions.getByType<MuzzleExtension>().directives
      .map { it.additionalRepositories.toList() }.distinct()
    return planning.planner(repositories, minimumAgeHours)
  }

  /** Shares repository sessions and a fixed cutoff while respecting each module's cooldown. */
  private class SharedMuzzlePlanning {
    private val system = MuzzleMavenRepoUtils.newRepositorySystem()
    private val startedAt = Instant.now()
    private val sessions = mutableMapOf<List<List<Triple<String, String, String>>>, RepositorySystemSession>()
    private val ages = mutableMapOf<Int, MuzzleDependencyAge>()
    private val planners = mutableMapOf<Pair<List<List<Triple<String, String, String>>>, Int>, MuzzleTaskPlanner>()

    fun planner(repositories: List<List<Triple<String, String, String>>>, minimumAgeHours: Int): MuzzleTaskPlanner =
      planners.getOrPut(repositories to minimumAgeHours) {
        val session = sessions.getOrPut(repositories) { MuzzleMavenRepoUtils.newRepositorySystemSession(system) }
        val age = ages.getOrPut(minimumAgeHours) { MuzzleDependencyAge(minimumAgeHours, startedAt) }
        MuzzleTaskPlanner(MavenMuzzleResolutionService(system, session, age))
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
     * @param muzzleBootstrap The configuration provider for agent bootstrap dependencies.
     * @param muzzleTooling The configuration provider for agent tooling dependencies.
     * @return The muzzle task provider.
     */
    private fun registerMuzzleTask(
      muzzleDirective: MuzzleDirective,
      versionArtifact: Artifact?,
      instrumentationProject: Project,
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

      return muzzleTask
    }
  }
}
