package datadog.smoketest

import com.datadog.profiling.testing.ProfilingTestUtils
import com.google.common.collect.Multimap
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

import java.time.Instant
import java.util.concurrent.TimeUnit

// very 'smokey' test - just to make sure that enabling MLT does not break profiling agent
class ProfilingIntegrationMLTTest extends AbstractSmokeTest {

  // This needs to give enough time for test app to start up and recording to happen
  private static final int REQUEST_WAIT_TIMEOUT = 40

  private final MockWebServer server = new MockWebServer()

  @Override
  ProcessBuilder createProcessBuilder() {
    String profilingShadowJar = System.getProperty("datadog.smoketest.profiling.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.profiling.continuous.to.periodic.upload.ratio=0") // Disable periodic profiles
    command.add("-Ddd.method.trace.sample.rate=1") // profile all traces
    command.addAll((String[]) ["-jar", profilingShadowJar])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  def setup() {
    server.start(profilingPort)
  }

  def cleanup() {
    try {
      server.shutdown()
    } catch (final IOException e) {
      // Looks like this happens for some unclear reason, but should not affect tests
    }
  }

  def "test recording"() {
    setup:
    server.enqueue(new MockResponse().setResponseCode(200))

    when:
    RecordedRequest firstRequest = server.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS)
    Multimap<String, Object> firstRequestParameters =
      ProfilingTestUtils.parseProfilingRequestParameters(firstRequest)


    def logHasErrors = false
    new File("${buildDirectory}/reports/testProcess.${this.getClass().getName()}.log").eachLine {
      if (it.contains("ERROR") || it.contains("WARN")) {
        println it
        logHasErrors = true
      }
    }

    then:
    !logHasErrors
    firstRequest.getRequestUrl().toString() == profilingUrl

    firstRequestParameters.get("format").get(0) == "jfr"
    firstRequestParameters.get("type").get(0) == "jfr-continuous"
    firstRequestParameters.get("runtime").get(0) == "jvm"

    def firstStartTime = Instant.parse(firstRequestParameters.get("recording-start").get(0))
    def firstEndTime = Instant.parse(firstRequestParameters.get("recording-end").get(0))
    firstStartTime != null
    firstEndTime != null
    def duration = firstEndTime.toEpochMilli() - firstStartTime.toEpochMilli()
    duration > TimeUnit.SECONDS.toMillis(PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS - 2)
    duration < TimeUnit.SECONDS.toMillis(PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS + 2)

    Map<String, String> requestTags = ProfilingTestUtils.parseTags(firstRequestParameters.get("tags[]"))
    requestTags.get("service") == "smoke-test-java-app"
    requestTags.get("language") == "jvm"
    requestTags.get("runtime-id") != null
    requestTags.get("host") == InetAddress.getLocalHost().getHostName()

    // profiler is alive
    firstRequestParameters.get("chunk-data").get(0) != null
  }
}
