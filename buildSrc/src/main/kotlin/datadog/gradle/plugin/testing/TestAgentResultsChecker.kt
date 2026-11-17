package datadog.gradle.plugin.testing

import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS
import com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.api.GradleException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit.MILLISECONDS

/** Validates all sessions on the running APM Test Agent and retains its responses. */
internal class TestAgentResultsChecker(
  private val agentUrl: String,
  private val reportDirectory: File,
  private val log: (String) -> Unit,
  timeoutMillis: Long = 30_000,
  private val retries: Int = 5,
  private val retryDelayMillis: Long = 2_000,
) {
  private val client = httpClient.newBuilder()
    .callTimeout(timeoutMillis, MILLISECONDS)
    .connectTimeout(timeoutMillis, MILLISECONDS)
    .readTimeout(timeoutMillis, MILLISECONDS)
    .build()

  fun check() {
    // Discard reports from previous executions or cache restores before checking the live agent.
    check(reportDirectory.deleteRecursively() && reportDirectory.mkdirs()) {
      "Cannot prepare Test Agent report directory: $reportDirectory"
    }

    val summaryFile = File(reportDirectory, "summary_response.txt")
    val summaryStatus = request("summary", summaryFile)
    if (summaryStatus != 200) {
      throw GradleException("APM Test Agent summary request failed with response code: $summaryStatus\n${summaryFile.readText()}")
    }

    val summary = readSummary(summaryFile)
    val failuresFile = File(reportDirectory, "response.txt")
    val failuresStatus = request("failures", failuresFile)
    if (failuresStatus != 200) {
      throw GradleException(
        "APM Test Agent Check Traces failed with response code: $failuresStatus\n" +
          "Failures:\n${failuresFile.readText()}\n" +
          "APM Test Agent Check Traces Summary Results:\n${summary.toPrettyString()}"
      )
    }
    if (summary.any { it.path("Failed_Checks").decimalValue().signum() != 0 }) {
      throw GradleException("APM Test Agent Check Traces failed: summary reports failing checks.\n${summary.toPrettyString()}")
    }

    if (summary.isEmpty) {
      log("APM Test Agent reported no trace checks.")
    } else {
      log("All APM Test Agent Check Traces returned successful! (HTTP 200)")
      log("APM Test Agent Check Traces Summary Results:\n${summary.toPrettyString()}")
    }
  }

  private fun readSummary(file: File): JsonNode {
    val summary = try {
      ObjectMapper().enable(FAIL_ON_TRAILING_TOKENS, USE_BIG_DECIMAL_FOR_FLOATS).readTree(file)
    } catch (exception: IOException) {
      throw GradleException("APM Test Agent returned an invalid trace check summary.\n${file.readText()}", exception)
    }
    if (summary == null || !summary.isObject || !summary.all { entry ->
        entry.isObject && listOf("Passed_Checks", "Failed_Checks", "Skipped_Checks").all { name ->
          val count = entry.path(name)
          count.isNumber && count.decimalValue().let { it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0 }
        }
      }
    ) {
      throw GradleException("APM Test Agent returned an invalid trace check summary.\n${file.readText()}")
    }
    return summary
  }

  private fun request(endpoint: String, report: File): Int {
    val request = Request.Builder()
      .url("${agentUrl.trimEnd('/')}/test/trace_check/$endpoint?return_all=true")
      .build()
    for (attempt in 0..retries) {
      try {
        client.newCall(request).execute().use { response ->
          report.outputStream().use { output -> response.body()?.byteStream()?.use { it.copyTo(output) } }
          if (response.code() !in listOf(408, 429, 500, 502, 503, 504) || attempt == retries) {
            return response.code()
          }
        }
      } catch (exception: IOException) {
        if (attempt == retries) {
          throw GradleException("Could not read the APM Test Agent trace check $endpoint.", exception)
        }
      }
      Thread.sleep(retryDelayMillis)
    }
    error("Unreachable")
  }

  companion object {
    private val httpClient = OkHttpClient.Builder()
      .retryOnConnectionFailure(false)
      .followRedirects(false)
      .followSslRedirects(false)
      .build()
  }
}
