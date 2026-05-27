package datadog.buildlogic.smoketest

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.newInstance
import org.gradle.tooling.GradleConnector
import javax.inject.Inject

/**
 * Runs a nested Gradle build inside [applicationDir] via the Gradle Tooling API.
 *
 * Lets a smoke test pin a Gradle version (typically older than the root build) and a Java
 * toolchain for the nested daemon, without committing per-application `gradlew` wrappers.
 *
 * The nested build script is expected to honour `-PappBuildDir=<path>` and redirect its
 * `buildDir` to that path so the artifact lands in [applicationBuildDir]. Project artifacts
 * from the root build can be forwarded via [projectJar]; each entry is passed as
 * `-P<propertyName>=<absolute-path>` and tracked as a task input so the nested build re-runs
 * when the upstream jar changes.
 */
abstract class NestedGradleBuild @Inject constructor(
  private val objects: ObjectFactory,
  javaToolchains: JavaToolchainService,
) : DefaultTask() {

  init {
    gradleVersion.convention(DEFAULT_NESTED_GRADLE_VERSION)
    javaLauncher.convention(
      javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(DEFAULT_NESTED_JAVA_VERSION))
      },
    )
  }

  @get:Internal
  abstract val applicationDir: DirectoryProperty

  @get:InputFiles
  @get:IgnoreEmptyDirectories
  @get:PathSensitive(PathSensitivity.RELATIVE)
  val applicationSources: FileTree =
    objects.fileTree().from(applicationDir).matching {
      exclude(".gradle/**", "build/**")
    }

  @get:Input
  abstract val gradleVersion: Property<String>

  @get:Nested
  abstract val javaLauncher: Property<JavaLauncher>

  @get:Input
  abstract val tasksToRun: ListProperty<String>

  @get:Input
  abstract val buildArguments: ListProperty<String>

  /**
   * Extra environment variables for the nested Gradle daemon. Merged on top of the outer
   * process environment — set a key to override an inherited value. The nested build script
   * sees these via `System.getenv()` like any normal environment variable.
   */
  @get:Input
  abstract val environment: MapProperty<String, String>

  @get:Nested
  abstract val projectJars: ListProperty<NestedBuildProjectJar>

  @get:OutputDirectory
  abstract val applicationBuildDir: DirectoryProperty

  /** Forward a root-build jar as `-P<name>=<absolute path>` into the nested build. */
  fun projectJar(name: String, file: Provider<RegularFile>) {
    projectJars.add(
      objects.newInstance<NestedBuildProjectJar>().apply {
        propertyName.set(name)
        this.file.set(file)
      },
    )
  }

  /** Configure additional aspects of the nested build via a typed action. */
  fun projectJar(action: Action<NestedBuildProjectJar>) {
    projectJars.add(
      objects.newInstance<NestedBuildProjectJar>().also(action::execute),
    )
  }

  @TaskAction
  fun runNestedBuild() {
    val appDir = applicationDir.get().asFile
    val appBuildDirFile = applicationBuildDir.get().asFile
    val daemonJavaHome = javaLauncher.get().metadata.installationPath.asFile

    val args = buildList {
      add("-PappBuildDir=${appBuildDirFile.absolutePath}")
      projectJars.get().forEach { entry ->
        add("-P${entry.propertyName.get()}=${entry.file.get().asFile.absolutePath}")
      }
      addAll(buildArguments.get())
    }

    val connector = GradleConnector.newConnector()
      .useGradleVersion(gradleVersion.get())
      .forProjectDirectory(appDir)

    val extraEnv = environment.get()
    val mergedEnv: Map<String, String>? =
      if (extraEnv.isEmpty()) null else System.getenv() + extraEnv

    connector.connect().use { connection ->
      connection.newBuild()
        .forTasks(*tasksToRun.get().toTypedArray())
        .withArguments(args)
        .setJavaHome(daemonJavaHome)
        .apply { if (mergedEnv != null) setEnvironmentVariables(mergedEnv) }
        .setStandardOutput(System.out)
        .setStandardError(System.err)
        .run()
    }
  }
}
