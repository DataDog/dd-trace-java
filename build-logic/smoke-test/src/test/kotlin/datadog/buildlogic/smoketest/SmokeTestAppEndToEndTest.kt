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
import org.tabletest.junit.TableTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

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
    // given
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

    // when
    val result = runner("buildJar").build()

    // then
    assertThat(result.task(":buildJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("libs/sample.jar")).exists()
  }

  @TableTest(
    """
    scenario                                    | configuration | change          | expected
    normalised version stamp ignored            | default       | stamp           | FROM_CACHE
    ZIP timestamps ignored                      | default       | zip             | FROM_CACHE
    ZIP entry order ignored                     | default       | order           | FROM_CACHE
    project version tracked                     | default       | version         | SUCCESS
    class contents tracked                      | default       | class           | SUCCESS
    classdata tracked                           | default       | classdata       | SUCCESS
    other resources tracked                     | default       | resource        | SUCCESS
    YAML resource contents tracked              | default       | yaml            | SUCCESS
    agent version added                         | default       | added           | SUCCESS
    agent version removed                       | default       | removed         | SUCCESS
    normalised version resources ignored        | default       | normalized-path | FROM_CACHE
    shadow normalised version stamp ignored     | shadow        | stamp           | FROM_CACHE
    """
  )
  fun `project JAR fingerprints ignore metadata and track source changes`(
    configuration: String,
    change: String,
    expected: TaskOutcome,
  ) {
    // given
    writeOuterSettings(withLocalBuildCache = true)
    outerSettings.appendText("\ninclude(\":api\")\n")
    val apiDirectory = projectDir.resolve("api").toFile().apply { mkdirs() }
    File(apiDirectory, "build.gradle.kts").writeText(
      """
      plugins { base }
      version = providers.gradleProperty("apiVersion").getOrElse("1.0")
      val prepareJar = tasks.register<Copy>("prepareJar") {
        from("input.jar")
        into(layout.buildDirectory.dir("libs"))
      }
      val outgoing = configurations.${if (configuration == "default") "named(\"default\")" else "create(\"shadow\")"} {
        isCanBeConsumed = true
        isCanBeResolved = false
      }
      artifacts.add(outgoing.name, layout.buildDirectory.file("libs/input.jar")) {
        builtBy(prepareJar)
      }
      """.trimIndent(),
    )
    writeSmokeTestAppBuild(
      """
      ${smokeTestGradleApplication("packageApp", "embedded-api.jar", "api.path")}
      projectJar("apiJar", project(":api")${if (configuration == "default") "" else ", \"shadow\""})
      """,
      extraImports = "import datadog.buildlogic.smoketest.NestedGradleBuild",
      extraPreamble = """
      normalization.runtimeClasspath.ignore("**/*.version")
      tasks.withType<NestedGradleBuild>().configureEach {
        actions.clear()
        val inputJar = projectJars.flatMap { it.single().file }
        val target = applicationBuildDir.file("embedded-api.jar")
        doLast {
          target.get().asFile.apply {
            parentFile.mkdirs()
            inputJar.get().asFile.copyTo(this, overwrite = true)
          }
        }
      }
      """,
    )

    val entries = linkedMapOf(
      "api.version" to "1.0~initial",
      "example/Api.class" to "class",
      "example/Agent.classdata" to "classdata",
      "config.txt" to "resource",
      "config.yaml" to "enabled: false\n",
    )
    if (change == "removed") {
      entries["dd-java-agent.version"] = "1.0~initial"
    }

    fun writeApiJar(timestamp: Long = 1_000_000_000_000L, reverseEntries: Boolean = false) {
      JarOutputStream(File(apiDirectory, "input.jar").outputStream()).use { jar ->
        val orderedEntries = if (reverseEntries) entries.entries.reversed() else entries.entries
        orderedEntries.forEach { (name, contents) ->
          jar.putNextEntry(JarEntry(name).apply {
            time = timestamp
          })
          jar.write(contents.toByteArray(Charsets.UTF_8))
          jar.closeEntry()
        }
      }
    }

    writeApiJar()

    // when
    // Re-snapshot the JAR rewritten outside Gradle between runs.
    val first = runner("packageApp", "--build-cache", "--configuration-cache", "--no-watch-fs", "-PapiVersion=1.0").build()

    // then
    assertThat(first.task(":api:prepareJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(first.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // when
    applicationBuildDir.deleteRecursively()
    var timestamp = 1_000_000_000_000L
    var reverseEntries = false
    var apiVersion = "1.0"
    when (change) {
      "stamp" -> entries["api.version"] = "1.0~next"
      "class" -> entries["example/Api.class"] = "changed class"
      "classdata" -> entries["example/Agent.classdata"] = "changed classdata"
      "resource" -> entries["config.txt"] = "changed resource"
      "yaml" -> entries["config.yaml"] = "enabled: true\n"
      "added" -> entries["dd-java-agent.version"] = "1.0~initial"
      "removed" -> entries.remove("dd-java-agent.version")
      "normalized-path" -> entries["optional.version"] = "1.0~initial"
      "zip" -> timestamp = 1_700_000_000_000L
      "order" -> reverseEntries = true
      "version" -> apiVersion = "2.0"
      else -> error("Unknown change: $change")
    }
    writeApiJar(timestamp, reverseEntries)
    val second = runner(
      "packageApp", "--build-cache", "--configuration-cache", "--no-watch-fs",
      "-PapiVersion=$apiVersion",
    ).build()

    // then
    if (change != "version") {
      assertThat(second.task(":api:prepareJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
    assertThat(second.task(":packageApp")?.outcome).isEqualTo(expected)
    if (change != "version") {
      assertThat(second.output).contains("Reusing configuration cache.")
    }
    JarFile(applicationOutput("embedded-api.jar")).use { jar ->
      val stamp = jar.getInputStream(jar.getJarEntry("api.version")).bufferedReader().use { it.readText() }
      assertThat(stamp).isEqualTo("1.0~initial")
      assertThat(jar.getJarEntry("dd-java-agent.version") != null).isEqualTo(change == "added")
      assertThat(jar.getJarEntry("optional.version")).isNull()
      val yaml = jar.getInputStream(jar.getJarEntry("config.yaml")).bufferedReader().use { it.readText() }
      assertThat(yaml).isEqualTo(if (change == "yaml") "enabled: true\n" else "enabled: false\n")
    }
  }

  @Test
  fun `nested Maven build produces the configured artifact`() {
    // given
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

    // when
    val result = runner(
      "packageApp",
      environment = mapOf("MAVEN_REPOSITORY_PROXY" to "https://repo.example/maven2/"),
    ).build()

    // then
    assertThat(result.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("target/sample.jar")).exists()
    assertThat(applicationOutput("target/maven-env.txt").readLines()).contains(
      "MAVEN_OPTS=-Xmx512M",
      "MVNW_REPOURL=https://repo.example/maven2",
    )
  }

  @Test
  fun `nested Maven build output is restored from the outer build cache`() {
    // given
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

    // when
    val first = runner("packageApp", "--build-cache").build()

    // then
    assertThat(first.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("target/sample.jar")).exists()

    // when
    applicationBuildDir.deleteRecursively()

    val second = runner("packageApp", "--build-cache").build()

    // then
    assertThat(second.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    assertThat(applicationOutput("target/sample.jar")).exists()
  }

  @TableTest(
    "scenario        | input          ",
    "Gradle artifact | Gradle artifact",
    "Maven artifact  | Maven artifact ",
    "extra file      | extra file     ",
    "extra directory | extra directory",
  )
  fun `tests track nested application contents`(input: String) {
    assertTestsTrackApplicationContents(input)
  }

  @Test
  fun `tests track explicitly declared native toolchain inputs`() {
    assertTestsTrackApplicationContents("GraalVM release")
  }

  private fun assertTestsTrackApplicationContents(scenario: String) {
    // given
    val maven = scenario == "Maven artifact"
    val graalvm = scenario == "GraalVM release"
    val extra = scenario.startsWith("extra")
    val directory = scenario == "extra directory"
    val artifactPath = if (maven) "target/sample.jar" else "sample.jar"
    val extraPath = if (directory) "extra" else "extra.txt"
    val payloadPath =
      if (extra) {
        if (directory) "$extraPath/value.txt" else extraPath
      } else {
        "sample.jar"
      }

    writeOuterSettings(withLocalBuildCache = true)
    val application =
      if (maven) {
        writeFakeMavenWrapper()
        File(applicationDir, "pom.xml").writeText("<project />")
        smokeTestMavenApplication(
          taskName = "packageApp",
          artifactPath = artifactPath,
          sysProperty = "sample.path",
          additionalConfig = "mavenExecutable.set(layout.projectDirectory.file(\"${fakeMavenWrapperName()}\"))",
        )
      } else {
        writeInnerSettings()
        writeInnerBuild(
          if (graalvm) {
            """
            tasks.register<Copy>("packageApp") {
              from(file(System.getenv("GRAALVM_HOME")).resolve("release"))
              rename { "sample.jar" }
              into(layout.buildDirectory)
            }
            """.trimIndent()
          } else {
            """
            tasks.register<Copy>("packageApp") {
              from(layout.projectDirectory.dir("payload"))
              into(layout.buildDirectory)
            }
            """.trimIndent()
          },
        )
        if (graalvm) {
          File(projectDir.toFile(), "graalvm/release").apply {
            parentFile.mkdirs()
            writeText("jar")
          }
        }
        File(applicationDir, "payload").mkdirs()
        File(applicationDir, "payload/sample.jar").writeText("jar")
        File(applicationDir, "payload/$payloadPath").apply {
          parentFile.mkdirs()
          writeText("jar")
        }
        smokeTestGradleApplication(
          taskName = "packageApp",
          artifactPath = artifactPath,
          sysProperty = "sample.path",
          additionalConfig =
            when {
              graalvm -> {
                """
                environment.put("GRAALVM_HOME", layout.projectDirectory.dir("graalvm").asFile.absolutePath)
                inputs {
                  property("graalvmRelease", providers.fileContents(layout.projectDirectory.file("graalvm/release")).asText)
                }
                """.trimIndent()
              }

              extra -> {
                "additionalSystemProperties.put(\"sample.extra\", \"$extraPath\")"
              }

              else -> {
                ""
              }
            },
        )
      }
    writeSmokeTestAppBuild(
      application,
      extraPreamble = """
      dependencies { testImplementation(files(${junitClasspath()})) }
      tasks.test { useJUnitPlatform() }
      """,
    )
    val property = if (extra) "sample.extra" else "sample.path"
    val relativeFile = if (directory) ", \"value.txt\"" else ""
    File(projectDir.toFile(), "src/test/java/SmokeArtifactTest.java").apply {
      parentFile.mkdirs()
      writeText(
        """
        import java.nio.file.Files;
        import java.nio.file.Path;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;

        public class SmokeArtifactTest {
          @Test void applicationWorks() throws Exception {
            Path artifact = Path.of(System.getProperty("$property")$relativeFile);
            assertEquals("jar", Files.readString(artifact).trim(), "smoke artifact");
          }
        }
        """.trimIndent(),
      )
    }

    // when
    val first = runner("test", "--build-cache").build()

    // then
    assertThat(first.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(first.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // when
    val unchanged = runner("test", "--build-cache").build()

    // then
    assertThat(unchanged.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(unchanged.task(":test")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

    // when
    File(projectDir.toFile(), "build/test-results").deleteRecursively()
    File(projectDir.toFile(), "build/reports/tests").deleteRecursively()
    if (graalvm) {
      applicationBuildDir.deleteRecursively()
    }
    val restored = runner("test", "--build-cache").build()

    // then
    if (graalvm) {
      assertThat(restored.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    }
    assertThat(restored.task(":test")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)

    // when
    if (maven) {
      File(applicationDir, "pom.xml").appendText("\n")
    } else {
      File(applicationDir, "payload/unrelated.txt").writeText("unrelated")
    }
    val unchangedArtifact = runner("test", "--build-cache").build()

    // then
    assertThat(unchangedArtifact.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(unchangedArtifact.task(":test")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

    // when
    if (graalvm) {
      File(projectDir.toFile(), "graalvm/release").writeText("broken")
    } else if (maven) {
      val original = if (isWindows()) "echo jar>" else "printf \"jar\""
      val wrapper = File(projectDir.toFile(), fakeMavenWrapperName())
      wrapper.writeText(wrapper.readText().replace(original, original.replace("jar", "broken")))
    } else {
      File(applicationDir, "payload/$payloadPath").writeText("broken")
    }
    val changedArtifact = runner("test", "--build-cache").buildAndFail()

    // then
    assertThat(changedArtifact.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(changedArtifact.task(":test")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(File(projectDir.toFile(), "build/test-results/test/TEST-SmokeArtifactTest.xml"))
      .content()
      .contains("smoke artifact", "broken")
  }

  @Test
  fun `nested build clears inherited Gradle launcher environment`() {
    // given
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
        val shutdownMarker = layout.buildDirectory.file("daemon-stopped.txt").get().asFile
        val userHome = gradle.gradleUserHomeDir
        outputs.file(out)
        doLast {
          Runtime.getRuntime().addShutdownHook(Thread {
            // Always recreate gradle home
            Thread.sleep(2500)
            userHome.mkdirs()
            userHome.resolve("late-daemon-write.txt").writeText("stopping")
            shutdownMarker.writeText("stopped")
          })
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

    // when
    val result = runner(
      "recordGradleEnvironment",
      environment = mapOf(
        "GRADLE_ARGS" to "--info",
        "GRADLE_OPTS" to "-Ddd.test.gradle.opts=inherited",
        "GRADLE_USER_HOME" to inheritedGradleUserHome.absolutePath,
      ),
    ).build()

    // then
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
    assertThat(applicationOutput("daemon-stopped.txt")).exists()
    assertThat(File(gradleUserHomeDir)).doesNotExist()

    // when
    assertThat(applicationOutput("daemon-stopped.txt").delete()).isTrue()
    assertThat(envFile.delete()).isTrue()
    val second = runner("recordGradleEnvironment", "--rerun-tasks", "--no-build-cache").build()

    // then
    assertThat(second.task(":recordGradleEnvironment")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("daemon-stopped.txt")).exists()
    assertThat(File(gradleUserHomeDir)).doesNotExist()
  }

  @Test
  fun `nested build receives native app environment and provider backed file inputs`() {
    // given
    writeOuterSettings()
    val graalvmHome = projectDir.resolve("test-graalvm").toFile()
    JarOutputStream(File(projectDir.toFile(), "agent.jar").outputStream()).use { }
    writeSmokeTestAppBuild(
      """
      ${smokeTestGradleApplication(
        taskName = "recordNativeInputs",
        artifactPath = "native-inputs.txt",
        sysProperty = "native.inputs.path",
        additionalConfig = """
        buildArguments.add("-Dnative.enabled=true")
        environment.put("GRAALVM_HOME", providers.provider { layout.projectDirectory.dir("test-graalvm").asFile.absolutePath })
        """,
      )}
      projectJar(
        "agentPath",
        providers.provider { layout.projectDirectory.file("agent.jar") },
        providers.provider { "1.0" },
      )
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

    // when
    val result = runner("recordNativeInputs").build()

    // then
    assertThat(result.task(":recordNativeInputs")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val inputsFile = applicationOutput("native-inputs.txt")
    assertThat(inputsFile).exists()
    assertThat(inputsFile.readLines()).contains(
      "graalvm=${graalvmHome.canonicalPath}",
      "agentPath=${projectDir.resolve("agent.jar").toFile().canonicalPath}",
      "nativeEnabled=true",
    )
  }

  @Test
  fun `application inputs are only queried for selected builds that run`() {
    // given
    writeOuterSettings()
    writeSmokeTestAppBuild(
      smokeTestGradleApplication(
        taskName = "nativeBuild",
        artifactPath = "native-app",
        sysProperty = "native.path",
        additionalConfig = """
        inputs {
          property("externalTool", fileContent(providers.provider<String> { error("Tool metadata queried") }, "release"))
        }
        """,
      ),
      extraImports = "import datadog.buildlogic.smoketest.NestedGradleBuild",
      extraPreamble = """
      tasks.withType<NestedGradleBuild>().configureEach { onlyIf { false } }
      """,
    )

    // when
    val unselected = runner("help", "--configuration-cache").build()

    // then
    assertThat(unselected.task(":nativeBuild")).isNull()

    // when
    val skippedWithFailingProvider = runner("nativeBuild").build()

    // then
    assertThat(skippedWithFailingProvider.task(":nativeBuild")?.outcome)
      .isEqualTo(TaskOutcome.SKIPPED)

    // when
    outerBuild.writeText(
      outerBuild
        .readText()
        .replace("providers.provider<String> { error(\"Tool metadata queried\") }", "providers.gradleProperty(\"missingRequired\")"),
    )
    val skipped = runner("nativeBuild", "--configuration-cache").build()

    // then
    assertThat(skipped.task(":nativeBuild")?.outcome).isEqualTo(TaskOutcome.SKIPPED)

    // when
    outerBuild.writeText(outerBuild.readText().replace("onlyIf { false }", "onlyIf { true }"))
    val missingInput = runner("nativeBuild").buildAndFail()

    // then
    assertThat(missingInput.output).contains("externalTool", "value")
  }

  @TableTest(
    "scenario      | maven | groovy",
    "Gradle Kotlin | false | false ",
    "Maven Kotlin  | true  | false ",
    "Gradle Groovy | false | true  ",
    "Maven Groovy  | true  | true  ",
  )
  fun `explicit application inputs participate in caching`(
    maven: Boolean,
    groovy: Boolean,
  ) {
    // given
    val application = if (maven) "mavenApp" else "gradleApp"
    val taskType = if (maven) "NestedMavenBuild" else "NestedGradleBuild"
    writeOuterSettings(withLocalBuildCache = true)
    val release = projectDir.resolve("toolchain/release").toFile()
    release.parentFile.mkdirs()
    release.writeText("first")
    if (maven) {
      writeFakeMavenWrapper()
    }
    if (groovy) {
      File(projectDir.toFile(), "build.gradle").writeText(
        """
        import datadog.buildlogic.smoketest.$taskType

        plugins {
          id 'java'
          id 'dd-trace-java.smoke-test-app'
        }

        def metadata = providers.fileContents(layout.projectDirectory.file('toolchain/release')).asText
        def home = providers.provider { 'toolchain' }
        smokeTestApp {
          $application {
            taskName = 'packageApp'
            artifactPath = 'toolchain.txt'
            sysProperty = 'toolchain.path'
            ${if (maven) "mavenExecutable.set(layout.projectDirectory.file('${fakeMavenWrapperName()}'))" else ""}
            inputs {
              property('toolchainRelease', fileContent(home, 'release'))
              property('optionalIdentity', providers.gradleProperty('optionalIdentity')).optional(true)
              property('optionalFileContent', fileContent(providers.gradleProperty('missingHome'), 'release')).optional(true)
            }
            inputs { property('anotherInput', 'fixed') }
          }
        }

        tasks.named('packageApp', $taskType) {
          actions.clear()
          def target = applicationBuildDir.file('toolchain.txt')
          doLast {
            def output = target.get().asFile
            output.parentFile.mkdirs()
            output.setText(metadata.get(), 'UTF-8')
          }
        }
        """.trimIndent(),
      )
    } else {
      val config =
        """
        ${if (maven) "mavenExecutable.set(layout.projectDirectory.file(\"${fakeMavenWrapperName()}\"))" else ""}
        inputs {
          property("toolchainRelease", fileContent(home, "release"))
          property("optionalIdentity", providers.gradleProperty("optionalIdentity")).optional(true)
          property("optionalFileContent", fileContent(providers.gradleProperty("missingHome"), "release")).optional(true)
        }
        inputs { property("anotherInput", "fixed") }
        """.trimIndent()
      writeSmokeTestAppBuild(
        if (maven) {
          smokeTestMavenApplication("packageApp", "toolchain.txt", "toolchain.path", config)
        } else {
          smokeTestGradleApplication("packageApp", "toolchain.txt", "toolchain.path", config)
        },
        extraImports = "import datadog.buildlogic.smoketest.$taskType",
        extraPreamble = """
        val metadata = providers.fileContents(layout.projectDirectory.file("toolchain/release")).asText
        val home = providers.provider { "${release.parentFile.invariantSeparatorsPath}" }
        tasks.withType<$taskType>().configureEach {
          actions.clear()
          val metadataInput = metadata
          val target = applicationBuildDir.file("toolchain.txt")
          doLast {
            target.get().asFile.apply {
              parentFile.mkdirs()
              writeText(metadataInput.get())
            }
          }
        }
        """,
      )
    }

    // when
    val first = runner("packageApp", "--build-cache", "--configuration-cache").build()

    // then
    assertThat(first.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("toolchain.txt")).content().isEqualTo("first")

    // when
    val unchanged = runner("packageApp", "--build-cache", "--configuration-cache").build()

    // then
    assertThat(unchanged.output).contains("Reusing configuration cache")
    assertThat(unchanged.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

    // when
    applicationBuildDir.deleteRecursively()
    val restored = runner("packageApp", "--build-cache", "--configuration-cache").build()

    // then
    assertThat(restored.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    assertThat(applicationOutput("toolchain.txt")).content().isEqualTo("first")

    // when
    release.writeText("second")
    val changed = runner("packageApp", "--build-cache", "--configuration-cache").build()

    // then
    assertThat(changed.task(":packageApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("toolchain.txt")).content().isEqualTo("second")

    // when
    release.delete()
    val missing = runner("packageApp", "--build-cache", "--configuration-cache").buildAndFail()

    // then
    assertThat(missing.output).contains("toolchainRelease", "value")
  }

  @Test
  fun `init scripts are not added outside CI`() {
    // given
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

    // when
    val result = runner(
      "recordInitScripts",
      environment = mapOf("CI" to "false"),
    ).build()

    // then
    assertThat(result.task(":recordInitScripts")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val countFile = applicationOutput("init-script-count.txt")
    assertThat(countFile).exists()
    assertThat(countFile.readText()).isEqualTo("0")
  }

  @Test
  fun `Maven proxy replaces Maven Central in nested repositories`() {
    // given
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
    writeInnerSettings(
      """
      pluginManagement {
        repositories {
          mavenCentral()
        }
      }

      gradle.settingsEvaluated {
        val outputDir = java.io.File(providers.gradleProperty("appBuildDir").get())
        outputDir.mkdirs()
        outputDir.resolve("plugin-repositories.txt").writeText(
          pluginManagement.repositories
            .withType(org.gradle.api.artifacts.repositories.MavenArtifactRepository::class.java)
            .joinToString(System.lineSeparator()) { "repository=" + it.url }
        )
      }

      gradle.projectsLoaded {
        gradle.rootProject.repositories.mavenCentral {
          content {
            includeGroup("com.unrelated")
          }
        }
      }
      """.trimIndent(),
    )
    writeInnerBuild(
      """
      repositories {
        mavenCentral()
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
          val artifacts = configurations.compileClasspath.get()
            .sortedBy { it.name }
            .map { it.name + "=" + it.readText() }
          val repositoryUrls = repositories
            .withType(org.gradle.api.artifacts.repositories.MavenArtifactRepository::class.java)
            .map { "repository=" + it.url }
          resolved.get().asFile.writeText(
            (listOf("init-script-count=" + gradle.startParameter.initScripts.size) +
              repositoryUrls + artifacts)
              .joinToString(System.lineSeparator())
          )
        }
      }
      """.trimIndent(),
    )

    // when
    val result = runner(
      "resolveRepositories",
      "-PmavenRepositoryProxy=${proxyRepository.toURI()}",
      environment = mapOf("CI" to "false"),
    ).build()

    // then
    assertThat(result.task(":resolveRepositories")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val resolvedFile = applicationOutput("resolved-repositories.txt")
    assertThat(resolvedFile).exists()
    val resolvedLines = resolvedFile.readLines()
    assertThat(resolvedLines).contains(
      "init-script-count=1",
      "repository=${proxyRepository.toURI()}",
      "project-only-1.0.jar=project-only",
      "shared-1.0.jar=proxy",
    )
    assertThat(resolvedLines).containsOnlyOnce(
      "repository=${proxyRepository.toURI()}",
    )
    assertThat(resolvedLines).doesNotContain(
      "repository=https://repo.maven.apache.org/maven2/",
    )
    val pluginRepositoriesFile = applicationOutput("plugin-repositories.txt")
    assertThat(pluginRepositoriesFile).exists()
    val pluginRepositoryLines = pluginRepositoriesFile.readLines()
    assertThat(pluginRepositoryLines).contains(
      "repository=${proxyRepository.toURI()}",
    )
    assertThat(pluginRepositoryLines).containsOnlyOnce(
      "repository=${proxyRepository.toURI()}",
    )
    assertThat(pluginRepositoryLines).doesNotContain(
      "repository=https://repo.maven.apache.org/maven2/",
    )
  }

  @Test
  fun `proxy declared in gradleProperties also injects repository init script`() {
    // given
    writeOuterSettings()
    writeSmokeTestAppBuild(
      smokeTestGradleApplication(
        taskName = "recordProxy",
        artifactPath = "proxy.txt",
        sysProperty = "proxy.path",
      ),
      extraImports = "import datadog.buildlogic.smoketest.NestedGradleBuild",
      extraPreamble = """
      tasks.withType<NestedGradleBuild>().configureEach {
        mavenRepositoryProxy.set("")
        gradleProperties.put("mavenRepositoryProxy", "https://declared.example")
      }
      """,
    )
    writeInnerSettings()
    writeInnerBuild(recordProxyTask())

    // when
    val result = runner(
      "recordProxy",
      environment = mapOf("CI" to "false"),
    ).build()

    // then
    assertThat(result.task(":recordProxy")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("proxy.txt").readLines()).containsExactly(
      "proxy=https://declared.example",
      "init-script-count=1",
    )
  }

  @Test
  fun `explicit task proxy overrides outer proxy on CI`() {
    // given
    writeOuterSettings()
    writeSmokeTestAppBuild(
      smokeTestGradleApplication(
        taskName = "recordProxy",
        artifactPath = "proxy.txt",
        sysProperty = "proxy.path",
      ),
      extraImports = "import datadog.buildlogic.smoketest.NestedGradleBuild",
      extraPreamble = """
      tasks.withType<NestedGradleBuild>().configureEach {
        mavenRepositoryProxy.set("https://task.example")
      }
      """,
    )
    writeInnerSettings()
    writeInnerBuild(recordProxyTask())

    // when
    val result = runner(
      "recordProxy",
      "-PmavenRepositoryProxy=https://outer.example",
      environment = mapOf("CI" to "true"),
    ).build()

    // then
    assertThat(result.task(":recordProxy")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("proxy.txt").readLines()).containsExactly(
      "proxy=https://task.example",
      "init-script-count=1",
    )
  }

  @Test
  fun `outer cache key reflects repository proxy changes`() {
    // given
    writeOuterSettings(withLocalBuildCache = true)
    writeSmokeTestAppBuild(
      smokeTestGradleApplication(
        taskName = "recordProxy",
        artifactPath = "proxy.txt",
        sysProperty = "proxy.path",
      ),
    )
    writeInnerSettings()
    writeInnerBuild(recordProxyTask())

    // when
    val first = runner(
      "recordProxy",
      "--build-cache",
      "-PmavenRepositoryProxy=https://first.example",
    ).build()

    // then
    assertThat(first.task(":recordProxy")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // when
    applicationBuildDir.deleteRecursively()

    val second = runner(
      "recordProxy",
      "--build-cache",
      "-PmavenRepositoryProxy=https://second.example",
    ).build()

    // then
    assertThat(second.task(":recordProxy")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(applicationOutput("proxy.txt").readLines()).containsExactly(
      "proxy=https://second.example",
      "init-script-count=1",
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
    // given
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

    // when
    val result = runner("recordCacheFlag").build()

    // then
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
    // given
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

    // when
    val firstArgs = listOfNotNull(
      "buildJar",
      "--build-cache",
      firstRunPropertyValue.takeIf { it.isNotEmpty() }?.let { "-PenvValue=$it" },
    ).toTypedArray()
    val first = runner(*firstArgs).build()

    // then
    assertThat(first.task(":buildJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // when
    // Wipe the output dir so the cache must serve it on the next run.
    applicationBuildDir.deleteRecursively()

    val secondArgs = listOfNotNull(
      "buildJar",
      "--build-cache",
      secondRunPropertyValue.takeIf { it.isNotEmpty() }?.let { "-PenvValue=$it" },
    ).toTypedArray()
    val second = runner(*secondArgs).build()

    // then
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

  private fun junitClasspath() =
    listOf(
      "org.junit.jupiter.api.Test",
      "org.junit.jupiter.engine.JupiterTestEngine",
      "org.junit.platform.engine.TestEngine",
      "org.junit.platform.launcher.Launcher",
      "org.junit.platform.commons.JUnitException",
      "org.opentest4j.AssertionFailedError",
    ).joinToString(", ") {
      "\"${Path.of(
        Class
          .forName(it)
          .protectionDomain.codeSource.location
          .toURI(),
      ).toFile().invariantSeparatorsPath}\""
    }

  private fun writeInnerSettings(additionalContent: String = "") {
    File(applicationDir, "settings.gradle.kts").writeText(
      """
      rootProject.name = "smoke-test-app-fixture-application"
      $additionalContent
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

  private fun recordProxyTask(): String =
    """
    tasks.register("recordProxy") {
      val out = layout.buildDirectory.file("proxy.txt")
      outputs.file(out)
      doLast {
        out.get().asFile.writeText(
          listOf(
            "proxy=" + project.findProperty("mavenRepositoryProxy"),
            "init-script-count=" + gradle.startParameter.initScripts.size,
          ).joinToString(System.lineSeparator())
        )
      }
    }
    """.trimIndent()

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

  // Proxy scenarios must not inherit repository settings exported by the CI job.
  private fun sanitizedGradleEnvironment(
    overrides: Map<String, String>? = null,
  ): Map<String, String> =
    (System.getenv() - setOf(
      "MAVEN_REPOSITORY_PROXY",
      "MVNW_REPOURL",
      "ORG_GRADLE_PROJECT_mavenRepositoryProxy",
      "ORG_GRADLE_PROJECT_gradlePluginProxy",
    )) +
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
