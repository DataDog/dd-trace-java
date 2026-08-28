import static datadog.trace.common.metrics.EventListener.EventType.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled(
    "The agent in CI doesn't have a valid API key. Unlike metrics and traces, data streams fails in this case")
class DataStreamsIntegrationTest extends AbstractTraceAgentTest {

  @Test
  void sendingStatsBucketToAgentShouldNotifyWithOkEvent() throws ReflectiveOperationException {
    SharedCommunicationObjects sharedCommunicationObjects = new SharedCommunicationObjects();
    sharedCommunicationObjects.createRemaining(Config.get());

    OkHttpSink sink =
        new OkHttpSink(
            OkHttpUtils.buildHttpClient(HttpUrl.parse(Config.get().getAgentUrl()), 5000L),
            Config.get().getAgentUrl(),
            DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT,
            false,
            true,
            Collections.emptyMap());

    BlockingListener listener = new BlockingListener();
    sink.register(listener);

    ControllableTimeSource timeSource = new ControllableTimeSource();

    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    try (DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            sink,
            sharedCommunicationObjects.featuresDiscovery(Config.get()),
            timeSource,
            () -> traceConfig,
            Config.get())) {

      dataStreams.start();
      DataStreamsTags tags =
          DataStreamsTags.create("testType", null, "testTopic", "testGroup", null);
      dataStreams.add(
          new StatsPoint(tags, 1, 2, 5, timeSource.getCurrentTimeNanos(), 0, 0, 0, null));
      timeSource.advance(Config.get().getDataStreamsBucketDurationNanoseconds());
      invokeReport(dataStreams);

      assertTrue(sharedCommunicationObjects.featuresDiscovery(Config.get()).supportsDataStreams());
      // conditions.eventually { assert listener.events.size() == 1 }
      waitForEvents(listener, 1);
      assertEquals(OK, listener.events.get(0));
    }
  }

  private static void waitForEvents(BlockingListener listener, int expectedCount) {
    long deadline = System.currentTimeMillis() + 1000;
    while (System.currentTimeMillis() < deadline && listener.events.size() < expectedCount) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertEquals(expectedCount, listener.events.size());
  }

  private static void invokeReport(DefaultDataStreamsMonitoring dataStreams)
      throws ReflectiveOperationException {
    Method report = DefaultDataStreamsMonitoring.class.getDeclaredMethod("report");
    report.setAccessible(true);
    report.invoke(dataStreams);
  }

  static class BlockingListener implements EventListener {
    List<EventType> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(EventType eventType, String message) {
      events.add(eventType);
    }
  }
}
