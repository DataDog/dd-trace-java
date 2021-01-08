package datadog.trace.common.metrics

import datadog.trace.api.WellKnownTags
import datadog.trace.test.util.DDSpecification
import spock.lang.Requires

import static datadog.trace.api.Platform.isJavaVersionAtLeast
import static java.util.concurrent.TimeUnit.SECONDS

@Requires({ "true" == System.getenv("CI") && isJavaVersionAtLeast(8) })
class AgentIntegrationTest extends DDSpecification {


  def "send metrics to trace agent should notify with OK event"() {
    setup:
    def listener = Mock(EventListener)
    OkHttpSink sink = new OkHttpSink("http://localhost:8126", 5000L, false)
    sink.register(listener)

    when:
    SerializingMetricWriter writer = new SerializingMetricWriter(
      new WellKnownTags("hostname", "env", "service", "version"),
      sink
    )
    writer.startBucket(2, System.nanoTime(), SECONDS.toNanos(10))
    writer.add(
      new MetricKey("resource1", "service1", "operation1", "sql", 0),
      new AggregateMetric().recordDurations(5, 2, 1, 2, 250, 4, 5)
    )
    writer.add(
      new MetricKey("resource2", "service2", "operation2", "web", 200),
      new AggregateMetric().recordDurations(10, 1, 1, 200, 2, 3, 4, 5, 6, 7, 8, 9)
    )
    writer.finishBucket()

    then:
    1 * listener.onEvent(EventListener.EventType.OK, _)

  }
}
