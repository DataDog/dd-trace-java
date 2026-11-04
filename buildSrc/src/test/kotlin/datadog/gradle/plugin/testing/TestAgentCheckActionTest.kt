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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TestAgentCheckActionTest : GradleFixture() {
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

      val restored = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(restored.task(":test")?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)
      assertThat(restored.task(":compileJava")?.outcome).isEqualTo(FROM_CACHE)
      assertThat(restored.output).contains("Reusing configuration cache")
      assertThat(checks.get()).isEqualTo(3)
    }
  }

  @Test
  fun `parallel test tasks use separate response directories`() {
    withAgent { server, _, checks ->
      writeProject(server)
      addSubproject("second", testBuildScript.replace("layout.projectDirectory", "rootProject.layout.projectDirectory"))
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
      listOf("build/tmp/test", "second/build/tmp/test").forEach { directory ->
        assertThat(file("$directory/summary_response.txt")).exists()
        assertThat(file("$directory/response.txt")).exists()
      }
      assertThat(file("summary_response.txt")).doesNotExist()
      assertThat(file("response.txt")).doesNotExist()
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
    val script = File("../.gitlab/check_test_agent_results.sh").readText()
    writeFile(".gitlab/check_test_agent_results.sh", script.replace(":8126", ":${server.port}"))
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
          if (request.path == "/test/trace_check/failures") {
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
  )

  private val testBuildScript = """
    import datadog.gradle.plugin.testing.configureTestAgentChecks

    plugins { id("dd-trace-java.test-jvm-constraints") }

    repositories { mavenCentral() }
    dependencies {
      testImplementation(platform("org.junit:junit-bom:5.14.1"))
      testImplementation("org.junit.jupiter:junit-jupiter")
      testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
      useJUnitPlatform()
      configureTestAgentChecks(layout.projectDirectory.file(".gitlab/check_test_agent_results.sh").asFile)
    }
  """
}
