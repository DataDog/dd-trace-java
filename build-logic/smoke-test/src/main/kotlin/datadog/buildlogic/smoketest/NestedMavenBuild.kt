package datadog.buildlogic.smoketest

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Runs a nested Maven build inside [applicationDir] via the root Maven wrapper.
 *
 * The nested build is expected to honour `-Dtarget.dir=<path>` and write outputs under that
 * directory so Gradle can track the artifact under [applicationBuildDir].
 */
@CacheableTask
abstract class NestedMavenBuild @Inject constructor(
  private val objects: ObjectFactory,
  javaToolchains: JavaToolchainService,
) : DefaultTask() {

  init {
    javaLauncher.convention(
      javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(DEFAULT_NESTED_JAVA_VERSION))
      },
    )
    goals.convention(listOf("package"))
    arguments.convention(emptyList())
    environment.convention(emptyMap())
    mavenOpts.convention("")
    useMavenLocalRepository.convention(false)
  }

  @get:Internal
  abstract val applicationDir: DirectoryProperty

  @get:InputFiles
  @get:IgnoreEmptyDirectories
  @get:PathSensitive(PathSensitivity.RELATIVE)
  val applicationSources: FileTree =
    objects.fileTree().from(applicationDir).matching {
      exclude("target/**")
    }

  @get:OutputDirectory
  abstract val applicationBuildDir: DirectoryProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val mavenExecutable: RegularFileProperty

  @get:InputFiles
  @get:IgnoreEmptyDirectories
  @get:PathSensitive(PathSensitivity.RELATIVE)
  val mavenWrapperFiles: FileTree =
    objects.fileTree().from(project.rootProject.layout.projectDirectory.dir(".mvn/wrapper")).matching {
      include("maven-wrapper.properties", "maven-wrapper.jar")
    }

  @get:Nested
  abstract val javaLauncher: Property<JavaLauncher>

  @get:Input
  abstract val goals: ListProperty<String>

  @get:Input
  abstract val arguments: ListProperty<String>

  @get:Input
  abstract val environment: MapProperty<String, String>

  @get:Input
  @get:Optional
  abstract val mavenOpts: Property<String>

  @get:Input
  @get:Optional
  abstract val mavenRepositoryProxy: Property<String>

  @get:Input
  abstract val useMavenLocalRepository: Property<Boolean>

  /** Timeout, in seconds, for the nested Maven build process. */
  @get:Input
  @get:Optional
  abstract val buildTimeoutSeconds: Property<Long>

  @get:Internal
  abstract val mavenLocalRepository: DirectoryProperty

  @TaskAction
  fun runNestedBuild() {
    val appDir = applicationDir.get().asFile
    val appBuildDirFile = applicationBuildDir.get().asFile
    val targetDir = appBuildDirFile.resolve("target")
    val daemonJavaHome = javaLauncher.get().metadata.installationPath.asFile
    val command = mavenCommand(mavenExecutable.get().asFile).apply {
      add("-Dtarget.dir=${targetDir.absolutePath}")
      if (useMavenLocalRepository.get()) {
        add("-Dmaven.repo.local=${mavenLocalRepository.get().asFile.absolutePath}")
      }
      addAll(arguments.get())
      addAll(goals.get())
    }

    val process = ProcessBuilder(command)
      .directory(appDir)
      .redirectOutput(ProcessBuilder.Redirect.INHERIT)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .apply {
        environment().apply {
          clear()
          putAll(nestedEnvironment(daemonJavaHome))
        }
      }
      .start()

    try {
      val timeoutSeconds = buildTimeoutSeconds.orNull
      val completed =
        if (timeoutSeconds == null) {
          process.waitFor()
          true
        } else {
          process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        }
      if (!completed) {
        process.destroyForcibly()
        throw GradleException(
          "Nested Maven build timed out after $timeoutSeconds seconds: " +
            command.joinToString(" "),
        )
      }
    } catch (e: InterruptedException) {
      process.destroyForcibly()
      Thread.currentThread().interrupt()
      throw GradleException("Interrupted while running nested Maven build", e)
    }
    val exitCode = process.exitValue()
    if (exitCode != 0) {
      throw GradleException(
        "Nested Maven build failed with exit code $exitCode: ${command.joinToString(" ")}",
      )
    }
  }

  private fun nestedEnvironment(daemonJavaHome: File): Map<String, String> {
    val env = System.getenv().toMutableMap()
    val repositoryProxy = mavenRepositoryProxy.orNull?.takeIf { it.isNotBlank() }
    if (repositoryProxy != null) {
      env["MAVEN_REPOSITORY_PROXY"] = repositoryProxy
      env.putIfAbsent("MVNW_REPOURL", repositoryProxy.trimEnd('/'))
    }
    val opts = mavenOpts.orNull
    if (!opts.isNullOrBlank()) {
      env["MAVEN_OPTS"] = opts
    }
    env["JAVA_HOME"] = daemonJavaHome.absolutePath
    env.putAll(environment.get())
    return env
  }

  private fun mavenCommand(executable: File): MutableList<String> =
    if (isWindows()) {
      mutableListOf("cmd", "/c", executable.absolutePath)
    } else {
      mutableListOf(executable.absolutePath)
  }

  companion object {
    internal fun mavenWrapperName(osName: String = System.getProperty("os.name")): String =
      if (isWindows(osName)) {
        "mvnw.cmd"
      } else {
        "mvnw"
      }

  }
}
