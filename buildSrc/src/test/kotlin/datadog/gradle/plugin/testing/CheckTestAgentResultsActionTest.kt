package datadog.gradle.plugin.testing

import datadog.gradle.plugin.GradleFixture
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.NO_SOURCE
import org.gradle.testkit.runner.TaskOutcome.SKIPPED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ASM9
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CheckTestAgentResultsActionTest : GradleFixture() {
  private var agentPort = 8126

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
  @ValueSource(strings = [
    "CI_TEST_AGENT_IMAGE",
    "CI_TEST_AGENT_ENABLED_CHECKS",
    "CI_TEST_AGENT_SUPPRESS_TRACE_PARSE_ERRORS",
    "CI_TEST_AGENT_POOL_TRACE_CHECK_FAILURES",
    "CI_TEST_AGENT_DISABLE_ERROR_RESPONSES",
  ])
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
  @ValueSource(strings = [
    "CI_TEST_AGENT_IMAGE",
    "CI_TEST_AGENT_ENABLED_CHECKS",
    "CI_TEST_AGENT_SUPPRESS_TRACE_PARSE_ERRORS",
    "CI_TEST_AGENT_POOL_TRACE_CHECK_FAILURES",
    "CI_TEST_AGENT_DISABLE_ERROR_RESPONSES",
  ])
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
  @ValueSource(strings = [
    "CI_TEST_AGENT_IMAGE",
    "CI_TEST_AGENT_SUPPRESS_TRACE_PARSE_ERRORS",
    "CI_TEST_AGENT_POOL_TRACE_CHECK_FAILURES",
    "CI_TEST_AGENT_DISABLE_ERROR_RESPONSES",
  ])
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
      writeRootProject("""
        tasks.withType<Test>().configureEach { environment("CI_USE_TEST_AGENT", "false") }
      """, append = true)
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

      writeRootProject("""
        tasks.withType<Test>().configureEach { environment("CI_AGENT_HOST", "another-agent") }
      """, append = true)
      val wrongHost = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment, expectFailure = true)
      assertThat(wrongHost.task(":test")?.outcome).describedAs(wrongHost.output).isEqualTo(FAILED)
      assertThat(wrongHost.output).contains("CI_AGENT_HOST must match")
      assertThat(checks.get()).isEqualTo(2)
    }
  }

  @Test
  fun `checker implementation changes invalidate previously cached results`() {
    withAgent { server, _, checks ->
      writeProject(server)
      val metadata = Properties().apply {
        CheckTestAgentResultsActionTest::class.java.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use { load(it) }
      }
      val classpath = metadata.getProperty("implementation-classpath").split(File.pathSeparator).map(::File)
      val checkerPath = "datadog/gradle/plugin/testing/TestAgentResultsChecker.class"
      val originalClasses = classpath.first { File(it, checkerPath).isFile }
      val classes = file("plugin-classes")
      check(originalClasses.copyRecursively(classes))
      val pluginClasspath = classpath.map { if (it == originalClasses) classes else it }
      val first = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment, pluginClasspath = pluginClasspath)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      // Change only the native checker, leaving its action and the test inputs unchanged.
      val checkerClass = File(classes, checkerPath)
      val writer = ClassWriter(0)
      ClassReader(checkerClass.readBytes()).accept(object : ClassVisitor(ASM9, writer) {
        override fun visitEnd() {
          super.visitField(ACC_PRIVATE or ACC_STATIC or ACC_FINAL, "validationPolicyVersion", "I", null, 2).visitEnd()
          super.visitEnd()
        }
      }, 0)
      checkerClass.writeBytes(writer.toByteArray())
      val changed = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment, pluginClasspath = pluginClasspath)
      assertThat(changed.task(":test")?.outcome).describedAs(changed.output).isEqualTo(SUCCESS)
      assertThat(checks.get()).isEqualTo(2)
      val restored = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment, pluginClasspath = pluginClasspath)
      assertThat(restored.task(":test")?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)
      assertThat(restored.output).contains("Reusing configuration cache")
      assertThat(checks.get()).isEqualTo(2)
    }
  }

  @Test
  fun `job host changes invalidate the effective test environment`() {
    withAgent { server, _, checks ->
      writeProject(server)
      writeRootProject("""
        tasks.withType<Test>().configureEach { environment("ANOTHER_SETTING", "configured") }
      """, append = true)
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
      server.setDispatcher(object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody("not json")
      })
      val failed = run("test", "--rerun", "--build-cache", "--configuration-cache", env = agentEnvironment, expectFailure = true)
      assertThat(failed.task(":test")?.outcome).describedAs(failed.output).isEqualTo(FAILED)
      assertThat(checks.get()).isEqualTo(1)
      assertThat(file("build/reports/test-agent/test/summary_response.txt")).hasContent("not json")
      assertThat(file("build/reports/test-agent/test/response.txt")).doesNotExist()
    }
  }

  @Test
  fun `cached and up to date tests do not contact the live agent`() {
    withAgent { server, failing, checks ->
      writeProject(server)
      val first = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      assertThat(checks.get()).isEqualTo(1)
      failing.set(true)

      val cached = run("clean", "test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(cached.task(":test")?.outcome).describedAs(cached.output).isEqualTo(FROM_CACHE)
      val upToDate = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(upToDate.task(":test")?.outcome).describedAs(upToDate.output).isEqualTo(UP_TO_DATE)
      assertThat(checks.get()).isEqualTo(1)
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["skipped", "no source"])
  fun `skipped and no source tests do not contact the live agent`(mode: String) {
    withAgent { server, failing, checks ->
      writeProject(server)
      failing.set(true)
      if (mode == "skipped") {
        writeRootProject("tasks.withType<Test>().configureEach { onlyIf { false } }", append = true)
      } else {
        check(file("src/test").deleteRecursively())
      }
      val tests = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(tests.task(":test")?.outcome).describedAs(tests.output).isEqualTo(if (mode == "skipped") SKIPPED else NO_SOURCE)
      assertThat(checks.get()).isZero()
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `tolerated assertions still run trace validation and retain failing reports`(traceFailure: Boolean) {
    withAgent { server, failing, checks ->
      writeProject(server)
      writeFailingTest()
      failing.set(traceFailure)
      writeRootProject("""
        tasks.register("later") {
          mustRunAfter(tasks.named("test"))
          doLast { println("Remaining tasks executed") }
        }
      """, append = true)

      val result = run("test", "later", "--continue", "--build-cache", "--configuration-cache",
        env = agentEnvironment + ("CONTINUE_ON_FAILURE" to "true"), expectFailure = traceFailure)
      assertThat(result.task(":test")?.outcome).describedAs(result.output).isEqualTo(if (traceFailure) FAILED else SUCCESS)
      assertThat(result.task(":later")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
      assertThat(checks.get()).isEqualTo(1)
      assertThat(parseXml(file("build/test-results/test/TEST-ExampleTest.xml")).documentElement.getAttribute("failures")).isEqualTo("1")
      if (traceFailure) {
        assertThat(result.output).contains("APM Test Agent Check Traces failed")
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `failure policy transitions never reuse tolerated failures with or without an agent`(agentEnabled: Boolean) {
    withAgent { server, _, checks ->
      writeProject(server)
      writeFailingTest()
      val normalEnvironment = agentEnvironment + ("CI_USE_TEST_AGENT" to agentEnabled.toString())
      val tolerantEnvironment = normalEnvironment + ("CONTINUE_ON_FAILURE" to "true")
      val normal = run("test", "-PrunFlakyTests", "--build-cache", "--configuration-cache", env = normalEnvironment, expectFailure = true)
      assertThat(normal.task(":test")?.outcome).describedAs(normal.output).isEqualTo(FAILED)
      assertThat(checks.get()).isZero()

      repeat(2) {
        val tolerated = run("test", "--build-cache", "--configuration-cache", env = tolerantEnvironment)
        assertThat(tolerated.task(":test")?.outcome).describedAs(tolerated.output).isEqualTo(SUCCESS)
        assertThat(parseXml(file("build/test-results/test/TEST-ExampleTest.xml")).documentElement.getAttribute("failures")).isEqualTo("1")
        if (it == 1) {
          assertThat(tolerated.output).contains("Reusing configuration cache")
        }
      }
      val clean = run("clean", "test", "--build-cache", "--configuration-cache", "--info", env = tolerantEnvironment)
      assertThat(clean.task(":test")?.outcome).describedAs(clean.output).isEqualTo(SUCCESS)
      assertThat(clean.output).contains("CI tolerates test failures")
      assertThat(checks.get()).isEqualTo(if (agentEnabled) 3 else 0)

      val normalAgain = run("test", "--build-cache", "--configuration-cache", env = normalEnvironment, expectFailure = true)
      assertThat(normalAgain.task(":test")?.outcome).describedAs(normalAgain.output).isEqualTo(FAILED)
      val cleanNormal = run("clean", "test", "--build-cache", "--configuration-cache", env = normalEnvironment, expectFailure = true)
      assertThat(cleanNormal.task(":test")?.outcome).describedAs(cleanNormal.output).isEqualTo(FAILED)
      assertThat(checks.get()).isEqualTo(if (agentEnabled) 3 else 0)
    }
  }

  @Test
  fun `failure tolerance does not suppress compilation errors`() {
    withAgent { server, _, checks ->
      writeProject(server)
      writeJavaSource("Example", "public class Example {")
      val result = run("test", "--continue", "--configuration-cache",
        env = agentEnvironment + ("CONTINUE_ON_FAILURE" to "true"), expectFailure = true)
      assertThat(result.task(":compileJava")?.outcome).describedAs(result.output).isEqualTo(FAILED)
      assertThat(result.task(":test")).isNull()
      assertThat(checks.get()).isZero()
    }
  }

  @Test
  fun `checks follow the job port and refresh the cached test environment`() {
    withAgent { firstServer, _, firstChecks ->
      writeProject(firstServer)
      val firstEnvironment = agentEnvironment
      val first = run("clean", "test", "--build-cache", "--configuration-cache", env = firstEnvironment)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      assertThat(firstChecks.get()).isEqualTo(1)

      withAgent { secondServer, _, secondChecks ->
        val secondEnvironment = firstEnvironment + ("CI_TEST_AGENT_PORT" to secondServer.port.toString())
        val changed = run("clean", "test", "--build-cache", "--configuration-cache", env = secondEnvironment)
        assertThat(changed.task(":test")?.outcome).describedAs(changed.output).isEqualTo(SUCCESS)
        assertThat(changed.output).doesNotContain("Reusing configuration cache")
        assertThat(file("build/test-results/test/TEST-ExampleTest.xml").readText())
          .contains("Test Agent port: ${secondServer.port}")
        assertThat(secondChecks.get()).isEqualTo(1)
        assertThat(firstChecks.get()).isEqualTo(1)

        val restored = run("clean", "test", "--build-cache", "--configuration-cache", env = secondEnvironment)
        assertThat(restored.task(":test")?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)
        assertThat(restored.output).contains("Reusing configuration cache")
        assertThat(secondChecks.get()).isEqualTo(1)
        val original = run("clean", "test", "--build-cache", "--configuration-cache", env = firstEnvironment)
        assertThat(original.task(":test")?.outcome).describedAs(original.output).isEqualTo(FROM_CACHE)
        assertThat(firstChecks.get()).isEqualTo(1)
      }
    }
  }

  @Test
  fun `task port overrides cannot send traces away from the checked sidecar`() {
    withAgent { server, _, checks ->
      writeProject(server)
      val first = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment)
      assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
      writeRootProject("""
        tasks.withType<Test>().configureEach { environment("CI_TEST_AGENT_PORT", "1") }
      """, append = true)
      val failed = run("test", "--build-cache", "--configuration-cache", env = agentEnvironment, expectFailure = true)
      assertThat(failed.task(":test")?.outcome).describedAs(failed.output).isEqualTo(FAILED)
      assertThat(failed.output).contains("CI_TEST_AGENT_PORT must match")
      assertThat(checks.get()).isEqualTo(1)
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["", "0", "-1", "65536", "not-a-port"])
  fun `checks reject an invalid job port`(port: String) {
    withAgent { server, _, checks ->
      writeProject(server)
      val environment = agentEnvironment + ("CI_TEST_AGENT_PORT" to port)
      val tests = run("test", "--configuration-cache", env = environment, expectFailure = true)
      assertThat(tests.task(":test")?.outcome).describedAs(tests.output).isEqualTo(FAILED)
      assertThat(tests.output).contains("CI_TEST_AGENT_PORT must be an integer between 1 and 65535")
      assertThat(checks.get()).isZero()
    }
  }

  @Test
  fun `job endpoint defaults to the existing sidecar port`() {
    withAgent { server, _, checks ->
      writeProject(server)
      writeRootProject("""
        println("Test Agent endpoint: " + tasks.named<Test>("test").get().inputs.properties["ciTestAgentUrl"])
      """, append = true)
      val result = run("help", env = agentEnvironment - "CI_TEST_AGENT_PORT", unsetEnv = setOf("CI_TEST_AGENT_PORT"))
      assertThat(result.output).contains("Test Agent endpoint: http://127.0.0.1:8126/")
      assertThat(checks.get()).isZero()
    }
  }

  private fun writeProject(server: MockWebServer) {
    agentPort = server.port
    writeSettings(
      """
      rootProject.name = "test-agent-check"
      buildCache { local { directory = file("task-cache") } }
      """
    )
    writeRootProject(testBuildScript)
    writeJavaSource("Example", "public class Example {}")
    writeJavaSource(
      "ExampleTest",
      """
      public class ExampleTest {
        @org.junit.jupiter.api.Test
        public void passes() {
          System.out.println("Test Agent port: " + System.getenv("CI_TEST_AGENT_PORT"));
        }
      }
      """,
      sourceSet = "test",
    )
  }

  private fun writeFailingTest() {
    writeJavaSource(
      "ExampleTest",
      """
      public class ExampleTest {
        @org.junit.jupiter.api.Test
        public void fails() { throw new AssertionError("Expected flaky failure"); }
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

  private val agentEnvironment get() = mapOf(
    "CI_USE_TEST_AGENT" to "true",
    "CONTINUE_ON_FAILURE" to "false",
    "CI_AGENT_HOST" to "127.0.0.1",
    "CI_TEST_AGENT_PORT" to agentPort.toString(),
    "CI_TEST_AGENT_IMAGE" to "test-agent@sha256:${"1".repeat(64)}",
    "CI_TEST_AGENT_ENABLED_CHECKS" to "trace_peer_service",
    "CI_TEST_AGENT_SUPPRESS_TRACE_PARSE_ERRORS" to "true",
    "CI_TEST_AGENT_POOL_TRACE_CHECK_FAILURES" to "true",
    "CI_TEST_AGENT_DISABLE_ERROR_RESPONSES" to "true",
  )

  private val testBuildScript = """
    import datadog.gradle.plugin.testing.configureTestAgentPostChecks
    import datadog.gradle.plugin.testing.configureTestFailurePolicy

    plugins { id("dd-trace-java.test-jvm-constraints") }

    repositories { mavenCentral() }
    dependencies {
      testImplementation(platform("org.junit:junit-bom:5.14.1"))
      testImplementation("org.junit.jupiter:junit-jupiter")
      testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
      useJUnitPlatform()
      configureTestFailurePolicy()
      configureTestAgentPostChecks()
    }
  """
}
