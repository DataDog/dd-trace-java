package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class ConflatingMetricAggregatorTest {

  private static final Set<String> EMPTY = new HashSet<>();
  private static final int HTTP_OK = 200;
  private static final long REPORTING_INTERVAL = 1L;
  private static final int QUEUE_SIZE = 256;

  private static void waitUntilEmpty(ConflatingMetricsAggregator aggregator) {
    int i = 0;
    while (!aggregator.inbox.isEmpty() && i++ < 100) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void reportAndWaitUntilEmpty(ConflatingMetricsAggregator aggregator) {
    waitUntilEmpty(aggregator);
    aggregator.report();
    waitUntilEmpty(aggregator);
  }

  @Test
  void shouldIgnoreTracesWithNoMeasuredSpans() throws Exception {
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            wellKnownTags,
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            10,
            QUEUE_SIZE,
            1,
            MILLISECONDS,
            false);
    aggregator.start();

    try {
      aggregator.publish(
          Collections.singletonList(
              new SimpleSpan("", "", "", "", false, false, false, 0, 0, HTTP_OK)));
      reportAndWaitUntilEmpty(aggregator);
      verify(sink, never()).accept(anyInt(), any());
    } finally {
      aggregator.close();
    }
  }

  @Test
  void shouldIgnoreTracesWithIgnoredResourceNames() throws Exception {
    String ignoredResourceName = "foo";
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");
    Set<String> ignoredResourceNames = new HashSet<>();
    ignoredResourceNames.add(ignoredResourceName);
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            wellKnownTags,
            ignoredResourceNames,
            features,
            HealthMetrics.NO_OP,
            sink,
            10,
            QUEUE_SIZE,
            1,
            MILLISECONDS,
            false);
    aggregator.start();

    try {
      aggregator.publish(
          Collections.singletonList(
              new SimpleSpan("", "", ignoredResourceName, "", true, true, false, 0, 0, HTTP_OK)));
      aggregator.publish(
          Collections.singletonList(
              new SimpleSpan(
                  "",
                  "",
                  UTF8BytesString.create(ignoredResourceName),
                  "",
                  true,
                  true,
                  false,
                  0,
                  0,
                  HTTP_OK)));
      aggregator.publish(
          Arrays.asList(
              new SimpleSpan("", "", ignoredResourceName, "", true, true, false, 0, 0, HTTP_OK),
              new SimpleSpan(
                  "",
                  "",
                  "measured, not ignored, but child of ignored, so should be ignored",
                  "",
                  true,
                  true,
                  false,
                  0,
                  0,
                  HTTP_OK)));
      reportAndWaitUntilEmpty(aggregator);
      verify(sink, never()).accept(anyInt(), any());
    } finally {
      aggregator.close();
    }
  }

  @Test
  void shouldBeResilientToNullResourceNames() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.emptySet());
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            10,
            QUEUE_SIZE,
            REPORTING_INTERVAL,
            SECONDS,
            false);
    aggregator.start();

    try {
      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(
          Collections.singletonList(
              new SimpleSpan(
                      "service", "operation", null, "type", false, true, false, 0, 100, HTTP_OK)
                  .setTag(SPAN_KIND, "baz")));
      aggregator.report();
      assertTrue(latch.await(2, SECONDS));

      verify(writer, times(1)).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1))
          .add(
              eq(
                  new MetricKey(
                      null,
                      "service",
                      "operation",
                      null,
                      "type",
                      HTTP_OK,
                      false,
                      false,
                      "baz",
                      Collections.emptyList(),
                      null,
                      null,
                      null)),
              any(AggregateMetric.class));
      verify(writer, times(1)).finishBucket();
    } finally {
      aggregator.close();
    }
  }

  @Test
  void unmeasuredTopLevelSpansHaveMetricsComputed() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.emptySet());
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            10,
            QUEUE_SIZE,
            REPORTING_INTERVAL,
            SECONDS,
            false);
    aggregator.start();

    try {
      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(
          Collections.singletonList(
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      false,
                      true,
                      false,
                      0,
                      100,
                      HTTP_OK)
                  .setTag(SPAN_KIND, "baz")));
      aggregator.report();
      assertTrue(latch.await(2, SECONDS));

      verify(writer, times(1)).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1))
          .add(
              eq(
                  new MetricKey(
                      "resource",
                      "service",
                      "operation",
                      null,
                      "type",
                      HTTP_OK,
                      false,
                      false,
                      "baz",
                      Collections.emptyList(),
                      null,
                      null,
                      null)),
              any(AggregateMetric.class));
      verify(writer, times(1)).finishBucket();
    } finally {
      aggregator.close();
    }
  }

  @TableTest({
    "scenario                | kind        | httpMethod | httpEndpoint      | statsComputed",
    "client                  | client      | null       | null              | true         ",
    "producer                | producer    | null       | null              | true         ",
    "consumer                | consumer    | null       | null              | true         ",
    "server UTF8             | server-utf8 | null       | null              | true         ",
    "internal                | internal    | null       | null              | false        ",
    "null kind               | null        | null       | null              | false        ",
    "server with method      | server      | GET        | /api/users/:id    | true         ",
    "server with method post | server      | POST       | /api/orders       | true         ",
    "server with delete      | server      | DELETE     | /api/products/:id | true         ",
    "client with endpoint    | client      | GET        | /external/api     | true         "
  })
  @ParameterizedTest(name = "[{index}] should compute stats for span kind {0}")
  void shouldComputeStatsForSpanKind(
      String kindStr, String httpMethod, String httpEndpoint, boolean statsComputed)
      throws Exception {
    Object kind = parseKind(kindStr);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.emptySet());
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            10,
            QUEUE_SIZE,
            REPORTING_INTERVAL,
            SECONDS,
            true);
    aggregator.start();

    try {
      CountDownLatch latch = new CountDownLatch(1);
      if (statsComputed) {
        doAnswer(
                invocation -> {
                  latch.countDown();
                  return null;
                })
            .when(writer)
            .finishBucket();
      }

      SimpleSpan span =
          new SimpleSpan(
              "service", "operation", "resource", "type", false, false, false, 0, 100, HTTP_OK);
      if (kind != null) {
        span.setTag(SPAN_KIND, kind);
      }
      if (httpMethod != null) {
        span.setTag("http.method", httpMethod);
      }
      if (httpEndpoint != null) {
        span.setTag("http.endpoint", httpEndpoint);
      }
      aggregator.publish(Collections.singletonList(span));
      aggregator.report();

      if (statsComputed) {
        assertTrue(latch.await(2, SECONDS));
        verify(writer, times(1)).startBucket(eq(1), anyLong(), anyLong());
        String resolvedKind = kind != null ? kind.toString() : null;
        verify(writer, times(1))
            .add(
                eq(
                    new MetricKey(
                        "resource",
                        "service",
                        "operation",
                        null,
                        "type",
                        HTTP_OK,
                        false,
                        false,
                        resolvedKind,
                        Collections.emptyList(),
                        httpMethod,
                        httpEndpoint,
                        null)),
                any(AggregateMetric.class));
        verify(writer, times(1)).finishBucket();
      } else {
        // wait for the report signal to be fully processed before verifying
        reportAndWaitUntilEmpty(aggregator);
        verify(writer, never()).startBucket(anyInt(), anyLong(), anyLong());
        verify(writer, never()).add(any(), any());
        verify(writer, never()).finishBucket();
      }
    } finally {
      aggregator.close();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 100})
  void aggregateRepetitiveSpans(int count) throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.emptySet());
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            10,
            QUEUE_SIZE,
            REPORTING_INTERVAL,
            SECONDS,
            false);
    long duration = 100;
    List<SimpleSpan> trace =
        Arrays.asList(
            new SimpleSpan(
                    "service",
                    "operation",
                    "resource",
                    "type",
                    true,
                    false,
                    false,
                    0,
                    duration,
                    HTTP_OK)
                .setTag(SPAN_KIND, "baz"),
            new SimpleSpan(
                    "service1",
                    "operation1",
                    "resource1",
                    "type",
                    false,
                    false,
                    false,
                    0,
                    0,
                    HTTP_OK)
                .setTag(SPAN_KIND, "baz"),
            new SimpleSpan(
                    "service2",
                    "operation2",
                    "resource2",
                    "type",
                    true,
                    false,
                    false,
                    0,
                    duration * 2,
                    HTTP_OK)
                .setTag(SPAN_KIND, "baz"));
    aggregator.start();

    try {
      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < count; ++i) {
        aggregator.publish(trace);
      }
      aggregator.report();
      assertTrue(latch.await(2, SECONDS));

      verify(writer, times(1)).finishBucket();
      verify(writer, times(1))
          .startBucket(eq(2), anyLong(), eq(SECONDS.toNanos(REPORTING_INTERVAL)));
      verify(writer, times(1))
          .add(
              eq(
                  new MetricKey(
                      "resource",
                      "service",
                      "operation",
                      null,
                      "type",
                      HTTP_OK,
                      false,
                      false,
                      "baz",
                      Collections.emptyList(),
                      null,
                      null,
                      null)),
              any(AggregateMetric.class));
      verify(writer, times(1))
          .add(
              eq(
                  new MetricKey(
                      "resource2",
                      "service2",
                      "operation2",
                      null,
                      "type",
                      HTTP_OK,
                      false,
                      false,
                      "baz",
                      Collections.emptyList(),
                      null,
                      null,
                      null)),
              any(AggregateMetric.class));
    } finally {
      aggregator.close();
    }
  }

  @Test
  void testLeastRecentlyWrittenToAggregateFlushedWhenSizeLimitExceeded() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.emptySet());
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            maxAggregates,
            QUEUE_SIZE,
            REPORTING_INTERVAL,
            SECONDS,
            false);
    long duration = 100;
    aggregator.start();

    try {
      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < 11; ++i) {
        aggregator.publish(
            Collections.singletonList(
                new SimpleSpan(
                        "service" + i,
                        "operation",
                        "resource",
                        "type",
                        false,
                        true,
                        false,
                        0,
                        duration,
                        HTTP_OK)
                    .setTag(SPAN_KIND, "baz")));
      }
      aggregator.report();
      assertTrue(latch.await(2, SECONDS));

      verify(writer, times(1))
          .startBucket(eq(10), anyLong(), eq(SECONDS.toNanos(REPORTING_INTERVAL)));
      for (int i = 1; i < 11; ++i) {
        verify(writer, times(1))
            .add(
                eq(
                    new MetricKey(
                        "resource",
                        "service" + i,
                        "operation",
                        null,
                        "type",
                        HTTP_OK,
                        false,
                        false,
                        "baz",
                        Collections.emptyList(),
                        null,
                        null,
                        null)),
                any(AggregateMetric.class));
      }
      verify(writer, never())
          .add(
              eq(
                  new MetricKey(
                      "resource",
                      "service0",
                      "operation",
                      null,
                      "type",
                      HTTP_OK,
                      false,
                      false,
                      "baz",
                      Collections.emptyList(),
                      null,
                      null,
                      null)),
              any(AggregateMetric.class));
      verify(writer, times(1)).finishBucket();
    } finally {
      aggregator.close();
    }
  }

  @Test
  void aggregateNotUpdatedInReportingIntervalNotReported() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.emptySet());
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            maxAggregates,
            QUEUE_SIZE,
            REPORTING_INTERVAL,
            SECONDS,
            false);
    long duration = 100;
    aggregator.start();

    try {
      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < 5; ++i) {
        aggregator.publish(
            Collections.singletonList(
                new SimpleSpan(
                        "service" + i,
                        "operation",
                        "resource",
                        "type",
                        false,
                        true,
                        false,
                        0,
                        duration,
                        HTTP_OK)
                    .setTag(SPAN_KIND, "baz")));
      }
      aggregator.report();
      assertTrue(latch.await(2, SECONDS));

      // all aggregates reported first cycle
      verify(writer, times(1))
          .startBucket(eq(5), anyLong(), eq(SECONDS.toNanos(REPORTING_INTERVAL)));
      for (int i = 0; i < 5; ++i) {
        verify(writer, times(1))
            .add(
                eq(
                    new MetricKey(
                        "resource",
                        "service" + i,
                        "operation",
                        null,
                        "type",
                        HTTP_OK,
                        false,
                        false,
                        "baz",
                        Collections.emptyList(),
                        null,
                        null,
                        null)),
                any(AggregateMetric.class));
      }
      verify(writer, times(1)).finishBucket();

      // Second cycle - only publish services 1-4
      org.mockito.Mockito.clearInvocations(writer);
      CountDownLatch latch2 = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch2.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 1; i < 5; ++i) {
        aggregator.publish(
            Collections.singletonList(
                new SimpleSpan(
                        "service" + i,
                        "operation",
                        "resource",
                        "type",
                        false,
                        true,
                        false,
                        0,
                        duration,
                        HTTP_OK)
                    .setTag(SPAN_KIND, "baz")));
      }
      aggregator.report();
      assertTrue(latch2.await(2, SECONDS));

      verify(writer, times(1))
          .startBucket(eq(4), anyLong(), eq(SECONDS.toNanos(REPORTING_INTERVAL)));
      verify(writer, never())
          .add(
              eq(
                  new MetricKey(
                      "resource",
                      "service0",
                      "operation",
                      null,
                      "type",
                      HTTP_OK,
                      false,
                      false,
                      "baz",
                      Collections.emptyList(),
                      null,
                      null,
                      null)),
              any(AggregateMetric.class));
    } finally {
      aggregator.close();
    }
  }

  @Test
  void whenNoAggregateIsUpdatedInReportingIntervalNothingIsReported() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.emptySet());
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            maxAggregates,
            QUEUE_SIZE,
            REPORTING_INTERVAL,
            SECONDS,
            false);
    long duration = 100;
    aggregator.start();

    try {
      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < 5; ++i) {
        aggregator.publish(
            Collections.singletonList(
                new SimpleSpan(
                        "service" + i,
                        "operation",
                        "resource",
                        "type",
                        false,
                        true,
                        false,
                        0,
                        duration,
                        HTTP_OK)
                    .setTag(SPAN_KIND, "quux")));
      }
      aggregator.report();
      assertTrue(latch.await(2, SECONDS));

      verify(writer, times(1))
          .startBucket(eq(5), anyLong(), eq(SECONDS.toNanos(REPORTING_INTERVAL)));

      // Second cycle - no updates
      reportAndWaitUntilEmpty(aggregator);
      // no additional invocations
      verify(writer, times(1)).finishBucket();
      verify(writer, times(1)).startBucket(anyInt(), anyLong(), anyLong());
    } finally {
      aggregator.close();
    }
  }

  @Test
  void shouldBeResilientToSerializationErrors() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.emptySet());
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            maxAggregates,
            QUEUE_SIZE,
            1,
            SECONDS,
            false);
    long duration = 100;
    aggregator.start();

    try {
      CountDownLatch latch = new CountDownLatch(1);
      doThrow(new IllegalArgumentException("something went wrong"))
          .when(writer)
          .startBucket(anyInt(), anyLong(), anyLong());
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .reset();

      for (int i = 0; i < 5; ++i) {
        aggregator.publish(
            Collections.singletonList(
                new SimpleSpan(
                    "service" + i,
                    "operation",
                    "resource",
                    "type",
                    false,
                    true,
                    false,
                    0,
                    duration,
                    HTTP_OK)));
      }
      assertTrue(latch.await(2, SECONDS));

      verify(writer, times(1)).reset();
    } finally {
      aggregator.close();
    }
  }

  @Test
  void forceFlushShouldNotBlockIfMetricsAreDisabled() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            maxAggregates,
            QUEUE_SIZE,
            1,
            SECONDS,
            false);
    aggregator.start();

    try {
      boolean flushed = aggregator.forceReport().get(10, SECONDS);
      assertFalse(flushed);
    } finally {
      aggregator.close();
    }
  }

  @Test
  void forceFlushShouldWaitForAggregatorToStart() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            maxAggregates,
            QUEUE_SIZE,
            1,
            SECONDS,
            false);

    try {
      CompletableFuture<Boolean> async =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return aggregator.forceReport().get();
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });

      assertThrows(TimeoutException.class, () -> async.get(3, SECONDS));

      aggregator.start();
      boolean flushed = async.get(3, TimeUnit.SECONDS);
      assertTrue(flushed);
    } finally {
      aggregator.close();
    }
  }

  @Test
  void shouldNotCountPartialSnapshotLongRunning() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            10,
            QUEUE_SIZE,
            REPORTING_INTERVAL,
            SECONDS,
            false);
    aggregator.start();

    try {
      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(
          Arrays.asList(
              new SimpleSpan(
                  "service",
                  "operation",
                  "resource",
                  "type",
                  true,
                  true,
                  false,
                  0,
                  100,
                  HTTP_OK,
                  true,
                  12345,
                  null),
              new SimpleSpan(
                  "service",
                  "operation",
                  "resource",
                  "type",
                  true,
                  true,
                  false,
                  0,
                  100,
                  HTTP_OK,
                  true,
                  0,
                  null)));
      aggregator.report();
      assertTrue(latch.await(2, SECONDS));

      verify(writer, times(1)).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1))
          .add(
              eq(
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
                      Collections.emptyList(),
                      null,
                      null,
                      null)),
              any(AggregateMetric.class));
      verify(writer, times(1)).finishBucket();
    } finally {
      aggregator.close();
    }
  }

  @Test
  void shouldIncludeGrpcStatusCodeInMetricKeyForRpcSpans() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.emptySet());
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            10,
            QUEUE_SIZE,
            REPORTING_INTERVAL,
            SECONDS,
            false);
    aggregator.start();

    try {
      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(
          Arrays.asList(
              new SimpleSpan(
                      "service",
                      "grpc.server",
                      "grpc.service/Method",
                      "rpc",
                      true,
                      false,
                      false,
                      0,
                      100,
                      0)
                  .setTag(SPAN_KIND, "server")
                  .setTag(InstrumentationTags.GRPC_STATUS_CODE, 0),
              new SimpleSpan(
                      "service",
                      "grpc.server",
                      "grpc.service/Method",
                      "rpc",
                      true,
                      false,
                      false,
                      0,
                      50,
                      0)
                  .setTag(SPAN_KIND, "server")
                  .setTag(InstrumentationTags.GRPC_STATUS_CODE, 5),
              new SimpleSpan(
                      "service", "http.request", "GET /api", "web", true, false, false, 0, 75, 200)
                  .setTag(SPAN_KIND, "server")));
      aggregator.report();
      assertTrue(latch.await(2, SECONDS));

      verify(writer, times(1)).startBucket(eq(3), anyLong(), anyLong());
      verify(writer, times(1))
          .add(
              eq(
                  new MetricKey(
                      "grpc.service/Method",
                      "service",
                      "grpc.server",
                      null,
                      "rpc",
                      0,
                      false,
                      false,
                      "server",
                      Collections.emptyList(),
                      null,
                      null,
                      "0")),
              any(AggregateMetric.class));
      verify(writer, times(1))
          .add(
              eq(
                  new MetricKey(
                      "grpc.service/Method",
                      "service",
                      "grpc.server",
                      null,
                      "rpc",
                      0,
                      false,
                      false,
                      "server",
                      Collections.emptyList(),
                      null,
                      null,
                      "5")),
              any(AggregateMetric.class));
      verify(writer, times(1))
          .add(
              eq(
                  new MetricKey(
                      "GET /api",
                      "service",
                      "http.request",
                      null,
                      "web",
                      200,
                      false,
                      false,
                      "server",
                      Collections.emptyList(),
                      null,
                      null,
                      null)),
              any(AggregateMetric.class));
      verify(writer, times(1)).finishBucket();
    } finally {
      aggregator.close();
    }
  }

  private static Object parseKind(String kindStr) {
    if (kindStr == null || kindStr.trim().equals("null")) return null;
    if (kindStr.trim().equals("server-utf8")) return UTF8BytesString.create("server");
    return kindStr.trim();
  }
}
