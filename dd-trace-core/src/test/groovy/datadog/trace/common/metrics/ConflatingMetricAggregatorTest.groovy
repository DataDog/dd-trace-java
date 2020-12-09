package datadog.trace.common.metrics

import datadog.trace.api.WellKnownTags
import datadog.trace.core.CoreSpan
import datadog.trace.test.util.DDSpecification
import spock.lang.Requires

import java.util.concurrent.CountDownLatch

import static datadog.trace.api.Platform.isJavaVersionAtLeast
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS

@Requires({ isJavaVersionAtLeast(8) })
class ConflatingMetricAggregatorTest extends DDSpecification {

  def "should ignore traces with no measured spans"() {
    setup:
    Sink sink = Mock(Sink)
    WellKnownTags wellKnownTags = new WellKnownTags("hostname", "env", "service", "version")
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(
      wellKnownTags,
      sink,
      10,
      10,
      1,
      MILLISECONDS
    )
    aggregator.start()

    when:
    aggregator.publish([new SimpleSpan("", "", "", "", false, false, 0, 0)])

    then:
    0 * sink._

    cleanup:
    aggregator.close()
  }

  def "aggregate repetitive spans"() {
    setup:
    int reportingInterval = 10
    CountDownLatch latch = new CountDownLatch(1)
    MetricWriter writer = Mock(MetricWriter)
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(
      Mock(Sink), writer, 10, 10, reportingInterval, SECONDS)
    long duration = 100
    List<CoreSpan> trace = [
      new SimpleSpan("service", "operation", "resource", "type", true, false, 0, duration),
      new SimpleSpan("service1", "operation1", "resource1", "type", false, false, 0, 0),
      new SimpleSpan("service2", "operation2", "resource2", "type", true, false, 0, duration * 2)
    ]
    aggregator.start()


    when:
    for (int i = 0; i < count; ++i) {
      aggregator.publish(trace)
    }
    aggregator.stop()
    latch.await(10, SECONDS)

    then: "metrics should be conflated"
    1 * writer.finishBucket() >> { latch.countDown() }
    1 * writer.startBucket(2, _, SECONDS.toNanos(reportingInterval))
    1 * writer.add(new MetricKey("resource", "service", "operation", "type", 0), _) >> {
      MetricKey key, AggregateMetric value -> value.getHitCount() == count && value.getDuration() == count * duration
    }
    1 * writer.add(new MetricKey("resource2", "service2", "operation2", "type", 0), _) >> {
      MetricKey key, AggregateMetric value -> value.getHitCount() == count && value.getDuration() == count * duration * 2
    }

    cleanup:
    aggregator.close()

    where:
    count << [10, 100]
  }


}
