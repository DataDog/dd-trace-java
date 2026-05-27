import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT
import static datadog.trace.common.metrics.EventListener.EventType.OK
import static java.util.concurrent.TimeUnit.SECONDS

import datadog.communication.http.OkHttpUtils
import datadog.metrics.api.Histograms
import datadog.metrics.impl.DDSketchHistograms
import datadog.trace.api.Config
import datadog.trace.api.WellKnownTags
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.common.metrics.AggregateEntry
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.OkHttpSink
import datadog.trace.common.metrics.PeerTagSchema
import datadog.trace.common.metrics.SerializingMetricWriter
import datadog.trace.common.metrics.SpanSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import okhttp3.HttpUrl

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
    OkHttpSink sink = new OkHttpSink(OkHttpUtils.buildHttpClient(HttpUrl.parse(agentUrl), 5000L), agentUrl, V06_METRICS_ENDPOINT, true, false, [:])
    sink.register(listener)

    when:
    SerializingMetricWriter writer = new SerializingMetricWriter(
      new WellKnownTags("runtimeid","hostname", "env", "service", "version","language"),
      sink
      )
    writer.startBucket(2, System.nanoTime(), SECONDS.toNanos(10))
    // Build entries via the production AggregateEntry.forSnapshot(snap, keyHash) path -- same
    // construction as AggregateTable.findOrInsert. Both entries use one peer tag (grault:quux)
    // -> schema names=["grault"], values=["quux"].
    PeerTagSchema schema = PeerTagSchema.testSchema(["grault"] as String[])
    SpanSnapshot snap1 = new SpanSnapshot(
      "resource1", "service1", "operation1", null, "sql", (short) 0,
      false, true, "xyzzy", schema, ["quux"] as String[], null, null, null, 0L)
    def entry1 = new AggregateEntry(snap1, AggregateEntry.hashOf(snap1))
    [2, 1, 2, 250, 4].each { entry1.recordOneDuration(it as long) }
    writer.add(entry1)
    SpanSnapshot snap2 = new SpanSnapshot(
      "resource2", "service2", "operation2", null, "web", (short) 200,
      false, true, "xyzzy", schema, ["quux"] as String[], null, null, null, 0L)
    def entry2 = new AggregateEntry(snap2, AggregateEntry.hashOf(snap2))
    [1, 1, 200, 2, 3, 4, 5, 6, 7, 8].each { entry2.recordOneDuration(it as long) }
    writer.add(entry2)
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
