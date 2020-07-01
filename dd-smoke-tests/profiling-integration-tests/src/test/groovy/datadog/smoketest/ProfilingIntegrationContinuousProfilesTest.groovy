package datadog.smoketest

import com.datadog.profiling.testing.ProfilingTestUtils
import com.google.common.collect.Multimap
import net.jpountz.lz4.LZ4FrameInputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.openjdk.jmc.common.item.Aggregators
import org.openjdk.jmc.common.item.Attribute
import org.openjdk.jmc.common.item.IItemCollection
import org.openjdk.jmc.common.item.ItemFilters
import org.openjdk.jmc.common.unit.UnitLookup
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit

import java.time.Instant
import java.util.concurrent.TimeUnit

class ProfilingIntegrationContinuousProfilesTest extends AbstractProfilingIntegrationTest {
  private static final int REQUEST_WAIT_TIMEOUT = 40

  def "test continuous recording"() {
    setup:
    profilingServer.enqueue(new MockResponse().setResponseCode(200))

    when:
    RecordedRequest firstRequest = profilingServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS)
    Multimap<String, Object> firstRequestParameters =
      ProfilingTestUtils.parseProfilingRequestParameters(firstRequest)

    then:
    firstRequest.getRequestUrl().toString() == profilingUrl
    firstRequest.getHeader("DD-API-KEY") == apiKey()

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

    firstRequestParameters.get("chunk-data").get(0) != null

    when:
    RecordedRequest secondRequest = profilingServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS)
    Multimap<String, Object> secondRequestParameters =
      ProfilingTestUtils.parseProfilingRequestParameters(secondRequest)

    checkLog()

    then:
    !logHasErrors
    secondRequest.getRequestUrl().toString() == profilingUrl
    secondRequest.getHeader("DD-API-KEY") == apiKey()

    def secondStartTime = Instant.parse(secondRequestParameters.get("recording-start").get(0))
    def period = secondStartTime.toEpochMilli() - firstStartTime.toEpochMilli()
    period > TimeUnit.SECONDS.toMillis(PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS - 2)
    period < TimeUnit.SECONDS.toMillis(PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS + 2)

    firstRequestParameters.get("chunk-data").get(0) != null

    IItemCollection events = JfrLoaderToolkit.loadEvents(new LZ4FrameInputStream(new ByteArrayInputStream(secondRequestParameters.get("chunk-data").get(0))))
    IItemCollection scopeEvents = events.apply(ItemFilters.type("datadog.Scope"))

    scopeEvents.size() > 0

    def cpuTimeAttr = Attribute.attr("cpuTime", "cpuTime", UnitLookup.TIMESPAN)

    // filter out scope events without CPU time data
    def filteredScopeEvents = scopeEvents.apply(ItemFilters.more(cpuTimeAttr, UnitLookup.NANOSECOND.quantity(Long.MIN_VALUE)))
    // make sure there is at least one scope event with CPU time data
    filteredScopeEvents.hasItems()

    filteredScopeEvents.getAggregate(Aggregators.min("datadog.Scope", cpuTimeAttr)).longValue() >= 10_000L

    // check exception events
    events.apply(ItemFilters.type("datadog.ExceptionSample")).hasItems()
    events.apply(ItemFilters.type("datadog.ExceptionCount")).hasItems()

    // check deadlock events
    events.apply(ItemFilters.type("datadog.Deadlock")).hasItems()
    events.apply(ItemFilters.type("datadog.DeadlockedThread")).hasItems()
  }
}
