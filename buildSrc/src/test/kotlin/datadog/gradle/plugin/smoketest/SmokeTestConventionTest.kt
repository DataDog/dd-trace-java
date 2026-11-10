package datadog.gradle.plugin.smoketest

import datadog.gradle.plugin.GradleFixture
import datadog.gradle.plugin.smoketest.SmokeTestArtifacts.SCOPE_DIAGNOSTICS_AGENT
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class SmokeTestConventionTest : GradleFixture() {
  @BeforeEach
  fun setup() {
    writeSettings(
      """
      rootProject.name = "smoke-test-convention-test"
      include(":dd-java-agent:instrumentation-testing", ":dd-smoke-tests:child", ":unrelated")
      """
    )
    writeRootProject(
      """
      allprojects {
        version = "1.2.3"
        repositories { mavenCentral() }
      }
      """
    )
    writeFile("gradle/libs.versions.toml", File("../gradle/libs.versions.toml").readText())
    writeFile(
      "gradle/java_no_deps.gradle",
      """
      apply plugin: 'java-library'
      tasks.withType(Test).configureEach { useJUnitPlatform() }
      """
    )
    writeFile("gradle/codenarc.gradle", "")
    writeFile("dd-java-agent/build.gradle.kts", producer("tracerForSmokeTests", "jar", true))
    writeFile(
      "dd-java-agent/instrumentation-testing/build.gradle.kts",
      producer("diagnosticsForSmokeTests", SCOPE_DIAGNOSTICS_AGENT, false)
    )
    writeFile("dd-java-agent/payload/agent.classdata", "agent classes")
    writeFile("dd-java-agent/payload/dd-java-agent.version", "1.2.3~first")
    writeFile("dd-java-agent/payload/dd-trace-api.version", "1.2.3~first")
    writeFile("dd-java-agent/payload/trace/dd-trace-core.version", "1.2.3~first")
    writeFile("dd-java-agent/instrumentation-testing/payload/diagnostics.txt", "diagnostics")

    for (path in listOf("dd-smoke-tests", "dd-smoke-tests/child")) {
      writeFile(
        "$path/build.gradle.kts",
        """
        plugins { id("dd-trace-java.module.smoke-test") }
        dependencies { testImplementation(libs.junit.jupiter) }
        layout.buildDirectory.set(layout.projectDirectory.dir("relocated"))
        normalization.runtimeClasspath { ignore("**/*.version") }

        // Realise the default test before instrumentation-testing has been evaluated.
        tasks.named<Test>("test").get()
        check(!tasks.named<Javadoc>("javadoc").get().enabled)
        check(!tasks.register("forbiddenApisLater").get().enabled)
        tasks.register<Test>("lateSmokeTest") {
          testClassesDirs = sourceSets.test.get().output.classesDirs
          classpath = sourceSets.test.get().runtimeClasspath
          useJUnitPlatform()
        }
        """
      )
      writeFile(
        "$path/src/test/java/SmokeArgumentsTest.java",
        """
        import java.io.File;
        import org.junit.jupiter.api.Test;

        public class SmokeArgumentsTest {
          @Test
          void receivesSmokeArguments() {
            for (String property : new String[] {
                "datadog.smoketest.agent.shadowJar.path",
                "datadog.smoketest.scopeDiagnostics.agent.path"}) {
              if (!new File(System.getProperty(property)).isFile()) {
                throw new AssertionError("Missing agent: " + property);
              }
            }
            if (!new File(System.getProperty("datadog.smoketest.builddir")).getName().equals("relocated")) {
              throw new AssertionError("Build directory was not forwarded");
            }
            if (Long.parseLong(System.getProperty("datadog.smoketest.scopeDiagnostics.quiescenceMillis")) <= 0) {
              throw new AssertionError("Quiescence period was not forwarded");
            }
          }
        }
        """
      )
    }
    writeFile(
      "unrelated/build.gradle.kts",
      """
      plugins { java }
      check(tasks.named<Javadoc>("javadoc").get().enabled)
      check(tasks.register("forbiddenApisLater").get().enabled)
      """
    )
  }

  @Test
  fun `help does not force agent project evaluation or artifact resolution`() {
    writeFile("dd-java-agent/build.gradle.kts", "error(\"Agent project was evaluated eagerly\")")
    writeFile(
      "dd-java-agent/instrumentation-testing/build.gradle.kts",
      "error(\"Diagnostics project was evaluated eagerly\")"
    )

    val result = run(":dd-smoke-tests:help", "--configure-on-demand", "--stacktrace")

    assertThat(result.task(":dd-smoke-tests:help")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(result.tasks).noneMatch { it.path.startsWith(":dd-java-agent:") }
  }

  @Test
  fun `parent child and late tests consume agent variants with configuration cache reuse`() {
    val args = arrayOf(
      ":dd-smoke-tests:test", ":dd-smoke-tests:child:test", ":dd-smoke-tests:child:lateSmokeTest",
      "-Ddatadog.smoketest.scopeDiagnostics.quiescenceMillis=3210",
      "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace"
    )
    val first = run(*args)

    for (path in listOf(
      ":dd-java-agent:tracerForSmokeTests",
      ":dd-java-agent:instrumentation-testing:diagnosticsForSmokeTests",
      ":dd-smoke-tests:test", ":dd-smoke-tests:child:test", ":dd-smoke-tests:child:lateSmokeTest"
    )) {
      assertThat(first.task(path)?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    }
    assertThat(first.task(":dd-java-agent:jar")).isNull()
    assertThat(first.task(":dd-java-agent:instrumentation-testing:jar")).isNull()

    val second = run(*args)
    assertThat(second.output).contains("Reusing configuration cache")
    assertThat(second.task(":dd-smoke-tests:child:test")?.outcome).isEqualTo(UP_TO_DATE)
  }

  @Test
  fun `fingerprints ignore commit stamps and track resource presence classes versions and diagnostics`() {
    val args = arrayOf(":dd-smoke-tests:child:test", "--no-build-cache", "--stacktrace")
    fun testOutcome() = run(*args).let {
      assertThat(it.task(":dd-smoke-tests:child:test")).describedAs(it.output).isNotNull()
      it.task(":dd-smoke-tests:child:test")!!.outcome
    }

    assertThat(testOutcome()).isEqualTo(SUCCESS)
    assertThat(testOutcome()).isEqualTo(UP_TO_DATE)

    writeFile("dd-java-agent/payload/dd-java-agent.version", "1.2.3~second")
    assertThat(testOutcome()).isEqualTo(UP_TO_DATE)

    file("dd-java-agent/payload/dd-trace-api.version").delete()
    assertThat(testOutcome()).isEqualTo(SUCCESS)

    writeFile("dd-java-agent/payload/agent.classdata", "changed agent classes")
    assertThat(testOutcome()).isEqualTo(SUCCESS)

    writeFile("dd-java-agent/instrumentation-testing/payload/diagnostics.txt", "changed diagnostics")
    assertThat(testOutcome()).isEqualTo(SUCCESS)

    writeRootProject(
      """
      allprojects {
        version = "1.2.4"
        repositories { mavenCentral() }
      }
      """
    )
    assertThat(testOutcome()).isEqualTo(SUCCESS)

    val changedQuiescence = run(*args, "-Ddatadog.smoketest.scopeDiagnostics.quiescenceMillis=3210")
    assertThat(changedQuiescence.task(":dd-smoke-tests:child:test")?.outcome)
      .describedAs(changedQuiescence.output).isEqualTo(SUCCESS)
  }

  private fun producer(taskName: String, elements: String, shadowed: Boolean): String = """
    plugins { `java-library` }
    dependencies { implementation("missing:must-not-resolve:1") }
    val agent = tasks.register<Jar>("$taskName") {
      archiveClassifier.set("smoke-agent")
      from("payload")
    }
    configurations.consumable("smokeAgentElements") {
      extendsFrom(configurations.implementation)
      attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("$elements"))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named("${if (shadowed) "shadowed" else "external"}"))
      }
      outgoing.artifact(agent)
    }
  """
}
