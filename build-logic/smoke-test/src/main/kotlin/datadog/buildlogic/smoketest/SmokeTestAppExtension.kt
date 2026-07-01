package datadog.buildlogic.smoketest

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider
import java.util.Locale
import javax.inject.Inject

/**
 * Project extension that wires a nested build task for a smoke-test application.
 *
 * The plugin only contributes a task when the consumer calls [gradleApp] or [mavenApp]; if
 * the extension stays unconfigured, the plugin is a no-op and consumers can register tasks
 * directly.
 */
abstract class SmokeTestAppExtension @Inject constructor(
  private val project: Project,
  javaToolchains: JavaToolchainService,
) {

  /**
   * Gradle version used by the nested daemon. Defaults to [DEFAULT_NESTED_GRADLE_VERSION] —
   * the version pinned for smoke-test applications whose Spring Boot plugin is incompatible
   * with Gradle 9.
   */
  abstract val gradleVersion: Property<String>

  /**
   * Optional base URL for Gradle distribution downloads. Defaults to the CI-provided MASS read
   * URL when present, so Tooling API downloads go through the pull-through cache.
   */
  abstract val gradleDistributionBaseUrl: Property<String>

  /**
   * JDK used by the nested build. Defaults to a [DEFAULT_NESTED_JAVA_VERSION] toolchain;
   * override to pin a different JDK if the nested application's build chain requires it.
   * The inner build script is responsible for pinning the produced bytecode level (e.g.
   * `java { sourceCompatibility = JavaVersion.VERSION_1_8 }`).
   */
  abstract val javaLauncher: Property<JavaLauncher>

  /** Directory containing the nested project's `settings.gradle` + sources. */
  abstract val applicationDir: DirectoryProperty

  /**
   * Directory the nested build writes its outputs to. Gradle applications are expected to
   * honour `-PappBuildDir=<path>`; Maven applications are expected to honour
   * `-Dtarget.dir=<path>`.
   */
  abstract val applicationBuildDir: DirectoryProperty

  internal abstract val projectJars: ListProperty<NestedBuildProjectJar>

  internal abstract val initScripts: ListProperty<String>

  internal abstract val gradleProperties: MapProperty<String, String>

  init {
    applicationDir.convention(project.layout.projectDirectory.dir("application"))
    applicationBuildDir.convention(project.layout.buildDirectory.dir("application"))
    gradleVersion.convention(DEFAULT_NESTED_GRADLE_VERSION)
    gradleDistributionBaseUrl.convention(
      project.providers.environmentVariable(MASS_READ_URL_ENV),
    )
    javaLauncher.convention(
      javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(DEFAULT_NESTED_JAVA_VERSION))
      },
    )

    val isCi = project.providers.environmentVariable("CI")
      .map { it.equals("true", ignoreCase = true) }
      .orElse(false)
    initScripts.convention(
      isCi.map {
        if (it) {
          listOf(PROXY_REPOSITORIES_INIT_SCRIPT)
        } else {
          emptyList()
        }
      },
    )
    gradleProperties.convention(
      isCi.map {
        if (it) {
          proxyGradleProperties()
        } else {
          emptyMap()
        }
      },
    )
  }

  /**
   * Register the nested-build task and wire the produced artifact into every `Test` task as
   * a system property. Calling this triggers task registration; consumers that prefer to
   * register [NestedGradleBuild] manually can leave [gradleApp] uncalled.
   */
  fun gradleApp(action: Action<GradleAppSpec>) {
    val spec = project.objects.newInstance<GradleAppSpec>()
    action.execute(spec)
    val taskName = requireNotNull(spec.taskName.orNull) {
      "smokeTestApp.gradleApp { taskName = ... } is required"
    }
    val artifactPath = requireNotNull(spec.artifactPath.orNull) {
      "smokeTestApp.gradleApp { artifactPath = ... } is required"
    }
    val sysProperty = requireNotNull(spec.sysProperty.orNull) {
      "smokeTestApp.gradleApp { sysProperty = ... } is required"
    }
    val nestedTasks = spec.nestedTasks.orNull?.takeIf { it.isNotEmpty() } ?: listOf(taskName)

    val taskProvider: TaskProvider<NestedGradleBuild> =
      project.tasks.register<NestedGradleBuild>(taskName) {
        applicationDir.set(this@SmokeTestAppExtension.applicationDir)
        applicationBuildDir.set(this@SmokeTestAppExtension.applicationBuildDir)
        gradleVersion.set(this@SmokeTestAppExtension.gradleVersion)
        gradleDistributionBaseUrl.set(this@SmokeTestAppExtension.gradleDistributionBaseUrl)
        javaLauncher.set(this@SmokeTestAppExtension.javaLauncher)
        tasksToRun.set(nestedTasks)
        buildArguments.set(spec.buildArguments)
        environment.set(spec.environment)
        buildCacheEnabled.set(spec.buildCacheEnabled)
        spec.stopTimeoutSeconds.orNull?.let(stopTimeoutSeconds::set)
        projectJars.set(this@SmokeTestAppExtension.projectJars)
      }

    wireTestTasks(taskProvider, artifactPath, sysProperty, spec.additionalSystemProperties)
  }

  /** Register a Maven nested-build task and expose its artifact to every `Test` task. */
  fun mavenApp(action: Action<MavenAppSpec>) {
    val spec = project.objects.newInstance<MavenAppSpec>()
    spec.mavenExecutable.convention(rootMavenExecutable())
    spec.mavenRepositoryProxy.convention(
      project.providers.environmentVariable("MAVEN_REPOSITORY_PROXY"),
    )
    spec.mavenLocalRepository.convention(project.rootProject.layout.projectDirectory.dir(".mvn/caches"))
    spec.useMavenLocalRepository.convention(isCiProvider())
    action.execute(spec)

    val taskName = requireNotNull(spec.taskName.orNull) {
      "smokeTestApp.mavenApp { taskName = ... } is required"
    }
    val artifactPath = requireNotNull(spec.artifactPath.orNull) {
      "smokeTestApp.mavenApp { artifactPath = ... } is required"
    }
    val sysProperty = requireNotNull(spec.sysProperty.orNull) {
      "smokeTestApp.mavenApp { sysProperty = ... } is required"
    }

    val taskProvider: TaskProvider<NestedMavenBuild> =
      project.tasks.register<NestedMavenBuild>(taskName) {
        applicationDir.set(this@SmokeTestAppExtension.applicationDir)
        applicationBuildDir.set(this@SmokeTestAppExtension.applicationBuildDir)
        javaLauncher.set(this@SmokeTestAppExtension.javaLauncher)
        mavenExecutable.set(spec.mavenExecutable)
        goals.set(spec.goals)
        arguments.set(spec.arguments)
        environment.set(spec.environment)
        mavenOpts.set(spec.mavenOpts)
        mavenRepositoryProxy.set(spec.mavenRepositoryProxy)
        useMavenLocalRepository.set(spec.useMavenLocalRepository)
        mavenLocalRepository.set(spec.mavenLocalRepository)
        spec.buildTimeoutSeconds.orNull?.let(buildTimeoutSeconds::set)
      }

    wireTestTasks(taskProvider, artifactPath, sysProperty, spec.additionalSystemProperties)
  }

  private fun wireTestTasks(
    taskProvider: TaskProvider<*>,
    artifactPath: String,
    sysProperty: String,
    additionalSystemProperties: MapProperty<String, String>,
  ) {
    val artifactProvider: Provider<RegularFile> = applicationBuildDir.file(artifactPath)
    val extras = additionalSystemProperties.get().mapValues { (_, relativePath) ->
      applicationBuildDir.file(relativePath)
    }
    project.tasks.withType<Test>().configureEach {
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
    val cfg = createExtraJarConfiguration(propertyName)
    project.dependencies.add(cfg.name, sourceProject)
    addProjectJarFromConfiguration(propertyName, cfg)
  }

  /**
   * Forward a non-default artifact configuration from [sourceProject]. Use this when the
   * upstream project exposes its build output under a configuration other than the default
   * (e.g. `shadowJar`).
   */
  fun projectJar(propertyName: String, sourceProject: Project, configuration: String) {
    val cfg = createExtraJarConfiguration(propertyName)
    project.dependencies.add(
      cfg.name,
      project.dependencies.project(
        mapOf("path" to sourceProject.path, "configuration" to configuration),
      ),
    )
    addProjectJarFromConfiguration(propertyName, cfg)
  }

  private fun createExtraJarConfiguration(propertyName: String): Configuration {
    val configurationName = "smokeTestAppExtraJar" +
      propertyName.replaceFirstChar { it.titlecase(Locale.ROOT) }
    return project.configurations.maybeCreate(configurationName).apply {
      isCanBeConsumed = false
      isCanBeResolved = true
      isTransitive = false
      description = "Jar artifact forwarded as -P$propertyName into the smoke-test nested build"
    }
  }

  /**
   * Lower-level overload for the rare case where the caller already has a provider of the
   * file. The caller is responsible for the upstream task dependency.
   */
  fun projectJar(propertyName: String, file: Provider<RegularFile>) {
    projectJars.add(
      project.objects.newInstance<NestedBuildProjectJar>().apply {
        this.propertyName.set(propertyName)
        this.file.set(file)
      },
    )
  }

  private fun addProjectJarFromConfiguration(propertyName: String, cfg: Configuration) {
    projectJars.add(
      project.objects.newInstance<NestedBuildProjectJar>().apply {
        this.propertyName.set(propertyName)
        // Configuration.elements yields a Provider that carries the producing task dependency,
        // so wiring it into the task's @InputFile both tracks file contents and arranges build
        // order.
        this.file.set(
          cfg.elements.map { files ->
            project.objects.fileProperty().fileValue(files.single().asFile).get()
          },
        )
      },
    )
  }

  private fun proxyGradleProperties(): Map<String, String> {
    val properties = mutableMapOf<String, String>()
    addGradleProperty(properties, "gradlePluginProxy")
    addGradleProperty(properties, "mavenRepositoryProxy")
    return properties
  }

  private fun addGradleProperty(properties: MutableMap<String, String>, name: String) {
    val value = project.providers.gradleProperty(name).orNull
    if (!value.isNullOrBlank()) {
      properties[name] = value
    }
  }

  private fun rootMavenExecutable(): Provider<RegularFile> =
    project.providers.provider {
      project.rootProject.layout.projectDirectory.file(NestedMavenBuild.mavenWrapperName())
    }

  private fun isCiProvider(): Provider<Boolean> =
    project.providers.environmentVariable("CI")
      .map { it.equals("true", ignoreCase = true) }
      .orElse(false)
}

/** Common DSL for a smoke-test application artifact. */
abstract class ApplicationSpec @Inject constructor() {

  init {
    additionalSystemProperties.convention(emptyMap())
  }

  /** Outer task name registered by the smoke-test plugin. */
  abstract val taskName: Property<String>

  /** Path to the produced artifact, relative to `applicationBuildDir`. */
  abstract val artifactPath: Property<String>

  /** System property name set on Test tasks to point them at the produced artifact. */
  abstract val sysProperty: Property<String>

  /**
   * Additional system properties to forward to every `Test` task, keyed by property name with
   * values resolved against `applicationBuildDir`. Use this for smoke tests that need more
   * than the single primary artifact path (e.g. a separately unpacked server install).
   */
  abstract val additionalSystemProperties: MapProperty<String, String>
}

/** DSL describing a nested Gradle invocation for one smoke-test application. */
abstract class GradleAppSpec @Inject constructor() : ApplicationSpec() {

  init {
    buildCacheEnabled.convention(false)
    buildArguments.convention(emptyList())
    environment.convention(emptyMap())
  }

  /** Tasks run inside the nested build. Defaults to `[taskName]`. */
  abstract val nestedTasks: ListProperty<String>

  /** Extra arguments passed to the nested Gradle invocation. */
  abstract val buildArguments: ListProperty<String>

  /**
   * Extra environment variables exposed to the nested Gradle daemon. Merged on top of the outer
   * process environment; Gradle launcher variables are reserved by the nested build task so CI
   * settings do not leak into pinned Gradle versions. Use this for nested tooling that reads
   * `JAVA_HOME`, `GRAALVM_HOME`, etc. from the env.
   */
  abstract val environment: MapProperty<String, String>

  /**
   * Whether to enable the build cache in the nested Gradle invocation. 
   * Gradle's org.gradle.caching flag is resolved from many sources (project, 
   * init, gradle user home, environment, command line) and any of them silently 
   * enables the build cache for nested builds. For this reasons it defaults to `false`.
   * Opt in only when the inner plugin chain keys its cached outputs on everything that
   * varies between runs (e.g. Quarkus's native-image does not track `GRAALVM_HOME`).
   * `--build-cache` / `--no-build-cache` is passed explicitly either way.
   */
  abstract val buildCacheEnabled: Property<Boolean>

  /** Timeout, in seconds, for stopping the nested Gradle daemon after the build. */
  abstract val stopTimeoutSeconds: Property<Long>
}

/** DSL describing a nested Maven invocation for one smoke-test application. */
abstract class MavenAppSpec @Inject constructor() : ApplicationSpec() {

  init {
    goals.convention(listOf("package"))
    arguments.convention(emptyList())
    environment.convention(emptyMap())
    mavenOpts.convention("")
  }

  /** Goals run inside the nested Maven build. Defaults to `package`. */
  abstract val goals: ListProperty<String>

  /** Extra arguments passed to the nested Maven invocation before [goals]. */
  abstract val arguments: ListProperty<String>

  /** Extra environment variables exposed to the nested Maven process. */
  abstract val environment: MapProperty<String, String>

  /** Optional `MAVEN_OPTS` value for the nested Maven process. */
  abstract val mavenOpts: Property<String>

  /** Root Maven wrapper executable used to launch the nested build. */
  abstract val mavenExecutable: RegularFileProperty

  /** Maven repository proxy used by the wrapper and by Maven builds that read the env var. */
  abstract val mavenRepositoryProxy: Property<String>

  /** Whether to pass [mavenLocalRepository] as `-Dmaven.repo.local`. Defaults to CI only. */
  abstract val useMavenLocalRepository: Property<Boolean>

  /** Local repository path used when [useMavenLocalRepository] is enabled. */
  abstract val mavenLocalRepository: DirectoryProperty

  /** Timeout, in seconds, for the nested Maven build process. */
  abstract val buildTimeoutSeconds: Property<Long>
}

/**
 * Default Gradle distribution version for the nested daemon. Pinned to a Gradle 8 release
 * because the Spring Boot Gradle plugin pre-3.5.0 calls `Configuration.getUploadTaskName()`,
 * removed in Gradle 9.
 */
const val DEFAULT_NESTED_GRADLE_VERSION = "8.14.5"

/**
 * Default JDK language version for the nested build. JDK 21 is the version the root build
 * requires for Gradle 9; standardising nested builds on the same JDK avoids pulling a
 * second toolchain onto dev machines and CI runners. Inner build scripts cross-compile down
 * to their actual bytecode target via `java { sourceCompatibility = ... }`.
 */
const val DEFAULT_NESTED_JAVA_VERSION = 21

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
