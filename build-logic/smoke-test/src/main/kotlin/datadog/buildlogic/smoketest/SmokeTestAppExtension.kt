package datadog.buildlogic.smoketest

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.CommandLineArgumentProvider
import java.util.Locale
import javax.inject.Inject

/**
 * Project extension that wires a [NestedGradleBuild] task for a smoke-test application.
 *
 * The plugin only contributes a task when the consumer calls [application]; if the extension
 * stays unconfigured, the plugin is a no-op and consumers can register [NestedGradleBuild]
 * directly.
 */
abstract class SmokeTestAppExtension @Inject constructor(private val project: Project) {

  /** Gradle version used by the nested daemon. Defaults to the root build's version. */
  abstract val gradleVersion: Property<String>

  /** JDK used by the nested daemon. Required when calling [application]. */
  abstract val javaLauncher: Property<JavaLauncher>

  /** Directory containing the nested project's `settings.gradle` + sources. */
  abstract val applicationDir: DirectoryProperty

  /**
   * Directory the nested build writes its outputs to. The nested build script is expected to
   * honour `-PappBuildDir=<path>`; see the existing smoke-test inner builds for the pattern.
   */
  abstract val applicationBuildDir: DirectoryProperty

  internal abstract val projectJars: ListProperty<NestedBuildProjectJar>

  init {
    applicationDir.convention(project.layout.projectDirectory.dir("application"))
    applicationBuildDir.convention(project.layout.buildDirectory.dir("application"))
    gradleVersion.convention(project.gradle.gradleVersion)
  }

  /**
   * Register the nested-build task and wire the produced artifact into every `Test` task as
   * a system property. Calling this triggers task registration; consumers that prefer to
   * register [NestedGradleBuild] manually can leave [application] uncalled.
   */
  fun application(action: Action<ApplicationSpec>) {
    require(javaLauncher.isPresent) {
      "smokeTestApp.javaLauncher must be set before configuring application { ... }"
    }
    val spec = project.objects.newInstance(ApplicationSpec::class.java)
    action.execute(spec)
    val taskName = requireNotNull(spec.taskName.orNull) {
      "smokeTestApp.application { taskName = ... } is required"
    }
    val artifactPath = requireNotNull(spec.artifactPath.orNull) {
      "smokeTestApp.application { artifactPath = ... } is required"
    }
    val sysProperty = requireNotNull(spec.sysProperty.orNull) {
      "smokeTestApp.application { sysProperty = ... } is required"
    }
    val nestedTasks = spec.nestedTasks.orNull?.takeIf { it.isNotEmpty() } ?: listOf(taskName)

    val capturedJars = projectJars
    val capturedAppDir = applicationDir
    val capturedAppBuildDir = applicationBuildDir
    val capturedGradleVersion = gradleVersion
    val capturedJavaLauncher = javaLauncher
    val capturedBuildArguments = spec.buildArguments

    val taskProvider: TaskProvider<NestedGradleBuild> =
      project.tasks.register(taskName, NestedGradleBuild::class.java) {
        applicationDir.set(capturedAppDir)
        applicationBuildDir.set(capturedAppBuildDir)
        gradleVersion.set(capturedGradleVersion)
        javaLauncher.set(capturedJavaLauncher)
        tasksToRun.set(nestedTasks)
        buildArguments.set(capturedBuildArguments)
        projectJars.set(capturedJars)
      }

    val artifactProvider: Provider<RegularFile> = applicationBuildDir.file(artifactPath)
    val extras = spec.additionalSystemProperties.get().mapValues { (_, relativePath) ->
      applicationBuildDir.file(relativePath)
    }
    project.tasks.withType(Test::class.java).configureEach {
      dependsOn(taskProvider)
      jvmArgumentProviders.add(SmokeTestArgProvider(sysProperty, artifactProvider, extras))
    }
  }

  /**
   * Forward the default `jar` artifact from [sourceProject] into the nested build as
   * `-P<propertyName>=<absolute path>`. The jar is consumed via a resolvable [Configuration],
   * which both establishes the correct task dependency and lets Gradle resolve the artifact
   * lazily — no `evaluationDependsOn` is needed.
   */
  fun projectJar(propertyName: String, sourceProject: Project) {
    val configurationName = "smokeTestAppExtraJar" +
      propertyName.replaceFirstChar { it.titlecase(Locale.ROOT) }
    val cfg = project.configurations.maybeCreate(configurationName).apply {
      isCanBeConsumed = false
      isCanBeResolved = true
      isTransitive = false
      description = "Jar artifact forwarded as -P$propertyName into the smoke-test nested build"
    }
    project.dependencies.add(configurationName, sourceProject)
    addProjectJarFromConfiguration(propertyName, cfg)
  }

  /**
   * Lower-level overload for the rare case where the caller already has a provider of the
   * file. The caller is responsible for the upstream task dependency.
   */
  fun projectJar(propertyName: String, file: Provider<RegularFile>) {
    val entry = project.objects.newInstance(NestedBuildProjectJar::class.java)
    entry.propertyName.set(propertyName)
    entry.file.set(file)
    projectJars.add(entry)
  }

  private fun addProjectJarFromConfiguration(propertyName: String, cfg: Configuration) {
    val entry = project.objects.newInstance(NestedBuildProjectJar::class.java)
    entry.propertyName.set(propertyName)
    // Configuration.elements yields a Provider that carries the producing task dependency, so
    // wiring it into the task's @InputFile both tracks file contents and arranges build order.
    entry.file.set(
      cfg.elements.map { files ->
        project.objects.fileProperty().fileValue(files.single().asFile).get()
      }
    )
    projectJars.add(entry)
  }
}

/** DSL describing the nested-build invocation for one smoke-test application. */
abstract class ApplicationSpec @Inject constructor() {
  /** Outer task name; the nested daemon runs the same task by default. */
  abstract val taskName: Property<String>

  /** Path to the produced artifact, relative to `applicationBuildDir`. */
  abstract val artifactPath: Property<String>

  /** System property name set on Test tasks to point them at the produced artifact. */
  abstract val sysProperty: Property<String>

  /** Tasks run inside the nested build. Defaults to `[taskName]`. */
  abstract val nestedTasks: ListProperty<String>

  /** Extra arguments passed to the nested Gradle invocation. */
  abstract val buildArguments: ListProperty<String>

  /**
   * Additional system properties to forward to every `Test` task, keyed by property name with
   * values resolved against `applicationBuildDir`. Use this for smoke tests that need more
   * than the single primary artifact path (e.g. a separately unpacked server install).
   */
  abstract val additionalSystemProperties: MapProperty<String, String>
}

private class SmokeTestArgProvider(
  private val sysProperty: String,
  private val artifact: Provider<RegularFile>,
  private val extras: Map<String, Provider<RegularFile>>,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> =
    buildList {
      add("-D$sysProperty=${artifact.get().asFile.absolutePath}")
      extras.forEach { (key, value) ->
        add("-D$key=${value.get().asFile.absolutePath}")
      }
    }
}
