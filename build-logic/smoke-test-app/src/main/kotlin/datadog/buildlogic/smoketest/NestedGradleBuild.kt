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
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
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
import org.gradle.kotlin.dsl.newInstance
import org.gradle.tooling.GradleConnector
import java.io.File
import java.util.Properties
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
 *
 * Nested builds share a retained Gradle user home, separate from the outer build's settings.
 */
@CacheableTask
abstract class NestedGradleBuild @Inject constructor(
  private val objects: ObjectFactory,
  javaToolchains: JavaToolchainService,
) : DefaultTask() {

  @get:Internal
  val daemonService: Provider<NestedGradleDaemonService> = project.gradle.sharedServices.registerIfAbsent(
    "nestedGradleDaemons",
    NestedGradleDaemonService::class.java,
  ) {}

  init {
    usesService(daemonService)
    gradleVersion.convention(DEFAULT_NESTED_GRADLE_VERSION)
    gradleDistributionSha256Sum.convention(DEFAULT_NESTED_GRADLE_SHA256_SUM)
    gradleUserHome.convention(
      objects.directoryProperty().fileValue(
        project.gradle.gradleUserHomeDir.resolve("nested-gradle-user-home"),
      ),
    )
    gradleDistributionBaseUrl.convention(
      project.providers.environmentVariable(MASS_READ_URL_ENV),
    )
    initScripts.convention(emptyList())
    gradleProperties.convention(emptyMap())
    mavenRepositoryProxy.convention(
      project.providers.gradleProperty(MAVEN_REPOSITORY_PROXY_PROPERTY),
    )
    gradlePluginProxy.convention(
      project.providers.gradleProperty(GRADLE_PLUGIN_PROXY_PROPERTY),
    )
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

  /** Expected distribution SHA-256. Override together with [gradleVersion]. */
  @get:Input
  abstract val gradleDistributionSha256Sum: Property<String>

  /** Retained nested Gradle home, separate from the outer home and excluded from task outputs. */
  @get:Internal
  abstract val gradleUserHome: DirectoryProperty

  @get:Input
  abstract val initScripts: ListProperty<String>

  @get:Input
  abstract val gradleProperties: MapProperty<String, String>

  /**
   * Repository proxy to use for dependencies, defaulting to the `mavenRepositoryProxy` property of
   * the owning build.
   *
   * A nested build runs against a dedicated Gradle user home, so it never sees the
   * `gradle.properties` that configures the outer build. Forwarding this explicitly is what lets
   * nested builds resolve for developers and CI machines that cannot reach Maven Central directly.
   */
  @get:Input
  @get:Optional
  abstract val mavenRepositoryProxy: Property<String>

  /** Plugin repository proxy, defaulting to the `gradlePluginProxy` property of the owning build. */
  @get:Input
  @get:Optional
  abstract val gradlePluginProxy: Property<String>

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

  /** Forward a JAR with runtime-classpath normalisation and an explicit base [version]. */
  fun projectJar(name: String, file: Provider<RegularFile>, version: Provider<String>) {
    projectJars.add(
      objects.newInstance<NestedBuildProjectJar>().apply {
        propertyName.set(name)
        this.file.set(file)
        this.version.set(version)
      },
    )
  }

  /** Configure a normalised JAR input, including its base version, via a typed action. */
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
    val gradleUserHomeDir = gradleUserHome.get().asFile
    val declaredProperties = gradleProperties.get()
    val proxyProperties = proxyProperties(declaredProperties)
    val initScriptFiles = writeInitScripts(proxyProperties.isNotEmpty())

    val args = buildList {
      initScriptFiles.forEach { script ->
        add("--init-script")
        add(script.absolutePath)
      }
      add(if (buildCacheEnabled.get()) "--build-cache" else "--no-build-cache")
      add("-PappBuildDir=${appBuildDirFile.absolutePath}")
      declaredProperties.forEach { (name, value) ->
        addGradleProperty(name, value)
      }
      // Forward the proxies unless the caller already declared them, so that every nested build
      // resolves the same way as the outer one.
      proxyProperties
        .filterKeys { !declaredProperties.containsKey(it) }
        .forEach { (name, value) -> addGradleProperty(name, value) }
      projectJars.get().forEach { entry ->
        add("-P${entry.propertyName.get()}=${entry.file.get().asFile.absolutePath}")
      }
      addAll(buildArguments.get())
      addAll(listOf("--project-dir", appDir.absolutePath))
    }

    // Wrapper properties let Gradle verify the distribution while managing its installation and caches.
    val connector = GradleConnector.newConnector()
      .forProjectDirectory(writeDistributionProject())
      .useGradleUserHomeDir(gradleUserHomeDir)
      .useBuildDistribution()

    val mergedEnv =
      System.getenv() +
        environment.get() +
        mapOf(
          "GRADLE_ARGS" to "",
          "GRADLE_OPTS" to "",
          "GRADLE_USER_HOME" to gradleUserHomeDir.absolutePath,
        )

    daemonService.get().register(gradleUserHomeDir, gradleVersion.get(), daemonJavaHome, mergedEnv)
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
  }

  private fun writeDistributionProject(): File {
    val directory = temporaryDir.resolve("distribution").apply { mkdirs() }
    directory.resolve("settings.gradle").writeText("rootProject.name = 'nested-gradle-bootstrap'\n")
    val propertiesFile = directory.resolve("gradle/wrapper/gradle-wrapper.properties")
    propertiesFile.parentFile.mkdirs()
    propertiesFile.outputStream().use { output ->
      Properties().apply {
        setProperty("distributionUrl", gradleDistributionUri(gradleDistributionBaseUrl.orNull, gradleVersion.get()).toString())
        setProperty("distributionSha256Sum", gradleDistributionSha256Sum.get())
      }.store(output, null)
    }
    return directory
  }

  private fun proxyProperties(declaredProperties: Map<String, String>): Map<String, String> = buildMap {
    addProxyProperty(
      MAVEN_REPOSITORY_PROXY_PROPERTY,
      declaredProperties,
      mavenRepositoryProxy.orNull,
    )
    addProxyProperty(
      GRADLE_PLUGIN_PROXY_PROPERTY,
      declaredProperties,
      gradlePluginProxy.orNull,
    )
  }

  private fun writeInitScripts(hasProxy: Boolean): List<File> {
    val declared = initScripts.get()
    // Nested build scripts are not required to read the proxy properties themselves, so inject the
    // repositories as well. Skipped when the caller already declared this script.
    val effective =
      if (!hasProxy || declared.contains(PROXY_REPOSITORIES_INIT_SCRIPT)) {
        declared
      } else {
        listOf(PROXY_REPOSITORIES_INIT_SCRIPT) + declared
      }
    return effective.mapIndexed { index, script ->
      temporaryDir.resolve("init-$index.init.gradle.kts").also { file ->
        file.writeText(script)
      }
    }
  }
}

private fun MutableMap<String, String>.addProxyProperty(
  name: String,
  declaredProperties: Map<String, String>,
  defaultValue: String?,
) {
  val value =
    if (declaredProperties.containsKey(name)) {
      declaredProperties[name]
    } else {
      defaultValue
    }
  value?.takeIf { it.isNotBlank() }?.let { put(name, it) }
}

private fun MutableList<String>.addGradleProperty(name: String, value: String?) {
  if (!value.isNullOrBlank()) {
    add("-P$name=$value")
  }
}

/** Gradle property naming the repository proxy to resolve dependencies through. */
internal const val MAVEN_REPOSITORY_PROXY_PROPERTY = "mavenRepositoryProxy"

/** Gradle property naming the repository proxy to resolve plugins through. */
internal const val GRADLE_PLUGIN_PROXY_PROPERTY = "gradlePluginProxy"

internal val PROXY_REPOSITORIES_INIT_SCRIPT: String =
  NestedGradleBuild::class.java.getResource("proxy-repositories.init.gradle.kts")
    ?.readText()
    ?: error("Missing proxy-repositories.init.gradle.kts resource")
