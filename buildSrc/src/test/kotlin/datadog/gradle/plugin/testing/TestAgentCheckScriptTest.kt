package datadog.gradle.plugin.testing

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.concurrent.TimeUnit

class TestAgentCheckScriptTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `successful checks include every session`() {
    val result = runScript(summary = passingSummary)
    assertThat(result.exitCode).describedAs(result.output).isZero()
    assertThat(result.output).contains("All APM Test Agent Check Traces returned successful")
    assertThat(result.requests).isEqualTo(2)
  }

  @Test
  fun `empty summary reports no checks after checking failures`() {
    val result = runScript()
    assertThat(result.exitCode).describedAs(result.output).isZero()
    assertThat(result.output).contains("APM Test Agent reported no trace checks.")
    assertThat(result.output).doesNotContain("returned successful")
    assertThat(result.requests).isEqualTo(2)
  }

  @Test
  fun `pooled failures are rejected even with an empty summary`() {
    val result = runScript(failuresStatus = 400)
    assertThat(result.exitCode).describedAs(result.output).isNotZero()
    assertThat(result.output).contains("APM Test Agent Check Traces failed")
    assertThat(result.output).doesNotContain("reported no trace checks")
  }

  @Test
  fun `summary failures are rejected when pooling is disabled`() {
    val result = runScript(summary = passingSummary.replace("\"Failed_Checks\":0", "\"Failed_Checks\":1"))
    assertThat(result.exitCode).describedAs(result.output).isNotZero()
    assertThat(result.output).contains("APM Test Agent Check Traces failed: summary reports failing checks.")
    assertThat(result.output).doesNotContain("response code: 200")
    assertThat(result.requests).isEqualTo(2)
  }

  @ParameterizedTest
  @ValueSource(ints = [404, 500])
  fun `missing or failing failure endpoints are rejected`(status: Int) {
    val result = runScript(failuresStatus = status)
    assertThat(result.exitCode).describedAs(result.output).isNotZero()
    assertThat(result.output).contains("failed with response code: $status")
  }

  @Test
  fun `unavailable summary is rejected`() {
    val result = runScript(summaryStatus = 404)
    assertThat(result.exitCode).describedAs(result.output).isNotZero()
    assertThat(result.output).contains("summary request failed with response code: 404")
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "",
    "not json",
    "[]",
    "null",
    "{\"x\":1}",
    "{} {}",
    "{\"trace_peer_service\":{\"Failed_Checks\":0}}",
    "{\"trace_peer_service\":{\"Passed_Checks\":0,\"Failed_Checks\":-1,\"Skipped_Checks\":0}}",
    "{\"trace_peer_service\":{\"Passed_Checks\":0,\"Failed_Checks\":\"0\",\"Skipped_Checks\":0}}",
  ])
  fun `invalid summaries are rejected before claiming success`(summary: String) {
    val result = runScript(summary = summary)
    assertThat(result.exitCode).describedAs(result.output).isNotZero()
    assertThat(result.output).contains("invalid trace check summary")
    assertThat(result.output).doesNotContain("returned successful")
  }

  @Test
  fun `transport failure is rejected`() {
    val result = runScript(disconnect = true)
    assertThat(result.exitCode).describedAs(result.output).isNotZero()
    assertThat(result.output).contains("Could not read the APM Test Agent trace check summary.")
  }

  @Test
  fun `inactive agent does not make HTTP requests`() {
    val result = runScript(enabled = "false")
    assertThat(result.exitCode).describedAs(result.output).isZero()
    assertThat(result.requests).isZero()
    assertThat(result.output).contains("APM Test Agent is not enabled")
  }

  private fun runScript(
    summary: String = "{}",
    summaryStatus: Int = 200,
    failuresStatus: Int = 200,
    enabled: String = "true",
    disconnect: Boolean = false,
  ): ScriptResult = MockWebServer().use { server ->
    server.setDispatcher(object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        if (disconnect) {
          return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        if (request.requestUrl?.queryParameter("return_all") != "true") {
          return MockResponse().setResponseCode(400).setBody("Expected all sessions")
        }
        return when (request.requestUrl?.encodedPath()) {
          "/test/trace_check/summary" -> MockResponse().setResponseCode(summaryStatus).setBody(summary)
          "/test/trace_check/failures" -> MockResponse().setResponseCode(failuresStatus).setBody("Trace check failures")
          else -> MockResponse().setResponseCode(404)
        }
      }
    })
    server.start()
    val script = File(directory, "check.sh")
    script.writeText(File("../tooling/check_test_agent_results.sh").readText().replace(":8126", ":${server.port}"))
    val output = File(directory, "output.log")
    val builder = ProcessBuilder("bash", script.absolutePath)
      .directory(directory)
      .redirectErrorStream(true)
      .redirectOutput(output)
    builder.environment()["CI_AGENT_HOST"] = "127.0.0.1"
    builder.environment()["CI_USE_TEST_AGENT"] = enabled
    val process = builder.start()
    try {
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).describedAs(output.readText()).isTrue()
      ScriptResult(process.exitValue(), output.readText(), server.requestCount)
    } finally {
      process.destroyForcibly()
    }
  }

  private data class ScriptResult(val exitCode: Int, val output: String, val requests: Int)

  private val passingSummary = """{"trace_peer_service":{"Passed_Checks":1,"Failed_Checks":0,"Skipped_Checks":0}}"""
}
