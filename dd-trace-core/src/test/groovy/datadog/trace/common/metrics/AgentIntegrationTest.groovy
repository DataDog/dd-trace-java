package datadog.trace.common.metrics

import datadog.trace.api.WellKnownTags
import datadog.trace.test.util.DDSpecification
import spock.lang.Ignore
import spock.lang.Requires

import static java.util.concurrent.TimeUnit.SECONDS

@Ignore("requires an upgrade to an as yet unreleased agent to run")
@Requires({ "true" == System.getenv("CI") })
class AgentIntegrationTest extends DDSpecification {


  def "send metrics to trace agent should notify with OK event"() {
    setup:
    def listener = Mock(EventListener)
    OkHttpSink sink = new OkHttpSink("http://localhost:8126", 5000L)
    sink.register(listener)

    when:
    SerializingMetricWriter writer = new SerializingMetricWriter(
      new WellKnownTags("hostname", "env", "service", "version"),
      sink
    )
    writer.startBucket(2, System.nanoTime(), SECONDS.toNanos(10))
    writer.add(
      new MetricKey("resource1", "service1", "operation1", 0),
      new AggregateMetric().addHits(10).addErrors(1)
    )
    writer.add(
      new MetricKey("resource2", "service2", "operation2", 200),
      new AggregateMetric().addHits(9).addErrors(1)
    )
    writer.finishBucket()

    then:
    1 * listener.onEvent(EventListener.EventType.OK, _)

  }
}
