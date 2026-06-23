package datadog.buildlogic.smoketest

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.newInstance
import org.gradle.tooling.GradleConnector
import java.io.File
import java.util.concurrent.TimeUnit
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
@CacheableTask
abstract class NestedGradleBuild @Inject constructor(
  private val objects: ObjectFactory,
  javaToolchains: JavaToolchainService,
) : DefaultTask() {

  init {
    gradleVersion.convention(DEFAULT_NESTED_GRADLE_VERSION)
    gradleDistributionBaseUrl.convention(
      project.providers.environmentVariable(MASS_READ_URL_ENV),
    )
    initScripts.convention(emptyList())
    gradleProperties.convention(emptyMap())
    javaLauncher.convention(
      javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(DEFAULT_NESTED_JAVA_VERSION))
      },
    )
    buildCacheEnabled.convention(false)
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

  /**
   * Optional base URL for Gradle distribution downloads. CI sets this to MASS so nested builds
   * download through the pull-through cache instead of directly from services.gradle.org.
   */
  @get:Input
  @get:Optional
  abstract val gradleDistributionBaseUrl: Property<String>

  @get:Input
  abstract val initScripts: ListProperty<String>

  @get:Input
  abstract val gradleProperties: MapProperty<String, String>

  @get:Nested
  abstract val javaLauncher: Property<JavaLauncher>

  @get:Input
  abstract val tasksToRun: ListProperty<String>

  @get:Input
  abstract val buildArguments: ListProperty<String>

  /**
   * Whether to enable the build cache in the nested Gradle invocation.
   * Gradle's org.gradle.caching flag is resolved from many sources (project, 
   * init, gradle user home, environment, command line) and any of them silently 
   * enables the build cache for nested builds. For this reasons it defaults to `false`.
   * Opt in only when the inner plugin chain keys its cached outputs on everything that
   * varies between runs (e.g. Quarkus's native-image does not track `GRAALVM_HOME`).
   * `--build-cache` / `--no-build-cache` is passed explicitly either way.
   */
  @get:Input
  abstract val buildCacheEnabled: Property<Boolean>

  /** Timeout, in seconds, for stopping the nested Gradle daemon after the build. */
  @get:Input
  @get:Optional
  abstract val stopTimeoutSeconds: Property<Long>

  /**
   * Extra environment variables for the nested Gradle daemon. Merged on top of the outer process
   * environment; Gradle launcher variables are reserved by this task so nested builds do not
   * inherit incompatible outer-build settings. The nested build script sees these via
   * `System.getenv()` like any normal environment variable.
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
    val gradleUserHomeDir = createGradleUserHome()
    val initScriptFiles = writeInitScripts()

    val args = buildList {
      initScriptFiles.forEach { script ->
        add("--init-script")
        add(script.absolutePath)
      }
      add(if (buildCacheEnabled.get()) "--build-cache" else "--no-build-cache")
      add("-PappBuildDir=${appBuildDirFile.absolutePath}")
      gradleProperties.get().forEach { (name, value) ->
        addGradleProperty(name, value)
      }
      projectJars.get().forEach { entry ->
        add("-P${entry.propertyName.get()}=${entry.file.get().asFile.absolutePath}")
      }
      addAll(buildArguments.get())
    }

    val connector = GradleConnector.newConnector()
      .forProjectDirectory(appDir)
      .useGradleUserHomeDir(gradleUserHomeDir)
      .apply {
        val distributionBaseUrl = gradleDistributionBaseUrl.orNull
        if (distributionBaseUrl.isNullOrBlank()) {
          useGradleVersion(gradleVersion.get())
        } else {
          useDistribution(
            gradleDistributionUri(distributionBaseUrl, gradleVersion.get()),
          )
        }
      }

    val mergedEnv =
      System.getenv() +
        environment.get() +
        mapOf(
          "GRADLE_ARGS" to "",
          "GRADLE_OPTS" to "",
          "GRADLE_USER_HOME" to gradleUserHomeDir.absolutePath,
        )

    try {
      connector.connect().use { connection ->
        connection.newBuild()
          .forTasks(*tasksToRun.get().toTypedArray())
          .withArguments(args)
          .setJavaHome(daemonJavaHome)
          .setEnvironmentVariables(mergedEnv)
          .setStandardOutput(System.out)
          .setStandardError(System.err)
          .run()
      }
    } finally {
      stopGradleDaemon(appDir, gradleUserHomeDir, daemonJavaHome, mergedEnv)
      deleteGradleUserHome(gradleUserHomeDir)
    }
  }

  private fun stopGradleDaemon(
    appDir: File,
    gradleUserHomeDir: File,
    daemonJavaHome: File,
    environment: Map<String, String>,
  ) {
    val gradleExecutable = findGradleExecutable(gradleUserHomeDir)
    if (gradleExecutable == null) {
      logger.warn(
        "Could not find nested Gradle executable under {} to stop its daemon",
        gradleUserHomeDir.absolutePath,
      )
      return
    }

    try {
      val processBuilder = ProcessBuilder(gradleExecutable.absolutePath, "--stop")
        .directory(appDir)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
      processBuilder.environment().apply {
        clear()
        putAll(environment)
        put("JAVA_HOME", daemonJavaHome.absolutePath)
      }

      val process = processBuilder.start()
      val timeoutSeconds = stopTimeoutSeconds.orNull
      val completed =
        if (timeoutSeconds == null) {
          process.waitFor()
          true
        } else {
          process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        }
      if (!completed) {
        process.destroyForcibly()
        logger.warn("Timed out after {} seconds while stopping nested Gradle daemon", timeoutSeconds)
        return
      }
      val exitCode = process.exitValue()
      if (exitCode != 0) {
        logger.warn("Nested Gradle daemon stop exited with code {}", exitCode)
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      logger.warn(
        "Interrupted while stopping nested Gradle daemon before deleting its user home",
        e,
      )
    } catch (e: Exception) {
      logger.warn("Could not stop nested Gradle daemon before deleting its user home", e)
    }
  }

  private fun writeInitScripts(): List<File> =
    initScripts.get().mapIndexed { index, script ->
      temporaryDir.resolve("init-$index.init.gradle.kts").also { file ->
        file.writeText(script)
      }
    }

  private fun findGradleExecutable(gradleUserHomeDir: File): File? =
    gradleUserHomeDir.walkTopDown().firstOrNull { file ->
      file.isFile &&
        file.name == gradleExecutableName() &&
        file.parentFile?.name == "bin"
    }

  private fun createGradleUserHome(): File {
    val directory = temporaryDir.resolve("gradle-user-home")
    deleteGradleUserHome(directory)
    if (!directory.mkdirs()) {
      throw GradleException(
        "Could not create nested Gradle user home: ${directory.absolutePath}",
      )
    }
    return directory
  }

  private fun deleteGradleUserHome(directory: File) {
    if (directory.exists() && !directory.deleteRecursively()) {
      logger.warn("Could not delete nested Gradle user home: {}", directory.absolutePath)
    }
  }

  companion object {
    internal fun gradleExecutableName(osName: String = System.getProperty("os.name")): String =
      if (isWindows(osName)) {
        "gradle.bat"
      } else {
        "gradle"
      }
  }
}

private fun MutableList<String>.addGradleProperty(name: String, value: String?) {
  if (!value.isNullOrBlank()) {
    add("-P$name=$value")
  }
}

internal val PROXY_REPOSITORIES_INIT_SCRIPT: String =
  NestedGradleBuild::class.java.getResource("proxy-repositories.init.gradle.kts")
    ?.readText()
    ?: error("Missing proxy-repositories.init.gradle.kts resource")
