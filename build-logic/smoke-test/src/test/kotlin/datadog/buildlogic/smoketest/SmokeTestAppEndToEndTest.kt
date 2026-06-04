package datadog.buildlogic.smoketest

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end tests that drive the plugin through the Gradle Test Kit and a temporary,
 * self-contained Kotlin-DSL test project. The inner "smoke-test application" is itself a
 * minimal Kotlin-DSL Gradle build; the outer build wires it through the `smokeTestApp` DSL.
 *
 * These tests are slow (each test spins up a Gradle daemon) but they are the only way to
 * exercise the Tooling API path end-to-end.
 */
class SmokeTestAppEndToEndTest {

  @TempDir
  lateinit var projectDir: Path

  private val outerSettings get() = projectDir.resolve("settings.gradle.kts").toFile()
  private val outerBuild get() = projectDir.resolve("build.gradle.kts").toFile()
  private val applicationDir get() = projectDir.resolve("application").toFile()

  @BeforeEach
  fun setUp() {
    applicationDir.mkdirs()
  }

  @Test
  fun `application block registers a NestedGradleBuild task with the configured name`() {
    writeOuterSettings()
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("packageApp")
          artifactPath.set("libs/test.jar")
          sysProperty.set("test.path")
        }
      }
      """.trimIndent(),
    )

    val result = runner("tasks", "--all").build()

    assertThat(result.output).contains("packageApp")
  }

  @Test
  fun `nested build produces the configured artifact`() {
    writeOuterSettings()
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("buildJar")
          artifactPath.set("libs/sample.jar")
          sysProperty.set("sample.path")
        }
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      tasks.register<Jar>("buildJar") {
        archiveFileName.set("sample.jar")
        from(file("src"))
      }
      """.trimIndent(),
    )
    File(applicationDir, "src").mkdir()
    File(applicationDir, "src/hello.txt").writeText("hi")

    val result = runner("buildJar").build()

    assertThat(result.task(":buildJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(projectDir.toFile(), "build/application/libs/sample.jar")).exists()
  }

  @Test
  fun `plugin is a no-op when the application block is never called`() {
    writeOuterSettings()
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        // No application block, no javaLauncher — should not blow up.
      }
      """.trimIndent(),
    )

    val result = runner("help").build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  @Test
  fun `manual NestedGradleBuild task registration works without the application block`() {
    writeOuterSettings()
    outerBuild.writeText(
      """
      import datadog.buildlogic.smoketest.NestedGradleBuild

      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      tasks.register<NestedGradleBuild>("customBuild") {
        applicationDir.set(layout.projectDirectory.dir("application"))
        applicationBuildDir.set(layout.buildDirectory.dir("application"))
        gradleVersion.set(gradle.gradleVersion)
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        tasksToRun.set(listOf("buildJar"))
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      tasks.register<Jar>("buildJar") {
        archiveFileName.set("custom.jar")
        from(file("src"))
      }
      """.trimIndent(),
    )
    File(applicationDir, "src").mkdir()
    File(applicationDir, "src/hello.txt").writeText("hi")

    val result = runner("customBuild").build()

    assertThat(result.task(":customBuild")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `nested build clears inherited Gradle launcher environment`() {
    writeOuterSettings()
    val inheritedGradleUserHome = projectDir.resolve("inherited-gradle-user-home").toFile()
    inheritedGradleUserHome.mkdirs()
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("recordGradleEnvironment")
          artifactPath.set("gradle-env.txt")
          sysProperty.set("gradle.env.path")
        }
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      tasks.register("recordGradleEnvironment") {
        val out = layout.buildDirectory.file("gradle-env.txt")
        outputs.file(out)
        doLast {
          out.get().asFile.writeText(
            listOf(
              "GRADLE_ARGS=${'$'}{System.getenv("GRADLE_ARGS") ?: "<null>"}",
              "GRADLE_OPTS=${'$'}{System.getenv("GRADLE_OPTS") ?: "<null>"}",
              "GRADLE_USER_HOME=${'$'}{System.getenv("GRADLE_USER_HOME") ?: "<null>"}",
              "gradleUserHomeDir=${'$'}{gradle.gradleUserHomeDir.absolutePath}",
            ).joinToString(System.lineSeparator())
          )
        }
      }
      """.trimIndent(),
    )

    val result = runner(
      "recordGradleEnvironment",
      environment = mapOf(
        "GRADLE_ARGS" to "--info",
        "GRADLE_OPTS" to "-Ddd.test.gradle.opts=inherited",
        "GRADLE_USER_HOME" to inheritedGradleUserHome.absolutePath,
      ),
    ).build()

    assertThat(result.task(":recordGradleEnvironment")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val envFile = File(projectDir.toFile(), "build/application/gradle-env.txt")
    assertThat(envFile).exists()
    val lines = envFile.readLines()
    assertThat(lines).contains(
      "GRADLE_ARGS=",
      "GRADLE_OPTS=",
    )
    val gradleUserHomeEnv = lines.single { it.startsWith("GRADLE_USER_HOME=") }
      .substringAfter("=")
    val gradleUserHomeDir = lines.single { it.startsWith("gradleUserHomeDir=") }
      .substringAfter("=")
    assertThat(gradleUserHomeEnv).isEqualTo(gradleUserHomeDir)
    assertThat(gradleUserHomeDir).isNotEqualTo(inheritedGradleUserHome.absolutePath)
    assertThat(File(gradleUserHomeDir)).doesNotExist()
  }

  @Test
  fun `nested build receives native app environment and provider backed file inputs`() {
    writeOuterSettings()
    File(projectDir.toFile(), "agent.jar").writeText("agent")
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("recordNativeInputs")
          artifactPath.set("native-inputs.txt")
          sysProperty.set("native.inputs.path")
          buildArguments.add("-Dnative.enabled=true")
          environment.put("GRAALVM_HOME", providers.provider { "test-graalvm" })
        }
        projectJar("agentPath", providers.provider { layout.projectDirectory.file("agent.jar") })
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      tasks.register("recordNativeInputs") {
        val out = layout.buildDirectory.file("native-inputs.txt")
        outputs.file(out)
        doLast {
          out.get().asFile.writeText(
            listOf(
              "graalvm=" + System.getenv("GRAALVM_HOME"),
              "agentPath=" + project.findProperty("agentPath"),
              "nativeEnabled=" + System.getProperty("native.enabled"),
            ).joinToString(System.lineSeparator())
          )
        }
      }
      """.trimIndent(),
    )

    val result = runner("recordNativeInputs").build()

    assertThat(result.task(":recordNativeInputs")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val inputsFile = File(projectDir.toFile(), "build/application/native-inputs.txt")
    assertThat(inputsFile).exists()
    assertThat(inputsFile.readLines()).contains(
      "graalvm=test-graalvm",
      "agentPath=${projectDir.resolve("agent.jar").toFile().canonicalPath}",
      "nativeEnabled=true",
    )
  }

  @Test
  fun `init scripts are not added outside CI`() {
    writeOuterSettings()
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("recordInitScripts")
          artifactPath.set("init-script-count.txt")
          sysProperty.set("init.script.count.path")
        }
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      tasks.register("recordInitScripts") {
        val out = layout.buildDirectory.file("init-script-count.txt")
        outputs.file(out)
        val initScriptCount = gradle.startParameter.initScripts.size
        doLast {
          out.get().asFile.writeText(initScriptCount.toString())
        }
      }
      """.trimIndent(),
    )

    val result = runner(
      "recordInitScripts",
      environment = mapOf("CI" to "false"),
    ).build()

    assertThat(result.task(":recordInitScripts")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val countFile = File(projectDir.toFile(), "build/application/init-script-count.txt")
    assertThat(countFile).exists()
    assertThat(countFile.readText()).isEqualTo("0")
  }

  @Test
  fun `init script prepends Maven proxy repositories without overriding project repositories`() {
    writeOuterSettings()
    val proxyRepository = projectDir.resolve("proxy-maven-repo").toFile()
    val projectRepository = projectDir.resolve("project-maven-repo").toFile()
    writeMavenArtifact(proxyRepository, "com.example", "shared", "1.0", "proxy")
    writeMavenArtifact(projectRepository, "com.example", "shared", "1.0", "project")
    writeMavenArtifact(projectRepository, "com.example", "project-only", "1.0", "project-only")
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("resolveRepositories")
          artifactPath.set("resolved-repositories.txt")
          sysProperty.set("resolved.repositories.path")
        }
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      repositories {
        maven {
          url = uri("${projectRepository.toURI()}")
        }
      }

      dependencies {
        implementation("com.example:shared:1.0")
        implementation("com.example:project-only:1.0")
      }

      tasks.register("resolveRepositories") {
        val resolved = layout.buildDirectory.file("resolved-repositories.txt")
        inputs.files(configurations.compileClasspath)
        outputs.file(resolved)
        doLast {
          resolved.get().asFile.writeText(
            configurations.compileClasspath.get()
              .sortedBy { it.name }
              .joinToString(System.lineSeparator()) { it.name + "=" + it.readText() }
          )
        }
      }
      """.trimIndent(),
    )

    val result = runner(
      "resolveRepositories",
      "-PmavenRepositoryProxy=${proxyRepository.toURI()}",
      environment = mapOf("CI" to "true"),
    ).build()

    assertThat(result.task(":resolveRepositories")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val resolvedFile = File(projectDir.toFile(), "build/application/resolved-repositories.txt")
    assertThat(resolvedFile).exists()
    assertThat(resolvedFile.readLines()).containsExactly(
      "project-only-1.0.jar=project-only",
      "shared-1.0.jar=proxy",
    )
  }

  @Test
  fun `init script injects Maven repository proxy into nested build`() {
    writeOuterSettings()
    val repository = projectDir.resolve("maven-repo").toFile()
    writeMavenArtifact(repository, "com.example", "demo", "1.0", "demo")
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("resolveDependency")
          artifactPath.set("resolved-dependency.txt")
          sysProperty.set("resolved.dependency.path")
        }
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      dependencies {
        implementation("com.example:demo:1.0")
      }

      tasks.register("resolveDependency") {
        val resolved = layout.buildDirectory.file("resolved-dependency.txt")
        inputs.files(configurations.compileClasspath)
        outputs.file(resolved)
        doLast {
          resolved.get().asFile.writeText(
            configurations.compileClasspath.get().singleFile.name
          )
        }
      }
      """.trimIndent(),
    )

    val result = runner(
      "resolveDependency",
      "-PmavenRepositoryProxy=${repository.toURI()}",
      environment = mapOf("CI" to "true"),
    ).build()

    assertThat(result.task(":resolveDependency")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val resolvedFile = File(projectDir.toFile(), "build/application/resolved-dependency.txt")
    assertThat(resolvedFile).exists()
    assertThat(resolvedFile.readText()).isEqualTo("demo-1.0.jar")
  }

  /**
   * `buildCacheEnabled` defaults to `false` and is plumbed through to the nested daemon as
   * an explicit `--no-build-cache` / `--build-cache` argument. The inner build records
   * `gradle.startParameter.isBuildCacheEnabled` so we can assert the value the daemon
   * actually received, regardless of any inner `gradle.properties`.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("buildCacheFlagCases")
  fun `buildCacheEnabled controls the inner --build-cache flag`(
    scenario: String,
    dslLine: String,
    expectedFlag: String,
  ) {
    writeOuterSettings()
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("recordCacheFlag")
          artifactPath.set("cache-flag.txt")
          sysProperty.set("cache.flag.path")
          $dslLine
        }
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      tasks.register("recordCacheFlag") {
        val out = layout.buildDirectory.file("cache-flag.txt")
        outputs.file(out)
        val flag = gradle.startParameter.isBuildCacheEnabled
        doLast {
          out.get().asFile.writeText(flag.toString())
        }
      }
      """.trimIndent(),
    )

    val result = runner("recordCacheFlag").build()

    assertThat(result.task(":recordCacheFlag")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val flagFile = File(projectDir.toFile(), "build/application/cache-flag.txt")
    assertThat(flagFile).exists()
    assertThat(flagFile.readText().trim()).isEqualTo(expectedFlag)
  }

  /**
   * Exercises the outer `NestedGradleBuild` `@CacheableTask` end-to-end. The `identical`
   * case verifies the task is actually cacheable (FROM_CACHE on a re-run with a wiped
   * output dir). The two `env-change` cases verify that the resolved value of an
   * `environment` Provider participates in the cache key — covering both first-class
   * `providers.gradleProperty(...)` and the closure-based `providers.provider(Callable { … })`
   * pattern used by `quarkus-native` for `GRAALVM_HOME`.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("envCacheKeyCases")
  fun `outer cache key reflects environment changes`(
    scenario: String,
    extraOuterImports: String,
    extraOuterPreamble: String,
    envDslLine: String,
    firstRunPropertyValue: String,
    secondRunPropertyValue: String,
    expectedSecondOutcome: TaskOutcome,
  ) {
    writeOuterSettings(withLocalBuildCache = true)
    outerBuild.writeText(
      """
      $extraOuterImports
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      $extraOuterPreamble

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("buildJar")
          artifactPath.set("libs/sample.jar")
          sysProperty.set("sample.path")
          $envDslLine
        }
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      tasks.register<Jar>("buildJar") {
        archiveFileName.set("sample.jar")
        from(file("src"))
      }
      """.trimIndent(),
    )
    File(applicationDir, "src").mkdir()
    File(applicationDir, "src/hello.txt").writeText("hi")

    val firstArgs = listOfNotNull(
      "buildJar",
      "--build-cache",
      firstRunPropertyValue.takeIf { it.isNotEmpty() }?.let { "-PenvValue=$it" },
    ).toTypedArray()
    val first = runner(*firstArgs).build()
    assertThat(first.task(":buildJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Wipe the output dir so the cache must serve it on the next run.
    File(projectDir.toFile(), "build/application").deleteRecursively()

    val secondArgs = listOfNotNull(
      "buildJar",
      "--build-cache",
      secondRunPropertyValue.takeIf { it.isNotEmpty() }?.let { "-PenvValue=$it" },
    ).toTypedArray()
    val second = runner(*secondArgs).build()
    assertThat(second.task(":buildJar")?.outcome).isEqualTo(expectedSecondOutcome)
  }

  private fun writeOuterSettings(withLocalBuildCache: Boolean = false) {
    val cacheBlock = if (withLocalBuildCache) {
      """
      buildCache {
        local {
          directory = file("${'$'}{rootDir}/build-cache")
        }
      }
      """.trimIndent()
    } else {
      ""
    }
    outerSettings.writeText(
      """
      rootProject.name = "smoke-test-app-fixture"
      $cacheBlock
      """.trimIndent(),
    )
  }

  private fun writeInnerSettings() {
    File(applicationDir, "settings.gradle.kts").writeText(
      """
      rootProject.name = "smoke-test-app-fixture-application"
      """.trimIndent(),
    )
  }

  private fun writeInnerBuild(taskBlock: String) {
    File(applicationDir, "build.gradle.kts").writeText(
      """
      plugins {
        java
      }
      if (hasProperty("appBuildDir")) {
        layout.buildDirectory.set(file(property("appBuildDir") as String))
      }
      $taskBlock
      """.trimIndent(),
    )
  }

  private fun writeMavenArtifact(
    repository: File,
    groupId: String,
    artifactId: String,
    version: String,
    jarContent: String,
  ) {
    val artifactDir = File(repository, "${groupId.replace('.', '/')}/$artifactId/$version")
    artifactDir.mkdirs()
    File(artifactDir, "$artifactId-$version.pom").writeText(
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>$groupId</groupId>
        <artifactId>$artifactId</artifactId>
        <version>$version</version>
      </project>
      """.trimIndent(),
    )
    File(artifactDir, "$artifactId-$version.jar").writeText(jarContent)
  }

  private fun runner(
    vararg args: String,
    environment: Map<String, String>? = null,
  ): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir.toFile())
      .withPluginClasspath()
      .withArguments(*args, "--stacktrace")
      .withEnvironment(sanitizedGradleEnvironment(environment))
      .forwardOutput()

  private fun sanitizedGradleEnvironment(
    overrides: Map<String, String>? = null,
  ): Map<String, String> =
    System.getenv() +
      mapOf(
        "GRADLE_ARGS" to "",
        "GRADLE_OPTS" to "",
        "GRADLE_USER_HOME" to outerGradleUserHome.absolutePath,
      ) +
      (overrides ?: emptyMap())

  private fun currentMajorJdk(): Int =
    System.getProperty("java.specification.version").let {
      if (it.startsWith("1.")) it.substring(2).toInt() else it.toInt()
    }

  companion object {
    private val outerGradleUserHome: File by lazy {
      Files.createTempDirectory("smoke-test-app-gradle-user-home-").toFile().also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread {
          try {
            DefaultGradleConnector.close()
          } catch (_: Exception) {
            // best effort
          }
          dir.deleteRecursively()
        })
      }
    }

    @JvmStatic
    fun buildCacheFlagCases(): List<Arguments> = listOf(
      // (scenario name, DSL line added to the `application { … }` block, expected
      // `gradle.startParameter.isBuildCacheEnabled` value seen by the nested daemon)
      Arguments.of("default off", "", "false"),
      Arguments.of("explicit true", "buildCacheEnabled.set(true)", "true"),
    )

    @JvmStatic
    fun envCacheKeyCases(): List<Arguments> = listOf(
      // (scenario, extra imports, extra preamble before smokeTestApp, env DSL line,
      // first-run -PenvValue, second-run -PenvValue, expected outcome of second run)
      Arguments.of(
        "identical inputs hit the cache",
        "",
        "",
        "",
        "",
        "",
        TaskOutcome.FROM_CACHE,
      ),
      Arguments.of(
        "gradleProperty env change misses the cache",
        "",
        "",
        """environment.put("MARKER_VAR", providers.gradleProperty("envValue"))""",
        "alpha",
        "beta",
        TaskOutcome.SUCCESS,
      ),
      Arguments.of(
        // Mirrors the `quarkus-native` wiring: an eagerly-resolved script-level value
        // flows into `providers.provider(Callable { … })` before being put into the
        // `environment` MapProperty.
        "Provider-Callable env change misses the cache",
        """
        import java.util.concurrent.Callable
        import org.gradle.api.provider.Provider
        """.trimIndent(),
        """
        val envValue: String = (project.findProperty("envValue") as String?) ?: "default"
        val markerProvider: Provider<String> = providers.provider(Callable { "resolved-${'$'}envValue" })
        """.trimIndent(),
        """environment.put("MARKER_VAR", markerProvider)""",
        "alpha",
        "beta",
        TaskOutcome.SUCCESS,
      ),
    )
  }
}
