package datadog.gradle.plugin.testing

import datadog.gradle.plugin.GradleFixture
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CheckTestAgentResultsActionTest : GradleFixture() {
  @Test
  fun `failed trace checks are not cached and successful checks allow reuse`() {
    withAgent { server, failing, checks ->
      writeProject(server)
      val inactive = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment + ("CI_USE_TEST_AGENT" to "false"))
      assertThat(inactive.task(":test")?.outcome).describedAs(inactive.output).isEqualTo(SUCCESS)
      assertThat(checks.get()).isZero()
      failing.set(true)

      repeat(2) {
        val failed = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment, expectFailure = true)
        assertThat(failed.task(":test")?.outcome).describedAs(failed.output).isEqualTo(FAILED)
        assertThat(failed.output).contains("APM Test Agent Check Traces failed")
        if (it == 1) {
          assertThat(failed.task(":compileJava")?.outcome).isEqualTo(FROM_CACHE)
          assertThat(failed.output).contains("Reusing configuration cache")
        }
      }
      assertThat(checks.get()).isEqualTo(2)

      failing.set(false)
      val passed = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(passed.task(":test")?.outcome).describedAs(passed.output).isEqualTo(SUCCESS)
      assertThat(checks.get()).isEqualTo(3)
      val summary = file("build/reports/test-agent/test/summary_response.txt").readText()
      val failures = file("build/reports/test-agent/test/response.txt").readText()

      val restored = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(restored.task(":test")?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)
      assertThat(restored.task(":compileJava")?.outcome).isEqualTo(FROM_CACHE)
      assertThat(restored.output).contains("Reusing configuration cache")
      assertThat(checks.get()).isEqualTo(3)
      assertThat(file("build/reports/test-agent/test/summary_response.txt")).hasContent(summary)
      assertThat(file("build/reports/test-agent/test/response.txt")).hasContent(failures)
    }
  }

  @Test
  fun `parallel test tasks use separate response directories`() {
    withAgent { server, _, checks ->
      writeProject(server)
      addSubproject("second", testBuildScript)
      writeJavaSource(
        "SecondTest",
        """
        public class SecondTest {
          @org.junit.jupiter.api.Test
          public void passes() {}
        }
        """,
        sourceSet = "test",
        projectPath = ":second",
      )

      val result = run("test", "--parallel", "--max-workers=2", "--build-cache", env = agentEnvironment)
      assertThat(result.task(":test")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
      assertThat(result.task(":second:test")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
      assertThat(checks.get()).isEqualTo(2)
      listOf("build/reports/test-agent/test", "second/build/reports/test-agent/test").forEach { directory ->
        assertThat(file("$directory/summary_response.txt")).exists()
        assertThat(file("$directory/response.txt")).exists()
      }
      assertThat(file("summary_response.txt")).doesNotExist()
      assertThat(file("response.txt")).doesNotExist()
    }
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "CI_TEST_AGENT_IMAGE",
      "CI_TEST_AGENT_ENABLED_CHECKS",
      "CI_TEST_AGENT_SUPPRESS_TRACE_PARSE_ERRORS",
      "CI_TEST_AGENT_POOL_TRACE_CHECK_FAILURES",
      "CI_TEST_AGENT_DISABLE_ERROR_RESPONSES",
    ]
  )
  fun `sidecar configuration changes invalidate results with configuration cache reuse`(variable: String) {
    withAgent { server, _, checks ->
      writeProject(server)
      val first = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      val value = if (variable == "CI_TEST_AGENT_IMAGE") "test-agent@sha256:${"2".repeat(64)}" else ""
      val changedEnvironment = agentEnvironment + (variable to value)

      val changed = run("clean", "test", "--build-cache", "--configuration-cache", env = changedEnvironment)
      assertThat(changed.task(":test")?.outcome).describedAs(changed.output).isEqualTo(SUCCESS)
      assertThat(changed.output).contains("Reusing configuration cache")
      assertThat(checks.get()).isEqualTo(2)

      val restored = run("clean", "test", "--build-cache", "--configuration-cache", env = changedEnvironment)
      assertThat(restored.task(":test")?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)
      assertThat(restored.output).contains("Reusing configuration cache")
      assertThat(checks.get()).isEqualTo(2)

      val original = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(original.task(":test")?.outcome).describedAs(original.output).isEqualTo(FROM_CACHE)
      assertThat(checks.get()).isEqualTo(2)
    }
  }

  @Test
  fun `inactive tests ignore sidecar configuration changes`() {
    withAgent { server, _, checks ->
      writeProject(server)
      val inactiveEnvironment = agentEnvironment + ("CI_USE_TEST_AGENT" to "false")
      val first = run("clean", "test", "--build-cache", "--configuration-cache", env = inactiveEnvironment)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      val restored = run("clean", "test", "--build-cache", "--configuration-cache", env = inactiveEnvironment + ("CI_TEST_AGENT_IMAGE" to "untracked:latest"))
      assertThat(restored.task(":test")?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)
      assertThat(checks.get()).isZero()
      assertThat(file("build/reports/test-agent")).doesNotExist()
    }
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "CI_TEST_AGENT_IMAGE",
      "CI_TEST_AGENT_ENABLED_CHECKS",
      "CI_TEST_AGENT_SUPPRESS_TRACE_PARSE_ERRORS",
      "CI_TEST_AGENT_POOL_TRACE_CHECK_FAILURES",
      "CI_TEST_AGENT_DISABLE_ERROR_RESPONSES",
    ]
  )
  fun `missing sidecar configuration fails before result reuse`(variable: String) {
    withAgent { server, _, checks ->
      writeProject(server)
      val first = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      val failed = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment - variable, unsetEnv = setOf(variable), expectFailure = true)
      assertThat(failed.task(":test")?.outcome).describedAs(failed.output).isEqualTo(FAILED)
      assertThat(failed.output).contains("Missing APM Test Agent configuration: $variable")
      assertThat(checks.get()).isEqualTo(1)
    }
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "CI_TEST_AGENT_IMAGE",
      "CI_TEST_AGENT_SUPPRESS_TRACE_PARSE_ERRORS",
      "CI_TEST_AGENT_POOL_TRACE_CHECK_FAILURES",
      "CI_TEST_AGENT_DISABLE_ERROR_RESPONSES",
    ]
  )
  fun `invalid sidecar configuration fails before result reuse`(variable: String) {
    withAgent { server, _, checks ->
      writeProject(server)
      val first = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      val value = if (variable == "CI_TEST_AGENT_IMAGE") "test-agent:latest" else "false"
      val failed = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment + (variable to value), expectFailure = true)
      assertThat(failed.task(":test")?.outcome).describedAs(failed.output).isEqualTo(FAILED)
      assertThat(failed.output).contains("$variable must")
      assertThat(checks.get()).isEqualTo(1)
    }
  }

  @Test
  fun `task environment overrides cannot bypass job enabled post checks`() {
    withAgent { server, failing, checks ->
      writeProject(server)
      writeRootProject(
        """
        tasks.withType<Test>().configureEach { environment("CI_USE_TEST_AGENT", "false") }
      """,
        append = true
      )
      failing.set(true)
      val failed = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment, expectFailure = true)
      assertThat(failed.task(":test")?.outcome).describedAs(failed.output).isEqualTo(FAILED)
      assertThat(failed.output).contains("APM Test Agent Check Traces failed")
      assertThat(checks.get()).isEqualTo(1)

      failing.set(false)
      val passed = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(passed.task(":test")?.outcome).describedAs(passed.output).isEqualTo(SUCCESS)
      assertThat(checks.get()).isEqualTo(2)
      assertThat(file("build/reports/test-agent/test/summary_response.txt")).exists()
      assertThat(file("build/reports/test-agent/test/response.txt")).exists()

      writeRootProject(
        """
        tasks.withType<Test>().configureEach { environment("CI_AGENT_HOST", "another-agent") }
      """,
        append = true
      )
      val wrongHost = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment, expectFailure = true)
      assertThat(wrongHost.task(":test")?.outcome).describedAs(wrongHost.output).isEqualTo(FAILED)
      assertThat(wrongHost.output).contains("CI_AGENT_HOST must match")
      assertThat(checks.get()).isEqualTo(2)
    }
  }

  @Test
  fun `checker script contents invalidate previously cached results`() {
    withAgent { server, _, checks ->
      writeProject(server)
      val first = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      file("tooling/check_test_agent_results.sh").appendText("\n# Updated validation script\n")
      val changed = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(changed.task(":test")?.outcome).describedAs(changed.output).isEqualTo(SUCCESS)
      assertThat(changed.output).contains("Reusing configuration cache")
      assertThat(checks.get()).isEqualTo(2)
      val restored = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(restored.task(":test")?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)
      assertThat(checks.get()).isEqualTo(2)
    }
  }

  @Test
  fun `job host changes invalidate the effective test environment`() {
    withAgent { server, _, checks ->
      writeProject(server)
      writeRootProject(
        """
        tasks.withType<Test>().configureEach { environment("ANOTHER_SETTING", "configured") }
      """,
        append = true
      )
      val first = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      val changedEnvironment = agentEnvironment + ("CI_AGENT_HOST" to "localhost")
      val changed = run("clean", "test", "--build-cache", "--configuration-cache", env = changedEnvironment)
      assertThat(changed.task(":test")?.outcome).describedAs(changed.output).isEqualTo(SUCCESS)
      assertThat(checks.get()).isEqualTo(2)
      val restored = run("clean", "test", "--build-cache", "--configuration-cache", env = changedEnvironment)
      assertThat(restored.task(":test")?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)
      assertThat(checks.get()).isEqualTo(2)
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["", "false"])
  fun `inactive invocations leave Test inputs outputs and actions unchanged`(mode: String) {
    withAgent { server, _, checks ->
      writeProject(server)
      writeRootProject(testBuildScript.replace("configureTestAgentPostChecks()", ""))
      val unset = agentEnvironment.keys + setOf("CI", "GITLAB_CI")
      val environment = if (mode.isEmpty()) emptyMap() else mapOf("CI_USE_TEST_AGENT" to mode)
      val first = run("clean", "test", "--build-cache", "--configuration-cache", env = environment, unsetEnv = unset)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      writeRootProject(testBuildScript)
      val restored = run("clean", "test", "--build-cache", "--configuration-cache", env = environment, unsetEnv = unset)
      assertThat(restored.task(":test")?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)
      assertThat(checks.get()).isZero()
      assertThat(file("build/reports/test-agent")).doesNotExist()
    }
  }

  @Test
  fun `invocation opt in invalidates the configuration cache and registers post checks`() {
    withAgent { server, failing, checks ->
      writeProject(server)
      val unset = agentEnvironment.keys + setOf("CI", "GITLAB_CI")
      val inactive = run("clean", "test", "--build-cache", "--configuration-cache", unsetEnv = unset)
      assertThat(inactive.task(":test")?.outcome).describedAs(inactive.output).isEqualTo(SUCCESS)
      failing.set(true)
      val active = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment, unsetEnv = setOf("CI", "GITLAB_CI"), expectFailure = true)
      assertThat(active.task(":test")?.outcome).describedAs(active.output).isEqualTo(FAILED)
      assertThat(active.output).contains("APM Test Agent Check Traces failed")
      assertThat(checks.get()).isEqualTo(1)
    }
  }

  @Test
  fun `developer builds can explicitly enable post checks without CI`() {
    withAgent { server, _, checks ->
      writeProject(server)
      val result = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment, unsetEnv = setOf("CI", "GITLAB_CI"))
      assertThat(result.task(":test")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
      assertThat(checks.get()).isEqualTo(1)
      assertThat(file("build/reports/test-agent/test/summary_response.txt")).hasContent("{}")
      assertThat(file("build/reports/test-agent/test/response.txt")).hasContent("{}")
    }
  }

  @Test
  fun `failed post checks discard reports from previous executions`() {
    withAgent { server, _, checks ->
      writeProject(server)
      val first = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      assertThat(file("build/reports/test-agent/test/summary_response.txt")).exists()
      writeFile("tooling/check_test_agent_results.sh", "exit 1")
      val failed = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment, expectFailure = true)
      assertThat(failed.task(":test")?.outcome).describedAs(failed.output).isEqualTo(FAILED)
      assertThat(checks.get()).isEqualTo(1)
      assertThat(file("build/reports/test-agent/test/summary_response.txt")).doesNotExist()
      assertThat(file("build/reports/test-agent/test/response.txt")).doesNotExist()
    }
  }

  private fun writeProject(server: MockWebServer) {
    writeSettings(
      """
      rootProject.name = "test-agent-check"
      buildCache { local { directory = file("task-cache") } }
      """
    )
    writeRootProject(testBuildScript)
    val script = File("../tooling/check_test_agent_results.sh").readText()
    writeFile("tooling/check_test_agent_results.sh", script.replace(":8126", ":${server.port}"))
    writeJavaSource("Example", "public class Example {}")
    writeJavaSource(
      "ExampleTest",
      """
      public class ExampleTest {
        @org.junit.jupiter.api.Test
        public void passes() {}
      }
      """,
      sourceSet = "test",
    )
  }

  private fun withAgent(test: (MockWebServer, AtomicBoolean, AtomicInteger) -> Unit) {
    val failing = AtomicBoolean()
    val checks = AtomicInteger()
    MockWebServer().use { server ->
      server.setDispatcher(object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (request.requestUrl?.encodedPath() == "/test/trace_check/failures" && request.requestUrl?.queryParameter("return_all") == "true") {
            checks.incrementAndGet()
            if (failing.get()) {
              return MockResponse().setResponseCode(400).setBody("{\"failure\":\"bad trace\"}")
            }
          }
          return MockResponse().setBody("{}")
        }
      })
      server.start()
      test(server, failing, checks)
    }
  }

  private val agentEnvironment = mapOf(
    "CI_USE_TEST_AGENT" to "true",
    "CI_AGENT_HOST" to "127.0.0.1",
    "CI_TEST_AGENT_IMAGE" to "test-agent@sha256:${"1".repeat(64)}",
    "CI_TEST_AGENT_ENABLED_CHECKS" to "trace_peer_service",
    "CI_TEST_AGENT_SUPPRESS_TRACE_PARSE_ERRORS" to "true",
    "CI_TEST_AGENT_POOL_TRACE_CHECK_FAILURES" to "true",
    "CI_TEST_AGENT_DISABLE_ERROR_RESPONSES" to "true",
  )

  private val testBuildScript = """
    import datadog.gradle.plugin.testing.configureTestAgentPostChecks

    plugins { id("dd-trace-java.test-jvm-constraints") }

    repositories { mavenCentral() }
    dependencies {
      testImplementation(platform("org.junit:junit-bom:5.14.1"))
      testImplementation("org.junit.jupiter:junit-jupiter")
      testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
      useJUnitPlatform()
      configureTestAgentPostChecks()
    }
  """
}
