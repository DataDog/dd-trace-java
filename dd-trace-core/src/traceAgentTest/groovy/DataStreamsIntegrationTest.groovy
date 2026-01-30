import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.http.HttpUtils
import datadog.http.client.HttpUrl
import datadog.trace.api.Config
import datadog.trace.api.TraceConfig
import datadog.trace.api.datastreams.DataStreamsTags
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.api.datastreams.StatsPoint
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.HttpSink
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring
import spock.lang.Ignore
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CopyOnWriteArrayList

import static datadog.trace.common.metrics.EventListener.EventType.OK

@Ignore("The agent in CI doesn't have a valid API key. Unlike metrics and traces, data streams fails in this case")
class DataStreamsIntegrationTest extends AbstractTraceAgentTest {

  def "Sending stats bucket to agent should notify with OK event"() {
    given:
    def conditions = new PollingConditions(timeout: 1)

    def sharedCommunicationObjects = new SharedCommunicationObjects()
    sharedCommunicationObjects.createRemaining(Config.get())

    HttpSink sink = new HttpSink(
      HttpUtils.buildHttpClient(HttpUrl.parse(Config.get().getAgentUrl()), 5000L),
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
    def dataStreams = new DefaultDataStreamsMonitoring(sink, sharedCommunicationObjects.featuresDiscovery(Config.get()), timeSource, { traceConfig }, Config.get())
    dataStreams.start()
    def tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null)
    dataStreams.add(new StatsPoint(tg, 1, 2, 5, timeSource.currentTimeNanos, 0, 0, 0, null))
    timeSource.advance(Config.get().getDataStreamsBucketDurationNanoseconds())
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
