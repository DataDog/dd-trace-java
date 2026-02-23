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
      MILLISECONDS, false
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
      MILLISECONDS, false
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
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, false)
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
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      [],
      null,
      null
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
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, false)
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
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      [],
      null,
      null
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
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, true)
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    def span = new SimpleSpan("service", "operation", "resource", "type", false, false, false, 0, 100, HTTP_OK)
      .setTag(SPAN_KIND, kind)
    if (httpMethod != null) {
      span.setTag("http.method", httpMethod)
    }
    if (httpEndpoint != null) {
      span.setTag("http.endpoint", httpEndpoint)
    }
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
      null,
      "type",
      HTTP_OK,
      false,
      false,
      kind,
      [],
      httpMethod,
      httpEndpoint
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 0 && aggregateMetric.getDuration() == 100
      })
    (statsComputed ? 1 : 0) * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()

    where:
    kind                             | httpMethod | httpEndpoint        | statsComputed
    "client"                         | null       | null                | true
    "producer"                       | null       | null                | true
    "consumer"                       | null       | null                | true
    UTF8BytesString.create("server") | null       | null                | true
    "internal"                       | null       | null                | false
    null                             | null       | null                | false
    "server"                         | "GET"      | "/api/users/:id"    | true
    "server"                         | "POST"     | "/api/orders"       | true
    "server"                         | "DELETE"   | "/api/products/:id" | true
    "client"                         | "GET"      | "/external/api"     | true
  }

  def "should create bucket for each set of peer tags"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >>> [["country"], ["country", "georegion"],]
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, false)
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
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "client",
      [UTF8BytesString.create("country:france")],
      null,
      null
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 0 && aggregateMetric.getDuration() == 100
      })
    1 * writer.add(
      new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "client",
      [UTF8BytesString.create("country:france"), UTF8BytesString.create("georegion:europe")],
      null,
      null
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
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, false)
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
      null,
      "type",
      HTTP_OK,
      false,
      false,
      kind,
      expectedPeerTags,
      null,
      null
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
      sink, writer, 10, queueSize, reportingInterval, SECONDS, false)
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
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      [],
      null,
      null
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
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, false)
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
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      [],
      null,
      null
      ), { AggregateMetric value ->
        value.getHitCount() == count && value.getDuration() == count * duration
      })
    1 * writer.add(new MetricKey(
      "resource2",
      "service2",
      "operation2",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      [],
      null,
      null
      ), { AggregateMetric value ->
        value.getHitCount() == count && value.getDuration() == count * duration * 2
      })

    cleanup:
    aggregator.close()

    where:
    count << [10, 100]
  }

  def "aggregate spans with same HTTP endpoint together, separate different endpoints"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, true)
    aggregator.start()

    when: "publish multiple spans with same endpoint"
    CountDownLatch latch = new CountDownLatch(1)
    int count = 5
    long duration = 100
    for (int i = 0; i < count; ++i) {
      aggregator.publish([
        new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration, HTTP_OK)
        .setTag(SPAN_KIND, "server")
        .setTag("http.method", "GET")
        .setTag("http.endpoint", "/api/users/:id")
      ])
    }
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then: "should aggregate into single metric"
    latchTriggered
    1 * writer.startBucket(1, _, _)
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "server",
      [],
      "GET",
      "/api/users/:id"
      ), { AggregateMetric value ->
        value.getHitCount() == count && value.getDuration() == count * duration
      })
    1 * writer.finishBucket() >> { latch.countDown() }

    when: "publish spans with different endpoints"
    CountDownLatch latch2 = new CountDownLatch(1)
    aggregator.publish([
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration, HTTP_OK)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "GET")
      .setTag("http.endpoint", "/api/users/:id"),
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration * 2, HTTP_OK)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "GET")
      .setTag("http.endpoint", "/api/orders/:id"),
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration * 3, HTTP_OK)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "POST")
      .setTag("http.endpoint", "/api/users/:id")
    ])
    aggregator.report()
    def latchTriggered2 = latch2.await(2, SECONDS)

    then: "should create separate metrics for each endpoint/method combination"
    latchTriggered2
    1 * writer.startBucket(3, _, _)
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "server",
      [],
      "GET",
      "/api/users/:id"
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration
      })
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "server",
      [],
      "GET",
      "/api/orders/:id"
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration * 2
      })
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "server",
      [],
      "POST",
      "/api/users/:id"
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration * 3
      })
    1 * writer.finishBucket() >> { latch2.countDown() }

    cleanup:
    aggregator.close()
  }

  def "create separate metrics for different HTTP method/endpoint/status combinations"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, true)
    aggregator.start()

    when: "publish spans with different combinations"
    CountDownLatch latch = new CountDownLatch(1)
    long duration = 100
    aggregator.publish([
      // Same endpoint, different methods
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration, 200)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "GET")
      .setTag("http.endpoint", "/api/users/:id"),
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration * 2, 200)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "POST")
      .setTag("http.endpoint", "/api/users/:id"),
      // Same method/endpoint, different status
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration * 3, 404)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "GET")
      .setTag("http.endpoint", "/api/users/:id"),
      // Different endpoint
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration * 4, 200)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "GET")
      .setTag("http.endpoint", "/api/orders/:id")
    ])
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then: "should create 4 separate metrics"
    latchTriggered
    1 * writer.startBucket(4, _, _)
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      200,
      false,
      false,
      "server",
      [],
      "GET",
      "/api/users/:id"
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration
      })
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      200,
      false,
      false,
      "server",
      [],
      "POST",
      "/api/users/:id"
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration * 2
      })
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      404,
      false,
      false,
      "server",
      [],
      "GET",
      "/api/users/:id"
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration * 3
      })
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      200,
      false,
      false,
      "server",
      [],
      "GET",
      "/api/orders/:id"
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration * 4
      })
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "handle spans without HTTP endpoint tags for backward compatibility"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, true)
    aggregator.start()

    when: "publish spans with and without HTTP tags"
    CountDownLatch latch = new CountDownLatch(1)
    long duration = 100
    aggregator.publish([
      // Span without HTTP tags (legacy behavior)
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration, 200)
      .setTag(SPAN_KIND, "server"),
      // Span with HTTP tags (new behavior)
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration * 2, 200)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "GET")
      .setTag("http.endpoint", "/api/users/:id")
    ])
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then: "should create separate metric keys for spans with and without HTTP tags"
    latchTriggered
    1 * writer.startBucket(2, _, _)
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      200,
      false,
      false,
      "server",
      [],
      null,
      null
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration
      })
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      200,
      false,
      false,
      "server",
      [],
      "GET",
      "/api/users/:id"
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration * 2
      })
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "gather the service name source when the span is published"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, false)
    aggregator.start()

    when: "publish spans with different service name source"
    CountDownLatch latch = new CountDownLatch(1)
    long duration = 100
    aggregator.publish([
      new SimpleSpan("service", "operation", "resource", "type", true, true, false, 0, duration, 200, false, 0, "source")
      .setTag(SPAN_KIND, "server"),
      new SimpleSpan("service", "operation", "resource", "type", true, true, false, 0, duration, 200, false, 0, null)
      .setTag(SPAN_KIND, "server"),
      new SimpleSpan("service", "operation", "resource", "type", true, true, false, 0, duration, 200, false, 0, "source")
      .setTag(SPAN_KIND, "server")
    ])
    aggregator.report()
    def latchTriggered = latch.await(2, SECONDS)

    then: "should create the different metric keys for spans with and without sources"
    latchTriggered
    1 * writer.startBucket(2, _, _)
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      "source",
      "type",
      200,
      false,
      false,
      "server",
      [],
      null,
      null
      ), { AggregateMetric value ->
        value.getHitCount() == 2 && value.getDuration() == 2 * duration
      })
    1 * writer.add(new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      200,
      false,
      false,
      "server",
      [],
      null,
      null
      ), { AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration
      })
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
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
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, reportingInterval, SECONDS, false)
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
        null,
        "type",
        HTTP_OK,
        false,
        false,
        "baz",
        [],
        null,
        null
        ), _) >> { MetricKey key, AggregateMetric value ->
          value.getHitCount() == 1 && value.getDuration() == duration
        }
    }
    0 * writer.add(new MetricKey(
      "resource",
      "service0",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      [],
      null,
      null
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
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, reportingInterval, SECONDS, false)
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
        null,
        "type",
        HTTP_OK,
        false,
        false,
        "baz",
        [],
        null,
        null
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
        null,
        "type",
        HTTP_OK,
        false,
        false,
        "baz",
        [],
        null,
        null
        ), { AggregateMetric value ->
          value.getHitCount() == 1 && value.getDuration() == duration
        })
    }
    0 * writer.add(new MetricKey(
      "resource",
      "service0",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "baz",
      [],
      null,
      null
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
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, reportingInterval, SECONDS, false)
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
        null,
        "type",
        HTTP_OK,
        false,
        false,
        "quux",
        [],
        null,
        null
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
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, 1, SECONDS, false)
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
        null,
        "type",
        HTTP_OK,
        false,
        true,
        "garply",
        [],
        null,
        null
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
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, 1, SECONDS, false)
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
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, 1, SECONDS, false)
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
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, 200, MILLISECONDS, false)
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
      features, HealthMetrics.NO_OP, sink, writer, maxAggregates, queueSize, 1, SECONDS, false)

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
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, false)
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
      null,
      "type",
      HTTP_OK,
      false,
      true,
      "",
      [],
      null,
      null
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 1 && aggregateMetric.getDuration() == 100
      })
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "should not change metric buckets when includeEndpointInMetrics is disabled"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, false)
    aggregator.start()

    when: "publishing spans with different http.method and http.endpoint"
    CountDownLatch latch = new CountDownLatch(1)
    aggregator.publish([
      new SimpleSpan("service", "operation", "resource", "type", false, true, false, 0, 100, HTTP_OK)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "GET")
      .setTag("http.endpoint", "/api/users/:id"),
      new SimpleSpan("service", "operation", "resource", "type", false, true, false, 0, 200, HTTP_OK)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "POST")
      .setTag("http.endpoint", "/api/orders"),
      new SimpleSpan("service", "operation", "resource", "type", false, true, false, 0, 150, HTTP_OK)
      .setTag(SPAN_KIND, "server")
    ])
    reportAndWaitUntilEmpty(aggregator)
    def latchTriggered = latch.await(2, SECONDS)

    then: "all spans should go to the same bucket (httpMethod and httpEndpoint are ignored)"
    latchTriggered
    1 * writer.startBucket(1, _, _)
    1 * writer.add(
      new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "server",
      [],
      null,
      null
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 3 && aggregateMetric.getTopLevelCount() == 3 && aggregateMetric.getDuration() == 450
      })
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "should separate metric buckets when includeEndpointInMetrics is enabled"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
    features.supportsMetrics() >> true
    features.peerTags() >> []
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      features, HealthMetrics.NO_OP, sink, writer, 10, queueSize, reportingInterval, SECONDS, true)
    aggregator.start()

    when: "publishing spans with different http.method and http.endpoint"
    CountDownLatch latch = new CountDownLatch(1)
    aggregator.publish([
      new SimpleSpan("service", "operation", "resource", "type", false, true, false, 0, 100, HTTP_OK)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "GET")
      .setTag("http.endpoint", "/api/users/:id"),
      new SimpleSpan("service", "operation", "resource", "type", false, true, false, 0, 200, HTTP_OK)
      .setTag(SPAN_KIND, "server")
      .setTag("http.method", "POST")
      .setTag("http.endpoint", "/api/orders"),
      new SimpleSpan("service", "operation", "resource", "type", false, true, false, 0, 150, HTTP_OK)
      .setTag(SPAN_KIND, "server")
    ])
    reportAndWaitUntilEmpty(aggregator)
    def latchTriggered = latch.await(2, SECONDS)

    then: "spans should go to separate buckets based on httpMethod and httpEndpoint"
    latchTriggered
    1 * writer.startBucket(3, _, _)
    1 * writer.add(
      new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "server",
      [],
      "GET",
      "/api/users/:id"
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 1 && aggregateMetric.getDuration() == 100
      })
    1 * writer.add(
      new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "server",
      [],
      "POST",
      "/api/orders"
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 1 && aggregateMetric.getDuration() == 200
      })
    1 * writer.add(
      new MetricKey(
      "resource",
      "service",
      "operation",
      null,
      "type",
      HTTP_OK,
      false,
      false,
      "server",
      [],
      null,
      null
      ), { AggregateMetric aggregateMetric ->
        aggregateMetric.getHitCount() == 1 && aggregateMetric.getTopLevelCount() == 1 && aggregateMetric.getDuration() == 150
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
