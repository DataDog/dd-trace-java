import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.http.OkHttpUtils
import datadog.metrics.api.Histograms
import datadog.metrics.impl.DDSketchHistograms
import datadog.trace.api.Config
import datadog.trace.api.WellKnownTags
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.common.metrics.AggregateMetric
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.MetricKey
import datadog.trace.common.metrics.OkHttpSink
import datadog.trace.common.metrics.SerializingMetricWriter
import okhttp3.HttpUrl

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLongArray

import static datadog.trace.common.metrics.EventListener.EventType.OK
import static java.util.concurrent.TimeUnit.SECONDS

class MetricsIntegrationTest extends AbstractTraceAgentTest {

  def setupSpec() {
    // Initialize metrics-lib histograms to register the DDSketch implementation
    Histograms.register(DDSketchHistograms.FACTORY)
  }

  def "send metrics to trace agent should notify with OK event"() {
    setup:
    def latch = new CountDownLatch(1)
    def listener = new BlockingListener(latch)
    def agentUrl = Config.get().getAgentUrl()
    OkHttpSink sink = new OkHttpSink(OkHttpUtils.buildHttpClient(HttpUrl.parse(agentUrl), 5000L), agentUrl, DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT, true, false, [:])
    sink.register(listener)

    when:
    SerializingMetricWriter writer = new SerializingMetricWriter(
      new WellKnownTags("runtimeid","hostname", "env", "service", "version","language"),
      sink
      )
    writer.startBucket(2, System.nanoTime(), SECONDS.toNanos(10))
    writer.add(
      new MetricKey("resource1", "service1", "operation1", "sql", 0, false, true, "xyzzy", [UTF8BytesString.create("grault:quux")]),
      new AggregateMetric().recordDurations(5, new AtomicLongArray(2, 1, 2, 250, 4, 5))
      )
    writer.add(
      new MetricKey("resource2", "service2", "operation2", "web", 200, false, true, "xyzzy", [UTF8BytesString.create("grault:quux")]),
      new AggregateMetric().recordDurations(10, new AtomicLongArray(1, 1, 200, 2, 3, 4, 5, 6, 7, 8, 9))
      )
    writer.finishBucket()

    then:
    latch.await(5, SECONDS)
    listener.events.size() == 1 && listener.events[0] == OK
  }

  static class BlockingListener implements EventListener {

    List<EventType> events = new CopyOnWriteArrayList<>()
    final CountDownLatch latch

    BlockingListener(CountDownLatch latch) {
      this.latch = latch
    }

    @Override
    void onEvent(EventType eventType, String message) {
      events.add(eventType)
      latch.countDown()
    }
  }
}
