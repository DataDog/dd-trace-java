package datadog.buildlogic.smoketest

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
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
      ) +
      (overrides ?: emptyMap())

  private fun currentMajorJdk(): Int =
    System.getProperty("java.specification.version").let {
      if (it.startsWith("1.")) it.substring(2).toInt() else it.toInt()
    }

  companion object {
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
