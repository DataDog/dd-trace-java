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
  private val applicationBuildDir get() = projectDir.resolve("build/application").toFile()

  @BeforeEach
  fun setUp() {
    applicationDir.mkdirs()
  }

  @Test
  fun `nested build produces the configured artifact`() {
    writeOuterSettings()
    writeSmokeTestAppBuild(
      smokeTestGradleApplication(
        taskName = "buildJar",
        artifactPath = "libs/sample.jar",
        sysProperty = "sample.path",
      ),
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
    assertThat(applicationOutput("libs/sample.jar")).exists()
  }

  @Test
  fun `nested Maven build produces the configured artifact`() {
    writeOuterSettings()
    writeFakeMavenWrapper()
    writeSmokeTestAppBuild(
      smokeTestMavenApplication(
        taskName = "packageApp",
        artifactPath = "target/sample.jar",
        sysProperty = "sample.path",
        additionalConfig = """
        mavenExecutable.set(layout.projectDirectory.file("${fakeMavenWrapperName()}"))
        mavenOpts.set("-Xmx512M")
        """,
      ),
    )
    File(applicationDir, "pom.xml").writeText("<project />")

    val result = runner(
      "packageApp",
      environment = mapOf("MAVEN_REPOSITORY_PROXY" to "https://repo.example/maven2/"),
    ).build()

    assertThat(result.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("target/sample.jar")).exists()
    assertThat(applicationOutput("target/maven-env.txt").readLines()).contains(
      "MAVEN_OPTS=-Xmx512M",
      "MVNW_REPOURL=https://repo.example/maven2",
    )
  }

  @Test
  fun `nested Maven build output is restored from the outer build cache`() {
    writeOuterSettings(withLocalBuildCache = true)
    writeFakeMavenWrapper()
    writeSmokeTestAppBuild(
      smokeTestMavenApplication(
        taskName = "packageApp",
        artifactPath = "target/sample.jar",
        sysProperty = "sample.path",
        additionalConfig = """
        mavenExecutable.set(layout.projectDirectory.file("${fakeMavenWrapperName()}"))
        """,
      ),
    )
    File(applicationDir, "pom.xml").writeText("<project />")

    val first = runner("packageApp", "--build-cache").build()
    assertThat(first.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("target/sample.jar")).exists()

    applicationBuildDir.deleteRecursively()

    val second = runner("packageApp", "--build-cache").build()
    assertThat(second.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    assertThat(applicationOutput("target/sample.jar")).exists()
  }

  @Test
  fun `nested build clears inherited Gradle launcher environment`() {
    writeOuterSettings()
    val inheritedGradleUserHome = projectDir.resolve("inherited-gradle-user-home").toFile()
    inheritedGradleUserHome.mkdirs()
    writeSmokeTestAppBuild(
      smokeTestGradleApplication(
        taskName = "recordGradleEnvironment",
        artifactPath = "gradle-env.txt",
        sysProperty = "gradle.env.path",
      ),
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
    val envFile = applicationOutput("gradle-env.txt")
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
    writeSmokeTestAppBuild(
      """
      ${smokeTestGradleApplication(
        taskName = "recordNativeInputs",
        artifactPath = "native-inputs.txt",
        sysProperty = "native.inputs.path",
        additionalConfig = """
        buildArguments.add("-Dnative.enabled=true")
        environment.put("GRAALVM_HOME", providers.provider { "test-graalvm" })
        """,
      )}
      projectJar("agentPath", providers.provider { layout.projectDirectory.file("agent.jar") })
      """,
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
    val inputsFile = applicationOutput("native-inputs.txt")
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
    writeSmokeTestAppBuild(
      smokeTestGradleApplication(
        taskName = "recordInitScripts",
        artifactPath = "init-script-count.txt",
        sysProperty = "init.script.count.path",
      ),
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
    val countFile = applicationOutput("init-script-count.txt")
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
    writeSmokeTestAppBuild(
      smokeTestGradleApplication(
        taskName = "resolveRepositories",
        artifactPath = "resolved-repositories.txt",
        sysProperty = "resolved.repositories.path",
      ),
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
    val resolvedFile = applicationOutput("resolved-repositories.txt")
    assertThat(resolvedFile).exists()
    assertThat(resolvedFile.readLines()).containsExactly(
      "project-only-1.0.jar=project-only",
      "shared-1.0.jar=proxy",
    )
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
    writeSmokeTestAppBuild(
      smokeTestGradleApplication(
        taskName = "recordCacheFlag",
        artifactPath = "cache-flag.txt",
        sysProperty = "cache.flag.path",
        additionalConfig = dslLine,
      ),
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
    val flagFile = applicationOutput("cache-flag.txt")
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
    writeSmokeTestAppBuild(
      smokeTestGradleApplication(
        taskName = "buildJar",
        artifactPath = "libs/sample.jar",
        sysProperty = "sample.path",
        additionalConfig = envDslLine,
      ),
      extraImports = extraOuterImports,
      extraPreamble = extraOuterPreamble,
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
    applicationBuildDir.deleteRecursively()

    val secondArgs = listOfNotNull(
      "buildJar",
      "--build-cache",
      secondRunPropertyValue.takeIf { it.isNotEmpty() }?.let { "-PenvValue=$it" },
    ).toTypedArray()
    val second = runner(*secondArgs).build()
    assertThat(second.task(":buildJar")?.outcome).isEqualTo(expectedSecondOutcome)
    assertThat(applicationOutput("libs/sample.jar")).exists()
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

  private fun writeOuterBuild(buildScript: String) {
    outerBuild.writeText(buildScript.trimIndent())
  }

  private fun writeSmokeTestAppBuild(
    smokeTestAppBody: String,
    extraImports: String = "",
    extraPreamble: String = "",
  ) {
    val imports = extraImports.trimIndent()
    val preamble = extraPreamble.trimIndent()
    val body = smokeTestAppBody.trimIndent()
    writeOuterBuild(
      buildString {
        if (imports.isNotBlank()) {
          appendLine(imports)
          appendLine()
        }
        appendLine("plugins {")
        appendLine("  java")
        appendLine("  id(\"dd-trace-java.smoke-test-app\")")
        appendLine("}")
        appendLine()
        if (preamble.isNotBlank()) {
          appendLine(preamble)
          appendLine()
        }
        appendLine("smokeTestApp {")
        appendLine("  javaLauncher.set(")
        appendLine("    javaToolchains.launcherFor {")
        appendLine("      languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()}))")
        appendLine("    }")
        appendLine("  )")
        if (body.isNotBlank()) {
          appendLine(body.prependIndent("  "))
        }
        appendLine("}")
      },
    )
  }

  private fun smokeTestGradleApplication(
    taskName: String,
    artifactPath: String,
    sysProperty: String,
    additionalConfig: String = "",
  ): String {
    val config = additionalConfig.trimIndent()
    return listOfNotNull(
      """
      gradleApp {
        taskName.set("$taskName")
        artifactPath.set("$artifactPath")
        sysProperty.set("$sysProperty")
      """.trimIndent(),
      config.takeIf { it.isNotBlank() }?.prependIndent("  "),
      "}",
    ).joinToString(System.lineSeparator())
  }

  private fun smokeTestMavenApplication(
    taskName: String,
    artifactPath: String,
    sysProperty: String,
    additionalConfig: String = "",
  ): String {
    val config = additionalConfig.trimIndent()
    return listOfNotNull(
      """
      mavenApp {
        taskName.set("$taskName")
        artifactPath.set("$artifactPath")
        sysProperty.set("$sysProperty")
      """.trimIndent(),
      config.takeIf { it.isNotBlank() }?.prependIndent("  "),
      "}",
    ).joinToString(System.lineSeparator())
  }

  private fun writeFakeMavenWrapper() {
    val wrapper = File(projectDir.toFile(), fakeMavenWrapperName())
    val resource =
      requireNotNull(javaClass.getResource(fakeMavenWrapperName())) {
        "Missing fake Maven wrapper test resource"
      }
    wrapper.writeBytes(resource.readBytes())
    if (!isWindows()) {
      wrapper.setExecutable(true)
    }
  }

  private fun fakeMavenWrapperName(): String =
    if (isWindows()) {
      "fake-mvnw.cmd"
    } else {
      "fake-mvnw"
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

  private fun applicationOutput(relativePath: String): File =
    applicationBuildDir.resolve(relativePath)

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
      // (scenario name, DSL line added to the `gradleApp { … }` block, expected
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
