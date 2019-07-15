package datadog.smoketest

import com.datadog.profiling.testing.ProfilingTestUtils
import com.google.common.collect.Multimap
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.openjdk.jmc.common.item.IItemCollection
import org.openjdk.jmc.common.item.ItemFilters
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit

import java.time.Instant
import java.util.concurrent.TimeUnit

class ProfilingIntegrationPeriodicProfilesTest extends AbstractSmokeTest {

  // This needs to give enough time for test app to start up and recording to happen
  private static final int REQUEST_WAIT_TIMEOUT = 40

  private final MockWebServer server = new MockWebServer()

  @Override
  ProcessBuilder createProcessBuilder() {
    String profilingShadowJar = System.getProperty("datadog.smoketest.profiling.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.profiling.continuous.to.periodic.upload.ratio=1") // Make all profiles periodic
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

  def "test periodic recording"() {
    setup:
    server.enqueue(new MockResponse().setResponseCode(200))

    when:
    RecordedRequest firstRequest
    Multimap<String, Object> firstRequestParameters
    while (true) {
      firstRequest = server.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS)
      firstRequestParameters =
        ProfilingTestUtils.parseProfilingRequestParameters(firstRequest)
      // Skip non-first chunks
      if (firstRequestParameters.get("chunk-seq-num").get(0) == "0") {
        break
      }
    }

    then:
    firstRequest.getRequestUrl().toString() == profilingUrl
    firstRequest.getHeader("Authorization") == Credentials.basic(PROFILING_API_KEY, "")

    firstRequestParameters.get("recording-name").get(0) == 'dd-profiling'
    firstRequestParameters.get("format").get(0) == "jfr"
    firstRequestParameters.get("type").get(0) == "jfr-periodic"
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

    firstRequestParameters.get("chunk-seq-num").get(0) == "0"
    firstRequestParameters.get("chunk-data").get(0) != null

    when:
    RecordedRequest secondRequest
    Multimap<String, Object> secondRequestParameters
    while (true) {
      secondRequest = server.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS)
      secondRequestParameters =
        ProfilingTestUtils.parseProfilingRequestParameters(secondRequest)
      // Skip non-first chunks
      if (secondRequestParameters.get("chunk-seq-num").get(0) == "0") {
        break
      }
    }

    then:
    secondRequest.getRequestUrl().toString() == profilingUrl
    secondRequest.getHeader("Authorization") == Credentials.basic(PROFILING_API_KEY, "")

    secondRequestParameters.get("recording-name").get(0) == 'dd-profiling'
    def secondStartTime = Instant.parse(secondRequestParameters.get("recording-start").get(0))
    def period = secondStartTime.toEpochMilli() - firstStartTime.toEpochMilli()
    period > TimeUnit.SECONDS.toMillis(PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS - 2)
    period < TimeUnit.SECONDS.toMillis(PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS + 2)

    secondRequestParameters.get("chunk-seq-num").get(0) == "0"
    firstRequestParameters.get("chunk-data").get(0) != null

    IItemCollection events = JfrLoaderToolkit.loadEvents(new ByteArrayInputStream(secondRequestParameters.get("chunk-data").get(0)))
    IItemCollection scopeEvents = events.apply(ItemFilters.type("datadog.Scope"))

    scopeEvents.size() > 0
  }

}
