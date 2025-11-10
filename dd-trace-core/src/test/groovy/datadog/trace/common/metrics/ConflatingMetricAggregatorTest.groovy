package datadog.trace.common.metrics

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.WellKnownTags
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.CoreSpan
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.test.util.DDSpecification
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier
import spock.lang.Shared

class ConflatingMetricAggregatorTest extends DDSpecification {

  static Set<String> empty = new HashSet<>()

  static final int HTTP_OK = 200

  @Shared
  long reportingInterval = 1
  @Shared
  int queueSize = 256

  def "should ignore traces with no measured spans"() {
    setup:
    Sink sink = Mock(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language")
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(
      wellKnownTags,
      empty,
      features,
      HealthMetrics.NO_OP,
      sink,
      10,
      queueSize,
      1,
      MILLISECONDS
      )
    aggregator.start()

    aggregator.publish([new SimpleSpan("", "", "", "", false, false, false, 0, 0, HTTP_OK)])
    when:
    reportAndWaitUntilEmpty(aggregator)
    then:
    0 * sink._

    cleanup:
    aggregator.close()
  }

  def "should ignore traces with ignored resource names"() {
    setup:
    String ignoredResourceName = "foo"
    Sink sink = Mock(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language")
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(
      wellKnownTags,
      [ignoredResourceName].toSet(),
      features,
      HealthMetrics.NO_OP,
      sink,
      10,
      queueSize,
      1,
      MILLISECONDS
      )
    aggregator.start()

    when: "publish ignored resource names"
    aggregator.publish([new SimpleSpan("", "", ignoredResourceName, "", true, true, false, 0, 0, HTTP_OK)])
    aggregator.publish([
      new SimpleSpan("", "", UTF8BytesString.create(ignoredResourceName), "", true, true, false, 0, 0, HTTP_OK)
    ])
    aggregator.publish([
      new SimpleSpan("", "", ignoredResourceName, "", true, true, false, 0, 0, HTTP_OK),
      new SimpleSpan("", "",
      "measured, not ignored, but child of ignored, so should be ignored", "", true, true, false, 0, 0, HTTP_OK)
    ])
    reportAndWaitUntilEmpty(aggregator)
    then:
    0 * sink._

    cleanup:
    aggregator.close()
  }

  def "should be resilient to null resource names"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS)
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    aggregator.publish([
      new SimpleSpan("service", "operation", null, "type", false, true, false, 0, 100, HTTP_OK)
      .setTag(SPAN_KIND, "baz")
    ])
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then:
    latchTriggered
    1 * writer.startBucket(1, _, _)
    1 * writer.add(new MetricKey(
      null,
      "service",
      "operation",
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      []
      ), _) >> { MetricKey key, AggregateMetric value ->
        value.getHitCount() == 1 && value.getTopLevelCount() == 1 && value.getDuration() == 100
      }
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "unmeasured top level spans have metrics computed"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS)
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    aggregator.publish([
      new SimpleSpan("service", "operation", "resource", "type", false, true, false, 0, 100, HTTP_OK)
      .setTag(SPAN_KIND, "baz")
    ])
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then:
    latchTriggered
    1 * writer.startBucket(1, _, _)
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      []
      ), _) >> { MetricKey key, AggregateMetric value ->
        value.getHitCount() == 1 && value.getTopLevelCount() == 1 && value.getDuration() == 100
      }
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "should compute stats for span kind #kind"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS)
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    def span = new SimpleSpan("service", "operation", "resource", "type", false, false, false, 0, 100, HTTP_OK)
      .setTag(SPAN_KIND, kind)
    aggregator.publish([span])
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then:
    latchTriggered == statsComputed
    (statsComputed ? 1 : 0) * writer.startBucket(1, _, _)
    (statsComputed ? 1 : 0) * writer.add(
      new MetricKey(
      "resource",
      "service",
      "operation",
      "type",
      HTTP_OK,
      false,
      false,
      kind,
      []
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 0 && aggregateMetric.getDuration() == 100
      })
    (statsComputed ? 1 : 0) * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()

    where:
    kind                             | statsComputed
    "client"                         | true
    "producer"                       | true
    "consumer"                       | true
    UTF8BytesString.create("server") | true
    "internal"                       | false
    null                             | false
  }

  def "should create bucket for each set of peer tags"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >>> [["country"], ["country", "georegion"],]
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS)
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    aggregator.publish([
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, 100, HTTP_OK)
      .setTag(SPAN_KIND, "client").setTag("country", "france").setTag("georegion", "europe"),
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, 100, HTTP_OK)
      .setTag(SPAN_KIND, "client").setTag("country", "france").setTag("georegion", "europe")
    ])
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then:
    latchTriggered
    1 * writer.startBucket(2, _, _)
    1 * writer.add(
      new MetricKey(
      "resource",
      "service",
      "operation",
      "type",
      HTTP_OK,
      false,
      false,
      "client",
      [UTF8BytesString.create("country:france")]
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 0 && aggregateMetric.getDuration() == 100
      })
    1 * writer.add(
      new MetricKey(
      "resource",
      "service",
      "operation",
      "type",
      HTTP_OK,
      false,
      false,
      "client",
      [UTF8BytesString.create("country:france"), UTF8BytesString.create("georegion:europe")]
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 0 && aggregateMetric.getDuration() == 100
      })
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "should aggregate the right peer tags for kind #kind"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> ["peer.hostname", "_dd.base_service"]
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS)
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    aggregator.publish([
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, 100, HTTP_OK)
      .setTag(SPAN_KIND, kind).setTag("peer.hostname", "localhost").setTag("_dd.base_service", UTF8BytesString.create("test"))
    ])
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then:
    latchTriggered
    1 * writer.startBucket(1, _, _)
    1 * writer.add(
      new MetricKey(
      "resource",
      "service",
      "operation",
      "type",
      HTTP_OK,
      false,
      false,
      kind,
      expectedPeerTags
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 0 && aggregateMetric.getDuration() == 100
      })
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()

    where:
    kind       | expectedPeerTags
    "client"   | [UTF8BytesString.create("peer.hostname:localhost"), UTF8BytesString.create("_dd.base_service:test")]
    "internal" | [UTF8BytesString.create("_dd.base_service:test")]
    "server"   | []
  }

  def "measured spans do not contribute to top level count"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty, features, HealthMetrics.NO_OP,
      sink, writer, 10, queueSize, reportingInterval, SECONDS)
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    aggregator.publish([
      new SimpleSpan("service", "operation", "resource", "type", measured, topLevel, false, 0, 100, HTTP_OK)
      .setTag(SPAN_KIND, "baz")
    ])
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then:
    latchTriggered
    1 * writer.startBucket(1, _, _)
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      []
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getTopLevelCount() == topLevelCount && value.getDuration() == 100
      })
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()

    where:
    measured | topLevel | topLevelCount
    true     | false    | 0
    true     | true     | 1
    false    | true     | 1
  }

  def "aggregate repetitive spans"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS)
    long duration = 100
    List<CoreSpan> trace = [
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration, HTTP_OK).setTag(SPAN_KIND, "baz"),
      new SimpleSpan("service1", "operation1", "resource1", "type", false, false, false, 0, 0, HTTP_OK).setTag(SPAN_KIND, "baz"),
      new SimpleSpan("service2", "operation2", "resource2", "type", true, false, false, 0, duration * 2, HTTP_OK).setTag(SPAN_KIND, "baz")
    ]
    aggregator.start()


    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < count; ++i) {
      aggregator.publish(trace)
    }
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then: "metrics should be conflated"
    latchTriggered
    1 * writer.finishBucket() >> { latch.countDown() }
    1 * writer.startBucket(2, _, SECONDS.toNanos(reportingInterval))
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      []
      ), { AggregateMetric value ->
        value.getHitCount() == count && value.getDuration() == count * duration
      })
    1 * writer.add(new MetricKey(
      "resource2",
      "service2",
      "operation2",
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      []
      ), { AggregateMetric value ->
        value.getHitCount() == count && value.getDuration() == count * duration * 2
      })

    cleanup:
    aggregator.close()

    where:
    count << [10, 100]
  }

  def "test least recently written to aggregate flushed when size limit exceeded"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, reportingInterval, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < 11; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration, HTTP_OK)
        .setTag(SPAN_KIND, "baz")
      ])
    }
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then: "the first aggregate should be dropped but the rest reported"
    latchTriggered
    1 * writer.startBucket(10, _, SECONDS.toNanos(reportingInterval))
    for (int i = 1; i < 11; ++i) {
      1 * writer.add(new MetricKey(
        "resource",
        "service" + i,
        "operation",
        "type",
        HTTP_OK,
        false,
        false,
        "baz",
        []
        ), _) >> { MetricKey key, AggregateMetric value ->
          value.getHitCount() == 1 && value.getDuration() == duration
        }
    }
    0 * writer.add(new MetricKey(
      "resource",
      "service0",
      "operation",
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      []
      ), _)
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "aggregate not updated in reporting interval not reported"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, reportingInterval, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < 5; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration, HTTP_OK)
        .setTag(SPAN_KIND, "baz")
      ])
    }
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then: "all aggregates should be reported"
    latchTriggered
    1 * writer.startBucket(5, _, SECONDS.toNanos(reportingInterval))
    for (int i = 0; i < 5; ++i) {
      1 * writer.add(new MetricKey(
        "resource",
        "service" + i,
        "operation",
        "type",
        HTTP_OK,
        false,
        false,
        "baz",
        []
        ), { AggregateMetric value ->
          value.getHitCount() == 1 && value.getDuration() == duration
        })
    }
    1 * writer.finishBucket() >> { latch.countDown() }

    when:
    latch = new CountDownLatch(1)
    for (int i = 1; i < 5; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration, HTTP_OK)
        .setTag(SPAN_KIND, "baz")
      ])
    }
    aggregator.report()
    latchTriggered = latch.await(2, SECONDS)

    then: "aggregate not updated in cycle is not reported"
    latchTriggered
    1 * writer.startBucket(4, _, SECONDS.toNanos(reportingInterval))
    for (int i = 1; i < 5; ++i) {
      1 * writer.add(new MetricKey(
        "resource",
        "service" + i,
        "operation",
        "type",
        HTTP_OK,
        false,
        false,
        "baz",
        []
        ), { AggregateMetric value ->
          value.getHitCount() == 1 && value.getDuration() == duration
        })
    }
    0 * writer.add(new MetricKey(
      "resource",
      "service0",
      "operation",
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      []
      ), _)
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "when no aggregate is updated in reporting interval nothing is reported"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, reportingInterval, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < 5; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration, HTTP_OK)
        .setTag(SPAN_KIND, "quux")
      ])
    }
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then: "all aggregates should be reported"
    latchTriggered
    1 * writer.startBucket(5, _, SECONDS.toNanos(reportingInterval))
    for (int i = 0; i < 5; ++i) {
      1 * writer.add(new MetricKey(
        "resource",
        "service" + i,
        "operation",
        "type",
        HTTP_OK,
        false,
        false,
        "quux",
        []
        ), { AggregateMetric value ->
          value.getHitCount() == 1 && value.getDuration() == duration
        })
    }
    1 * writer.finishBucket() >> { latch.countDown() }

    when:
    reportAndWaitUntilEmpty(aggregator)

    then: "aggregate not updated in cycle is not reported"
    0 * writer.finishBucket()
    0 * writer.startBucket(_, _, _)
    0 * writer.add(_, _)

    cleanup:
    aggregator.close()
  }

  def "should report periodically"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, 1, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < 5; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration, HTTP_OK, true)
        .setTag(SPAN_KIND, "garply")
      ])
    }
    def latchTriggered = latch.await(2, SECONDS)

    then: "all aggregates should be reported"
    latchTriggered
    1 * writer.startBucket(5, _, SECONDS.toNanos(1))
    for (int i = 0; i < 5; ++i) {
      1 * writer.add(new MetricKey(
        "resource",
        "service" + i,
        "operation",
        "type",
        HTTP_OK,
        false,
        true,
        "garply",
        []
        ), { AggregateMetric value ->
          value.getHitCount() == 1 && value.getDuration() == duration
        })
    }
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "should be resilient to serialization errors"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, 1, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < 5; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration, HTTP_OK)
      ])
    }
    def latchTriggered = latch.await(2, SECONDS)

    then: "writer should be reset if reporting fails"
    latchTriggered
    1 * writer.startBucket(_, _, _) >> {
      throw new IllegalArgumentException("something went wrong")
    }
    1 * writer.reset() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "force flush should not block if metrics are disabled"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, 1, SECONDS)
    aggregator.start()

    when:
    def flushed = aggregator.forceReport().get(10, SECONDS)

    then:
    notThrown(TimeoutException)
    !flushed

    cleanup:
    aggregator.close()
  }

  def "should start even if the agent is not available"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> false
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, 200, MILLISECONDS)
    final spans = [
      new SimpleSpan("service", "operation", "resource", "type", false, true, false, 0, 10, HTTP_OK)
    ]
    aggregator.start()

    when:
    aggregator.publish(spans)
    Thread.sleep(1_000)

    then:
    0 * writer._
    when:
    features.supportsMetrics() >> true
    aggregator.publish(spans)
    Thread.sleep(1_000)

    then:
    (1.._) * writer._

    cleanup:
    aggregator.close()
  }

  def "force flush should wait for aggregator to start"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, 1, SECONDS)

    when:
    def async = CompletableFuture.supplyAsync(new Supplier<Boolean>() {
        @Override
        Boolean get() {
          return aggregator.forceReport().get()
        }
      })
    async.get(3, SECONDS)

    then:
    thrown(TimeoutException)

    when:
    aggregator.start()
    def flushed = async.get(3, TimeUnit.SECONDS)

    then:
    notThrown(TimeoutException)
    flushed

    cleanup:
    aggregator.close()
  }

  def "should not count partial snapshot(long running)"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS)
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    aggregator.publish([
      new SimpleSpan("service", "operation", "resource", "type", true, true, false, 0, 100, HTTP_OK, true, 12345),
      new SimpleSpan("service", "operation", "resource", "type", true, true, false, 0, 100, HTTP_OK, true, 0)
    ])
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then:
    latchTriggered
    1 * writer.startBucket(1, _, _)
    1 * writer.add(
      new MetricKey(
      "resource",
      "service",
      "operation",
      "type",
      HTTP_OK,
      false,
      true,
      "",
      []
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 1 && aggregateMetric.getDuration() == 100
      })
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def reportAndWaitUntilEmpty(ConflatingMetricsAggregator aggregator) {
    waitUntilEmpty(aggregator)
    aggregator.report()
    waitUntilEmpty(aggregator)
  }


  def waitUntilEmpty(ConflatingMetricsAggregator aggregator) {
    int i = 0
    while (!aggregator.inbox.isEmpty() && i++ < 100) {
      Thread.sleep(10)
    }
  }
}
