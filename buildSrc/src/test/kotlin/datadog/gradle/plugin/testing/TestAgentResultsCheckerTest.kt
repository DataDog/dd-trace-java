package datadog.gradle.plugin.testing

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger

class TestAgentResultsCheckerTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `successful checks include every session`() {
    val result = runCheck(summary = passingSummary)
    assertThat(result.failure).describedAs(result.output).isNull()
    assertThat(result.output).contains("All APM Test Agent Check Traces returned successful")
    assertThat(result.requests).isEqualTo(2)
    assertThat(File(directory, "summary_response.txt")).hasContent(passingSummary)
    assertThat(File(directory, "response.txt")).hasContent("Trace check failures")
  }

  @Test
  fun `empty summary reports no checks after checking failures`() {
    val result = runCheck()
    assertThat(result.failure).describedAs(result.output).isNull()
    assertThat(result.output).contains("APM Test Agent reported no trace checks.")
    assertThat(result.output).doesNotContain("returned successful")
    assertThat(result.requests).isEqualTo(2)
  }

  @Test
  fun `pooled failures are rejected even with an empty summary`() {
    val result = runCheck(failuresStatus = 400)
    assertThat(result.failure).isNotNull()
    assertThat(result.output).contains("APM Test Agent Check Traces failed", "Trace check failures")
    assertThat(result.output).doesNotContain("reported no trace checks")
    assertThat(File(directory, "response.txt")).hasContent("Trace check failures")
  }

  @Test
  fun `summary failures are rejected when pooling is disabled`() {
    val result = runCheck(summary = passingSummary.replace("\"Failed_Checks\":0", "\"Failed_Checks\":1"))
    assertThat(result.failure).isNotNull()
    assertThat(result.output).contains("APM Test Agent Check Traces failed: summary reports failing checks.")
    assertThat(result.output).doesNotContain("response code: 200")
    assertThat(result.requests).isEqualTo(2)
  }

  @ParameterizedTest
  @ValueSource(ints = [302, 404, 500])
  fun `missing or failing failure endpoints are rejected`(status: Int) {
    val result = runCheck(failuresStatus = status)
    assertThat(result.failure).isNotNull()
    assertThat(result.output).contains("failed with response code: $status")
  }

  @Test
  fun `unavailable summary is rejected`() {
    val result = runCheck(summaryStatus = 404)
    assertThat(result.failure).isNotNull()
    assertThat(result.output).contains("summary request failed with response code: 404")
    assertThat(result.requests).isEqualTo(1)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "",
      "not json",
      "[]",
      "null",
      "{\"x\":1}",
      "{} {}",
      "{} garbage",
      "{\"trace_peer_service\":{\"Failed_Checks\":0}}",
      "{\"trace_peer_service\":{\"Passed_Checks\":0,\"Failed_Checks\":-1,\"Skipped_Checks\":0}}",
      "{\"trace_peer_service\":{\"Passed_Checks\":0,\"Failed_Checks\":\"0\",\"Skipped_Checks\":0}}",
      "{\"trace_peer_service\":{\"Passed_Checks\":0.5,\"Failed_Checks\":0,\"Skipped_Checks\":0}}",
      "{\"trace_peer_service\":{\"Passed_Checks\":0,\"Failed_Checks\":0,\"Skipped_Checks\":null}}",
      "{\"trace_peer_service\":{\"Passed_Checks\":false,\"Failed_Checks\":0,\"Skipped_Checks\":0}}",
    ]
  )
  fun `invalid summaries are rejected before claiming success`(summary: String) {
    val result = runCheck(summary = summary)
    assertThat(result.failure).isNotNull()
    assertThat(result.output).contains("invalid trace check summary")
    assertThat(result.output).doesNotContain("returned successful")
    assertThat(result.requests).isEqualTo(1)
    assertThat(File(directory, "summary_response.txt")).hasContent(summary)
  }

  @ParameterizedTest
  @ValueSource(strings = ["1.0", "1e2", "9223372036854775808"])
  fun `nonnegative whole numbers are accepted without integer coercion`(count: String) {
    val result = runCheck(summary = passingSummary.replace("\"Passed_Checks\":1", "\"Passed_Checks\":$count"))
    assertThat(result.failure).describedAs(result.output).isNull()
  }

  @Test
  fun `transport failure is rejected`() {
    val result = runCheck(disconnect = true)
    assertThat(result.failure).isNotNull()
    assertThat(result.output).contains("Could not read the APM Test Agent trace check summary.")
  }

  @ParameterizedTest
  @ValueSource(ints = [408, 429, 500, 502, 503, 504])
  fun `transient HTTP failures are retried`(status: Int) {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(status).setBody("Not ready"))
      server.enqueue(MockResponse().setBody(passingSummary))
      server.enqueue(MockResponse().setBody("{}"))
      check(server, retries = 1)
      assertThat(server.requestCount).isEqualTo(3)
      assertThat(File(directory, "summary_response.txt")).hasContent(passingSummary)
    }
  }

  @Test
  fun `failure endpoint is also retried`() {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("{}"))
      server.enqueue(MockResponse().setResponseCode(503).setBody("Not ready"))
      server.enqueue(MockResponse().setBody("{}"))
      check(server, retries = 1)
      assertThat(server.requestCount).isEqualTo(3)
      assertThat(File(directory, "response.txt")).hasContent("{}")
    }
  }

  @Test
  fun `retries are bounded and retain the last HTTP error body`() {
    val result = runCheck(summary = "Not ready", summaryStatus = 503, retries = 2)
    assertThat(result.failure).isNotNull()
    assertThat(result.requests).isEqualTo(3)
    assertThat(result.output).contains("summary request failed with response code: 503", "Not ready")
    assertThat(File(directory, "summary_response.txt")).hasContent("Not ready")
  }

  @Test
  fun `automatic service unavailable retries remain bounded`() {
    MockWebServer().use { server ->
      server.setDispatcher(object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("Not ready")
      })
      val failure = assertThrows<GradleException> { check(server, retries = 5) }
      assertThat(failure).hasMessageContaining("summary request failed with response code: 503")
      // OkHttp may retry each call once internally in addition to the six explicit attempts.
      assertThat(server.requestCount).isBetween(6, 12)
    }
  }

  @Test
  fun `timeout covers the whole response even when data keeps arriving`() {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody(passingSummary).throttleBody(1, 20, MILLISECONDS))
      val failure = assertThrows<GradleException> { check(server, timeoutMillis = 100) }
      assertThat(failure).hasMessage("Could not read the APM Test Agent trace check summary.")
      assertThat(failure.cause).isInstanceOf(IOException::class.java)
    }
  }

  @Test
  fun `transport failures are retried`() {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
      server.enqueue(MockResponse().setBody(passingSummary))
      server.enqueue(MockResponse().setBody("{}"))
      check(server, retries = 1)
      assertThat(server.requestCount).isEqualTo(3)
    }
  }

  private fun runCheck(
    summary: String = "{}",
    summaryStatus: Int = 200,
    failuresStatus: Int = 200,
    disconnect: Boolean = false,
    retries: Int = 0,
  ): CheckResult = MockWebServer().use { server ->
    val requests = AtomicInteger()
    server.setDispatcher(object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        requests.incrementAndGet()
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
    val messages = mutableListOf<String>()
    val failure = try {
      check(server, retries = retries, log = { messages.add(it) })
      null
    } catch (exception: GradleException) {
      messages.add(exception.message.orEmpty())
      exception
    }
    CheckResult(failure, messages.joinToString("\n"), requests.get())
  }

  private fun check(
    server: MockWebServer,
    retries: Int = 0,
    timeoutMillis: Long = 1_000,
    log: (String) -> Unit = {},
  ) {
    val agentUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    TestAgentResultsChecker(agentUrl, directory, log, timeoutMillis, retries, retryDelayMillis = 0).check()
  }

  private data class CheckResult(val failure: GradleException?, val output: String, val requests: Int)

  private val passingSummary = """{"trace_peer_service":{"Passed_Checks":1,"Failed_Checks":0,"Skipped_Checks":0}}"""
}
