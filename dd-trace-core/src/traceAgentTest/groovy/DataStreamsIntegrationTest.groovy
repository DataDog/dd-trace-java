import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.http.OkHttpUtils
import datadog.trace.api.Config
import datadog.trace.api.TraceConfig
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.OkHttpSink
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring
import datadog.trace.test.util.DDSpecification
import okhttp3.HttpUrl
import spock.lang.Ignore
import spock.lang.Requires
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CopyOnWriteArrayList

import static datadog.trace.common.metrics.EventListener.EventType.OK
import static datadog.trace.core.datastreams.DefaultDataStreamsMonitoring.DEFAULT_BUCKET_DURATION_NANOS

@Requires({
  "true" == System.getenv("CI")
})
@Ignore("The agent in CI doesn't have a valid API key. Unlike metrics and traces, data streams fails in this case")
class DataStreamsIntegrationTest extends DDSpecification {

  def "Sending stats bucket to agent should notify with OK event"() {
    given:
    def conditions = new PollingConditions(timeout: 1)

    def sharedCommunicationObjects = new SharedCommunicationObjects()
    sharedCommunicationObjects.createRemaining(Config.get())

    OkHttpSink sink = new OkHttpSink(
      OkHttpUtils.buildHttpClient(HttpUrl.parse(Config.get().getAgentUrl()), 5000L),
      Config.get().getAgentUrl(),
      DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT,
      false,
      true,
      [:])

    def listener = new BlockingListener()
    sink.register(listener)

    def timeSource = new ControllableTimeSource()

    def traceConfig = Mock(TraceConfig) {
      isDataStreamsEnabled() >> true
    }

    when:
    def dataStreams = new DefaultDataStreamsMonitoring(sink, sharedCommunicationObjects.featuresDiscovery, timeSource, { traceConfig }, Config.get())
    dataStreams.start()
    dataStreams.accept(new StatsPoint("testType", "testGroup", "testTopic", 1, 2, timeSource.currentTimeNanos, 0, 0))
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
    dataStreams.report()

    then:
    sharedCommunicationObjects.featuresDiscovery.supportsDataStreams()
    conditions.eventually {
      assert listener.events.size() == 1
    }
    listener.events[0] == OK

    cleanup:
    dataStreams.close()
  }

  static class BlockingListener implements EventListener {
    List<EventType> events = new CopyOnWriteArrayList<>()

    @Override
    void onEvent(EventType eventType, String message) {
      events.add(eventType)
    }
  }
}
