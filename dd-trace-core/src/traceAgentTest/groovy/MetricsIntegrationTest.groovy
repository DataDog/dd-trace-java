


import datadog.trace.api.WellKnownTags
import datadog.trace.common.metrics.AggregateMetric
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.MetricKey
import datadog.trace.common.metrics.OkHttpSink
import datadog.trace.common.metrics.SerializingMetricWriter
import datadog.trace.test.util.DDSpecification
import spock.lang.Requires

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLongArray

import static datadog.trace.api.Platform.isJavaVersionAtLeast
import static datadog.trace.common.metrics.EventListener.EventType.OK
import static java.util.concurrent.TimeUnit.SECONDS

@Requires({
  "true" == System.getenv("CI") && isJavaVersionAtLeast(8)
})
class MetricsIntegrationTest extends DDSpecification {


  def "send metrics to trace agent should notify with OK event"() {
    setup:
    def latch = new CountDownLatch(1)
    def listener = new BlockingListener(latch)
    OkHttpSink sink = new OkHttpSink("http://localhost:8126", 5000L, true)
    sink.register(listener)

    when:
    SerializingMetricWriter writer = new SerializingMetricWriter(
      new WellKnownTags("runtimeid","hostname", "env", "service", "version"),
      sink
      )
    writer.startBucket(2, System.nanoTime(), SECONDS.toNanos(10))
    writer.add(
      new MetricKey("resource1", "service1", "operation1", "sql", 0),
      new AggregateMetric().recordDurations(5, new AtomicLongArray(2, 1, 2, 250, 4, 5))
      )
    writer.add(
      new MetricKey("resource2", "service2", "operation2", "web", 200),
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
