import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT;
import static datadog.trace.common.metrics.EventListener.EventType.OK;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.http.OkHttpUtils;
import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.metrics.AggregateMetric;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.MetricKey;
import datadog.trace.common.metrics.OkHttpSink;
import datadog.trace.common.metrics.SerializingMetricWriter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLongArray;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MetricsIntegrationTest extends AbstractTraceAgentTest {

  @BeforeAll
  static void initHistograms() {
    // Initialize metrics-lib histograms to register the DDSketch implementation
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  @Test
  void sendMetricsToTraceAgentShouldNotifyWithOKEvent() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    BlockingListener listener = new BlockingListener(latch);
    String agentUrl = Config.get().getAgentUrl();
    OkHttpSink sink =
        new OkHttpSink(
            OkHttpUtils.buildHttpClient(HttpUrl.parse(agentUrl), 5000L),
            agentUrl,
            V06_METRICS_ENDPOINT,
            true,
            false,
            Collections.<String, String>emptyMap());
    sink.register(listener);

    SerializingMetricWriter writer =
        new SerializingMetricWriter(
            new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language"),
            sink);
    writer.startBucket(2, System.nanoTime(), SECONDS.toNanos(10));
    writer.add(
        new MetricKey(
            "resource1",
            "service1",
            "operation1",
            null,
            "sql",
            0,
            false,
            true,
            "xyzzy",
            Collections.singletonList(UTF8BytesString.create("grault:quux")),
            null,
            null,
            null),
        new AggregateMetric()
            .recordDurations(5, new AtomicLongArray(new long[] {2, 1, 2, 250, 4, 5})));
    writer.add(
        new MetricKey(
            "resource2",
            "service2",
            "operation2",
            null,
            "web",
            200,
            false,
            true,
            "xyzzy",
            Collections.singletonList(UTF8BytesString.create("grault:quux")),
            null,
            null,
            null),
        new AggregateMetric()
            .recordDurations(
                10, new AtomicLongArray(new long[] {1, 1, 200, 2, 3, 4, 5, 6, 7, 8, 9})));
    writer.finishBucket();

    assertTrue(latch.await(5, SECONDS));
    assertEquals(1, listener.events.size());
    assertEquals(OK, listener.events.get(0));
  }

  static class BlockingListener implements EventListener {

    List<EventListener.EventType> events = new CopyOnWriteArrayList<>();
    CountDownLatch latch;

    BlockingListener(CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public void onEvent(EventListener.EventType eventType, String message) {
      events.add(eventType);
      latch.countDown();
    }
  }
}
