import static datadog.trace.common.metrics.EventListener.EventType.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.OkHttpSink;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Disabled(
    "The agent in CI doesn't have a valid API key. Unlike metrics and traces, data streams fails in this case")
@ExtendWith(MockitoExtension.class)
class DataStreamsIntegrationTest extends AbstractTraceAgentTest {

  @Mock TraceConfig traceConfig;

  @Test
  void sendingStatsBucketToAgentShouldNotifyWithOKEvent() throws Exception {
    SharedCommunicationObjects sharedCommunicationObjects = new SharedCommunicationObjects();
    sharedCommunicationObjects.createRemaining(Config.get());

    OkHttpSink sink =
        new OkHttpSink(
            OkHttpUtils.buildHttpClient(HttpUrl.parse(Config.get().getAgentUrl()), 5000L),
            Config.get().getAgentUrl(),
            DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT,
            false,
            true,
            new HashMap<String, String>());

    BlockingListener listener = new BlockingListener();
    sink.register(listener);

    ControllableTimeSource timeSource = new ControllableTimeSource();

    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            sharedCommunicationObjects.featuresDiscovery(Config.get()),
            timeSource,
            () -> traceConfig,
            Config.get());
    dataStreams.start();
    try {
      DataStreamsTags tg = DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
      dataStreams.add(new StatsPoint(tg, 1, 2, 5, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
      timeSource.advance(Config.get().getDataStreamsBucketDurationNanoseconds());
      dataStreams.report();

      assertTrue(sharedCommunicationObjects.featuresDiscovery(Config.get()).supportsDataStreams());

      long deadline = System.currentTimeMillis() + 1000L;
      while (listener.events.size() < 1 && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
      }
      assertEquals(1, listener.events.size());
      assertEquals(OK, listener.events.get(0));
    } finally {
      dataStreams.close();
    }
  }

  static class BlockingListener implements EventListener {
    List<EventListener.EventType> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(EventListener.EventType eventType, String message) {
      events.add(eventType);
    }
  }
}
