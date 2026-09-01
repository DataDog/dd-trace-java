package datadog.trace.common.metrics;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class ClientStatsAggregatorTest {

  private static final int HTTP_OK = 200;
  private static final long REPORTING_INTERVAL = 1;
  private static final int QUEUE_SIZE = 256;

  @Test
  void shouldIgnoreTracesWithNoMeasuredSpans() throws Exception {
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");
    try (ClientStatsAggregator aggregator =
        new ClientStatsAggregator(
            wellKnownTags,
            emptySet(),
            AdditionalTagsSchema.EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            10,
            QUEUE_SIZE,
            1,
            MILLISECONDS,
            false)) {
      aggregator.start();

      aggregator.publish(
          singletonList(new SimpleSpan("", "", "", "", false, false, false, 0, 0, HTTP_OK)));

      waitUntilAggregatorIsEmpty(aggregator);
      clearInvocations(sink);
      aggregator.forceReport().get(2, SECONDS);

      verifyNoInteractions(sink);
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
    Set<String> ignoredResources = Collections.singleton(ignoredResourceName);
    try (ClientStatsAggregator aggregator =
        new ClientStatsAggregator(
            wellKnownTags,
            ignoredResources,
            AdditionalTagsSchema.EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            10,
            QUEUE_SIZE,
            1,
            MILLISECONDS,
            false)) {
      aggregator.start();
      clearInvocations(sink);

      // publish ignored resource names
      aggregator.publish(
          singletonList(
              new SimpleSpan("", "", ignoredResourceName, "", true, true, false, 0, 0, HTTP_OK)));
      aggregator.publish(
          singletonList(
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
      aggregator.forceReport().get(2, SECONDS);

      verifyNoInteractions(sink);
    }
  }

  @Test
  void shouldBeResilientToNullResourceNames() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, false)) {
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);
      AggregateEntry expectedEntry =
          AggregateEntryTestUtils.of(
              null,
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "baz",
              emptyList(),
              null,
              null,
              null);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                assertTrue(AggregateEntryTestUtils.equals(e, expectedEntry));
                assertEquals(1, e.getHitCount());
                assertEquals(1, e.getTopLevelCount());
                assertEquals(100, e.getDuration());
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(
          singletonList(
              new SimpleSpan(
                      "service", "operation", null, "type", false, true, false, 0, 100, HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "baz")));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1)).add(any(AggregateEntry.class));
      verify(writer).finishBucket();
    }
  }

  @Test
  void unmeasuredTopLevelSpansHaveMetricsComputed() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, false)) {
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);
      AggregateEntry expectedEntry =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "baz",
              emptyList(),
              null,
              null,
              null);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                assertTrue(AggregateEntryTestUtils.equals(e, expectedEntry));
                assertEquals(1, e.getHitCount());
                assertEquals(1, e.getTopLevelCount());
                assertEquals(100, e.getDuration());
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(
          singletonList(
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
                  .setTag(Tags.SPAN_KIND, "baz")));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1)).add(any(AggregateEntry.class));
      verify(writer).finishBucket();
    }
  }

  @TableTest({
    "scenario                        | kind        | httpMethod | httpEndpoint      | statsComputed",
    "client                          | client      |            |                   | true         ",
    "producer                        | producer    |            |                   | true         ",
    "consumer                        | consumer    |            |                   | true         ",
    "server (UTF8BytesString)        | UTF8.server |            |                   | true         ",
    "internal                        | internal    |            |                   | false        ",
    "null kind                       |             |            |                   | false        ",
    "server GET /api/users/:id       | server      | GET        | /api/users/:id    | true         ",
    "server POST /api/orders         | server      | POST       | /api/orders       | true         ",
    "server DELETE /api/products/:id | server      | DELETE     | /api/products/:id | true         ",
    "client GET /external/api        | client      | GET        | /external/api     | true         "
  })
  void shouldComputeStatsForSpanKind(
      @ConvertWith(StringOrUTF8ByteStringConverter.class) CharSequence kind,
      String httpMethod,
      String httpEndpoint,
      boolean statsComputed)
      throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, true)) {
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);
      if (statsComputed) {
        AggregateEntry expectedEntry =
            AggregateEntryTestUtils.of(
                "resource",
                "service",
                "operation",
                null,
                "type",
                HTTP_OK,
                false,
                false,
                kind == null ? null : kind.toString(),
                emptyList(),
                httpMethod,
                httpEndpoint,
                null);
        doAnswer(
                invocation -> {
                  AggregateEntry e = invocation.getArgument(0);
                  assertTrue(AggregateEntryTestUtils.equals(e, expectedEntry));
                  assertEquals(1, e.getHitCount());
                  assertEquals(0, e.getTopLevelCount());
                  assertEquals(100, e.getDuration());
                  return null;
                })
            .when(writer)
            .add(any(AggregateEntry.class));
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
                  "service", "operation", "resource", "type", false, false, false, 0, 100, HTTP_OK)
              .setTag(Tags.SPAN_KIND, kind);
      if (httpMethod != null) {
        span.setTag("http.method", httpMethod);
      }
      if (httpEndpoint != null) {
        span.setTag("http.endpoint", httpEndpoint);
      }
      aggregator.publish(singletonList(span));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      assertEquals(statsComputed, latchTriggered);
      verify(writer, times(statsComputed ? 1 : 0)).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(statsComputed ? 1 : 0)).add(any(AggregateEntry.class));
      verify(writer, times(statsComputed ? 1 : 0)).finishBucket();
    }
  }

  static class StringOrUTF8ByteStringConverter implements ArgumentConverter {
    public static final String UTF_8_PREFIX = "UTF8.";

    @Override
    public Object convert(Object source, ParameterContext context)
        throws ArgumentConversionException {
      if (source == null) {
        return null;
      }
      String s = source.toString();
      if (s.isEmpty()) return null;
      if (s.startsWith(UTF_8_PREFIX)) {
        return UTF8BytesString.create(s.substring(UTF_8_PREFIX.length()));
      }
      return s;
    }
  }

  @Test
  void shouldCreateSeparateBucketsForDistinctPeerTagValues() throws Exception {
    // Peer-tag NAMES are configured per-tracer and stable for the duration of a trace publish;
    // peer-tag VALUES vary per-span. Two spans with the same names but different values should
    // produce two distinct aggregate buckets.
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags())
        .thenReturn(new LinkedHashSet<>(Arrays.asList("country", "georegion")));

    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, false)) {
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);

      AggregateEntry expectedFranceEntry =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "client",
              Arrays.asList(
                  UTF8BytesString.create("country:france"),
                  UTF8BytesString.create("georegion:europe")),
              null,
              null,
              null);
      AggregateEntry expectedGermanyEntry =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "client",
              Arrays.asList(
                  UTF8BytesString.create("country:germany"),
                  UTF8BytesString.create("georegion:europe")),
              null,
              null,
              null);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                if (AggregateEntryTestUtils.equals(e, expectedFranceEntry)
                    || AggregateEntryTestUtils.equals(e, expectedGermanyEntry)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(0, e.getTopLevelCount());
                  assertEquals(100, e.getDuration());
                } else {
                  throw new AssertionError("Unexpected AggregateEntry in add(): " + e);
                }
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
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
                      false,
                      false,
                      0,
                      100,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "client")
                  .setTag("country", "france")
                  .setTag("georegion", "europe"),
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      true,
                      false,
                      false,
                      0,
                      100,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "client")
                  .setTag("country", "germany")
                  .setTag("georegion", "europe")));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      assertTrue(latchTriggered);
      verify(writer, times(1)).startBucket(eq(2), anyLong(), anyLong());
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedFranceEntry)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedGermanyEntry)));
      verify(writer, times(1)).finishBucket();
    }
  }

  @TableTest({
    "scenario | kind     | expectedPeerTagStrings                              ",
    "client   | client   | ['peer.hostname:localhost', '_dd.base_service:test']",
    "internal | internal | ['_dd.base_service:test']                           ",
    "server   | server   | []                                                  "
  })
  void shouldAggregateTheRightPeerTagsForKind(
      @ConvertWith(StringOrUTF8ByteStringConverter.class) CharSequence kind,
      List<String> expectedPeerTagStrings)
      throws Exception {
    List<UTF8BytesString> expectedPeerTags = new ArrayList<>();
    for (String tag : expectedPeerTagStrings) {
      expectedPeerTags.add(UTF8BytesString.create(tag));
    }
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags())
        .thenReturn(new LinkedHashSet<>(Arrays.asList("peer.hostname", "_dd.base_service")));
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, false)) {
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);
      AggregateEntry expectedEntry =
          AggregateEntryTestUtils.of(
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
              null,
              null);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                assertTrue(AggregateEntryTestUtils.equals(e, expectedEntry));
                assertEquals(1, e.getHitCount());
                assertEquals(0, e.getTopLevelCount());
                assertEquals(100, e.getDuration());
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(
          singletonList(
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      true,
                      false,
                      false,
                      0,
                      100,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, kind)
                  .setTag("peer.hostname", "localhost")
                  .setTag("_dd.base_service", UTF8BytesString.create("test"))));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1)).add(any(AggregateEntry.class));
      verify(writer).finishBucket();
    }
  }

  @TableTest({
    "scenario                   | measured | topLevel | topLevelCount",
    "measured no top level      | true     | false    | 0            ",
    "measured and top level     | true     | true     | 1            ",
    "not measured but top level | false    | true     | 1            "
  })
  void measuredSpansDoNotContributeToTopLevelCount(
      boolean measured, boolean topLevel, int topLevelCount) throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, false)) {
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);
      AggregateEntry expectedEntry =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "baz",
              emptyList(),
              null,
              null,
              null);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                assertTrue(AggregateEntryTestUtils.equals(e, expectedEntry));
                assertEquals(1, e.getHitCount());
                assertEquals(topLevelCount, e.getTopLevelCount());
                assertEquals(100, e.getDuration());
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(
          singletonList(
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      measured,
                      topLevel,
                      false,
                      0,
                      100,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "baz")));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1)).add(any(AggregateEntry.class));
      verify(writer).finishBucket();
    }
  }

  @TableTest({"scenario    | count", "count = 10  | 10   ", "count = 100 | 100  "})
  void aggregateRepetitiveSpans(int count) throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, false)) {
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
                  .setTag(Tags.SPAN_KIND, "baz"),
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
                  .setTag(Tags.SPAN_KIND, "baz"),
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
                  .setTag(Tags.SPAN_KIND, "baz"));
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);
      AggregateEntry expectedEntry1 =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "baz",
              emptyList(),
              null,
              null,
              null);
      AggregateEntry expectedEntry2 =
          AggregateEntryTestUtils.of(
              "resource2",
              "service2",
              "operation2",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "baz",
              emptyList(),
              null,
              null,
              null);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                if (AggregateEntryTestUtils.equals(e, expectedEntry1)) {
                  assertEquals(count, e.getHitCount());
                  assertEquals(count * duration, e.getDuration());
                } else if (AggregateEntryTestUtils.equals(e, expectedEntry2)) {
                  assertEquals(count, e.getHitCount());
                  assertEquals(count * duration * 2, e.getDuration());
                } else {
                  throw new AssertionError("Unexpected AggregateEntry in add()");
                }
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
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
      boolean latchTriggered = latch.await(2, SECONDS);

      // metrics should be conflated
      assertTrue(latchTriggered);
      verify(writer).finishBucket();
      verify(writer).startBucket(eq(2), anyLong(), eq(SECONDS.toNanos(REPORTING_INTERVAL)));
      verify(writer, times(1)).add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedEntry1)));
      verify(writer, times(1)).add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedEntry2)));
    }
  }

  @Test
  void aggregateSpansWithSameHttpEndpointTogetherSeparateDifferentEndpoints() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, true)) {
      aggregator.start();

      // Cycle 1: publish multiple spans with same endpoint
      int count = 5;
      long duration = 100;
      CountDownLatch latch = new CountDownLatch(1);
      CountDownLatch latch2 = new CountDownLatch(1);

      AggregateEntry expectedGetUsers =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "server",
              emptyList(),
              "GET",
              "/api/users/:id",
              null);
      AggregateEntry expectedGetOrders =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "server",
              emptyList(),
              "GET",
              "/api/orders/:id",
              null);
      AggregateEntry expectedPostUsers =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "server",
              emptyList(),
              "POST",
              "/api/users/:id",
              null);

      AtomicInteger cycle = new AtomicInteger(1);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                if (cycle.get() == 1) {
                  // should aggregate into single metric
                  assertTrue(AggregateEntryTestUtils.equals(e, expectedGetUsers));
                  assertEquals(count, e.getHitCount());
                  assertEquals(count * duration, e.getDuration());
                } else {
                  // separate metrics for each endpoint/method combination
                  if (AggregateEntryTestUtils.equals(e, expectedGetUsers)) {
                    assertEquals(1, e.getHitCount());
                    assertEquals(duration, e.getDuration());
                  } else if (AggregateEntryTestUtils.equals(e, expectedGetOrders)) {
                    assertEquals(1, e.getHitCount());
                    assertEquals(duration * 2, e.getDuration());
                  } else if (AggregateEntryTestUtils.equals(e, expectedPostUsers)) {
                    assertEquals(1, e.getHitCount());
                    assertEquals(duration * 3, e.getDuration());
                  } else {
                    throw new AssertionError("Unexpected AggregateEntry in cycle 2 add()");
                  }
                }
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                cycle.incrementAndGet();
                latch.countDown();
                return null;
              })
          .doAnswer(
              invocation -> {
                latch2.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < count; ++i) {
        aggregator.publish(
            singletonList(
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
                    .setTag(Tags.SPAN_KIND, "server")
                    .setTag("http.method", "GET")
                    .setTag("http.endpoint", "/api/users/:id")));
      }
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      // should aggregate into single metric
      assertTrue(latchTriggered);
      verify(writer, times(1)).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedGetUsers)));

      // publish spans with different endpoints
      aggregator.publish(
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
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "GET")
                  .setTag("http.endpoint", "/api/users/:id"),
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      true,
                      false,
                      false,
                      0,
                      duration * 2,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "GET")
                  .setTag("http.endpoint", "/api/orders/:id"),
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      true,
                      false,
                      false,
                      0,
                      duration * 3,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "POST")
                  .setTag("http.endpoint", "/api/users/:id")));
      aggregator.report();
      boolean latchTriggered2 = latch2.await(2, SECONDS);

      // should create separate metrics for each endpoint/method combination
      assertTrue(latchTriggered2);
      verify(writer, times(1)).startBucket(eq(3), anyLong(), anyLong());
      verify(writer, times(2))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedGetUsers)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedGetOrders)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedPostUsers)));
      verify(writer, times(2)).finishBucket();
    }
  }

  @Test
  void createSeparateMetricsForDifferentHttpMethodEndpointStatusCombinations() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, true)) {
      aggregator.start();

      // publish spans with different combinations
      CountDownLatch latch = new CountDownLatch(1);
      long duration = 100;

      AggregateEntry expectedGet200Users =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              200,
              false,
              false,
              "server",
              emptyList(),
              "GET",
              "/api/users/:id",
              null);
      AggregateEntry expectedPost200Users =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              200,
              false,
              false,
              "server",
              emptyList(),
              "POST",
              "/api/users/:id",
              null);
      AggregateEntry expectedGet404Users =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              404,
              false,
              false,
              "server",
              emptyList(),
              "GET",
              "/api/users/:id",
              null);
      AggregateEntry expectedGet200Orders =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              200,
              false,
              false,
              "server",
              emptyList(),
              "GET",
              "/api/orders/:id",
              null);

      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                if (AggregateEntryTestUtils.equals(e, expectedGet200Users)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(duration, e.getDuration());
                } else if (AggregateEntryTestUtils.equals(e, expectedPost200Users)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(duration * 2, e.getDuration());
                } else if (AggregateEntryTestUtils.equals(e, expectedGet404Users)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(duration * 3, e.getDuration());
                } else if (AggregateEntryTestUtils.equals(e, expectedGet200Orders)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(duration * 4, e.getDuration());
                } else {
                  throw new AssertionError("Unexpected AggregateEntry in add()");
                }
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(
          Arrays.asList(
              // Same endpoint, different methods
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
                      200)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "GET")
                  .setTag("http.endpoint", "/api/users/:id"),
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      true,
                      false,
                      false,
                      0,
                      duration * 2,
                      200)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "POST")
                  .setTag("http.endpoint", "/api/users/:id"),
              // Same method/endpoint, different status
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      true,
                      false,
                      false,
                      0,
                      duration * 3,
                      404)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "GET")
                  .setTag("http.endpoint", "/api/users/:id"),
              // Different endpoint
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      true,
                      false,
                      false,
                      0,
                      duration * 4,
                      200)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "GET")
                  .setTag("http.endpoint", "/api/orders/:id")));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      // should create 4 separate metrics
      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(4), anyLong(), anyLong());
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedGet200Users)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedPost200Users)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedGet404Users)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedGet200Orders)));
      verify(writer).finishBucket();
    }
  }

  @Test
  void handleSpansWithoutHttpEndpointTagsForBackwardCompatibility() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, true)) {
      aggregator.start();

      // publish spans with and without HTTP tags
      CountDownLatch latch = new CountDownLatch(1);
      long duration = 100;

      AggregateEntry expectedNoHttpTags =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              200,
              false,
              false,
              "server",
              emptyList(),
              null,
              null,
              null);
      AggregateEntry expectedWithHttpTags =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              200,
              false,
              false,
              "server",
              emptyList(),
              "GET",
              "/api/users/:id",
              null);

      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                if (AggregateEntryTestUtils.equals(e, expectedNoHttpTags)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(duration, e.getDuration());
                } else if (AggregateEntryTestUtils.equals(e, expectedWithHttpTags)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(duration * 2, e.getDuration());
                } else {
                  throw new AssertionError("Unexpected AggregateEntry in add()");
                }
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(
          Arrays.asList(
              // Span without HTTP tags (legacy behavior)
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
                      200)
                  .setTag(Tags.SPAN_KIND, "server"),
              // Span with HTTP tags (new behavior)
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      true,
                      false,
                      false,
                      0,
                      duration * 2,
                      200)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "GET")
                  .setTag("http.endpoint", "/api/users/:id")));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      // should create separate metric keys for spans with and without HTTP tags
      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(2), anyLong(), anyLong());
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedNoHttpTags)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedWithHttpTags)));
      verify(writer).finishBucket();
    }
  }

  @Test
  void gatherTheServiceNameSourceWhenTheSpanIsPublished() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, false)) {
      aggregator.start();

      // publish spans with different service name source
      CountDownLatch latch = new CountDownLatch(1);
      long duration = 100;

      AggregateEntry expectedWithSource =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              "source",
              "type",
              200,
              false,
              false,
              "server",
              emptyList(),
              null,
              null,
              null);
      AggregateEntry expectedWithoutSource =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              200,
              false,
              false,
              "server",
              emptyList(),
              null,
              null,
              null);

      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                if (AggregateEntryTestUtils.equals(e, expectedWithSource)) {
                  assertEquals(2, e.getHitCount());
                  assertEquals(2 * duration, e.getDuration());
                } else if (AggregateEntryTestUtils.equals(e, expectedWithoutSource)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(duration, e.getDuration());
                } else {
                  throw new AssertionError("Unexpected AggregateEntry in add()");
                }
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
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
                      duration,
                      200,
                      false,
                      0,
                      "source")
                  .setTag(Tags.SPAN_KIND, "server"),
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      true,
                      true,
                      false,
                      0,
                      duration,
                      200,
                      false,
                      0,
                      null)
                  .setTag(Tags.SPAN_KIND, "server"),
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      true,
                      true,
                      false,
                      0,
                      duration,
                      200,
                      false,
                      0,
                      "source")
                  .setTag(Tags.SPAN_KIND, "server")));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      // should create the different metric keys for spans with and without sources
      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(2), anyLong(), anyLong());
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedWithSource)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedWithoutSource)));
      verify(writer).finishBucket();
    }
  }

  @Test
  void newAggregatesBeyondSizeLimitAreDroppedWhenNoStaleEntriesCanBeEvicted() throws Exception {
    // The table only evicts entries with hitCount == 0 to make room. When all entries are live
    // (all have been recorded against), an over-cap insert drops the new key rather than evicting
    // an established one. This protects the data we've already collected from a burst of new keys.
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(
            features, HealthMetrics.NO_OP, sink, writer, maxAggregates, false)) {
      long duration = 100;
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);

      AggregateEntry expectedDropped =
          AggregateEntryTestUtils.of(
              "resource",
              "service10",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "baz",
              emptyList(),
              null,
              null,
              null);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                assertEquals(1, e.getHitCount());
                assertEquals(duration, e.getDuration());
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < 11; ++i) {
        aggregator.publish(
            singletonList(
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
                    .setTag(Tags.SPAN_KIND, "baz")));
      }
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      // the established service0..service9 are reported; service10 is dropped
      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(10), anyLong(), eq(SECONDS.toNanos(REPORTING_INTERVAL)));
      for (int i = 0; i < 10; ++i) {
        AggregateEntry expected =
            AggregateEntryTestUtils.of(
                "resource",
                "service" + i,
                "operation",
                null,
                "type",
                HTTP_OK,
                false,
                false,
                "baz",
                emptyList(),
                null,
                null,
                null);
        verify(writer, times(1)).add(argThat(e -> AggregateEntryTestUtils.equals(e, expected)));
      }
      verify(writer, never()).add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedDropped)));
      verify(writer).finishBucket();
    }
  }

  @Test
  void shouldReportDroppedAggregateToHealthMetricsOnLruEviction() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, healthMetrics, sink, writer, maxAggregates, false)) {
      long duration = 100;
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < maxAggregates + 1; ++i) {
        aggregator.publish(
            singletonList(
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
                    .setTag(Tags.SPAN_KIND, "baz")));
      }
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      assertTrue(latchTriggered);
      verify(writer).finishBucket();
      verify(healthMetrics).onStatsAggregateDropped();
    }
  }

  @Test
  void shouldNotReportDroppedAggregateWhenEvictedEntryWasAlreadyFlushed() throws Exception {
    int maxAggregates = 5;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, healthMetrics, sink, writer, maxAggregates, false)) {
      aggregator.start();

      // fill cache and flush — entries are cleared (hitCount=0) but stay in the LRU
      CountDownLatch latch1 = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch1.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < maxAggregates; ++i) {
        aggregator.publish(
            singletonList(
                new SimpleSpan(
                        "service" + i,
                        "operation",
                        "resource",
                        "type",
                        false,
                        true,
                        false,
                        0,
                        100,
                        HTTP_OK)
                    .setTag(Tags.SPAN_KIND, "baz")));
      }
      aggregator.report();
      latch1.await(2, SECONDS);

      verify(writer, times(1)).finishBucket();

      // publish new distinct spans — LRU evicts the cleared entries before the next report
      CountDownLatch latch2 = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                latch2.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = maxAggregates; i < maxAggregates * 2; ++i) {
        aggregator.publish(
            singletonList(
                new SimpleSpan(
                        "service" + i,
                        "operation",
                        "resource",
                        "type",
                        false,
                        true,
                        false,
                        0,
                        100,
                        HTTP_OK)
                    .setTag(Tags.SPAN_KIND, "baz")));
      }
      aggregator.report();
      latch2.await(2, SECONDS);

      // no drop metric because all evicted entries had hitCount=0 (already reported)
      verify(writer, times(2)).finishBucket();
      verify(healthMetrics, never()).onStatsAggregateDropped();
    }
  }

  @Test
  void aggregateNotUpdatedInReportingIntervalNotReported() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(
            features, HealthMetrics.NO_OP, sink, writer, maxAggregates, false)) {
      long duration = 100;
      aggregator.start();

      // first cycle
      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                assertEquals(1, e.getHitCount());
                assertEquals(duration, e.getDuration());
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < 5; ++i) {
        aggregator.publish(
            singletonList(
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
                    .setTag(Tags.SPAN_KIND, "baz")));
      }
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      // all aggregates should be reported
      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(5), anyLong(), eq(SECONDS.toNanos(REPORTING_INTERVAL)));
      for (int i = 0; i < 5; ++i) {
        AggregateEntry expected =
            AggregateEntryTestUtils.of(
                "resource",
                "service" + i,
                "operation",
                null,
                "type",
                HTTP_OK,
                false,
                false,
                "baz",
                emptyList(),
                null,
                null,
                null);
        verify(writer, times(1)).add(argThat(e -> AggregateEntryTestUtils.equals(e, expected)));
      }
      verify(writer, times(1)).finishBucket();

      // second cycle - service0 not updated
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
            singletonList(
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
                    .setTag(Tags.SPAN_KIND, "baz")));
      }
      aggregator.report();
      boolean latchTriggered2 = latch2.await(2, SECONDS);

      // aggregate not updated in cycle is not reported
      assertTrue(latchTriggered2);
      verify(writer).startBucket(eq(4), anyLong(), eq(SECONDS.toNanos(REPORTING_INTERVAL)));
      for (int i = 1; i < 5; ++i) {
        AggregateEntry expected =
            AggregateEntryTestUtils.of(
                "resource",
                "service" + i,
                "operation",
                null,
                "type",
                HTTP_OK,
                false,
                false,
                "baz",
                emptyList(),
                null,
                null,
                null);
        verify(writer, times(2)).add(argThat(e -> AggregateEntryTestUtils.equals(e, expected)));
      }
      AggregateEntry expectedService0 =
          AggregateEntryTestUtils.of(
              "resource",
              "service0",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "baz",
              emptyList(),
              null,
              null,
              null);
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedService0)));
      verify(writer, times(2)).finishBucket();
    }
  }

  @Test
  void whenNoAggregateIsUpdatedInReportingIntervalNothingIsReported() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(
            features, HealthMetrics.NO_OP, sink, writer, maxAggregates, false)) {
      long duration = 100;
      aggregator.start();

      // first cycle
      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                assertEquals(1, e.getHitCount());
                assertEquals(duration, e.getDuration());
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < 5; ++i) {
        aggregator.publish(
            singletonList(
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
                    .setTag(Tags.SPAN_KIND, "quux")));
      }
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      // all aggregates should be reported
      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(5), anyLong(), eq(SECONDS.toNanos(REPORTING_INTERVAL)));
      for (int i = 0; i < 5; ++i) {
        AggregateEntry expected =
            AggregateEntryTestUtils.of(
                "resource",
                "service" + i,
                "operation",
                null,
                "type",
                HTTP_OK,
                false,
                false,
                "quux",
                emptyList(),
                null,
                null,
                null);
        verify(writer, times(1)).add(argThat(e -> AggregateEntryTestUtils.equals(e, expected)));
      }
      verify(writer, times(1)).finishBucket();

      // second cycle - no updates at all
      waitUntilAggregatorIsEmpty(aggregator);
      clearInvocations(writer);
      aggregator.forceReport().get(2, SECONDS);

      // =aggregate not updated in cycle is not reported
      verify(writer, never()).startBucket(anyInt(), anyLong(), anyLong());
      verify(writer, never()).add(any(AggregateEntry.class));
      verify(writer, never()).finishBucket();
    }
  }

  @Test
  void shouldReportPeriodically() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, maxAggregates, 1, SECONDS, false)) {
      long duration = 100;
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                assertEquals(1, e.getHitCount());
                assertEquals(duration, e.getDuration());
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      for (int i = 0; i < 5; ++i) {
        aggregator.publish(
            singletonList(
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
                        HTTP_OK,
                        true)
                    .setTag(Tags.SPAN_KIND, "garply")));
      }
      boolean latchTriggered = latch.await(2, SECONDS);

      // all aggregates should be reported
      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(5), anyLong(), eq(SECONDS.toNanos(1)));
      for (int i = 0; i < 5; ++i) {
        AggregateEntry expected =
            AggregateEntryTestUtils.of(
                "resource",
                "service" + i,
                "operation",
                null,
                "type",
                HTTP_OK,
                false,
                true,
                "garply",
                emptyList(),
                null,
                null,
                null);
        verify(writer, times(1)).add(argThat(e -> AggregateEntryTestUtils.equals(e, expected)));
      }
      verify(writer, times(1)).finishBucket();
    }
  }

  @Test
  void shouldBeResilientToSerializationErrors() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, maxAggregates, 1, SECONDS, false)) {
      long duration = 100;
      aggregator.start();

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
            singletonList(
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
      boolean latchTriggered = latch.await(2, SECONDS);

      // writer should be reset if reporting fails
      assertTrue(latchTriggered);
      verify(writer).startBucket(anyInt(), anyLong(), anyLong());
      verify(writer).reset();
    }
  }

  @Test
  void forceFlushShouldNotBlockIfMetricsAreDisabled() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, maxAggregates, 1, SECONDS, false)) {
      aggregator.start();

      Boolean flushed = aggregator.forceReport().get(10, SECONDS);

      assertNotNull(flushed);
      assertFalse(flushed);
    }
  }

  @Test
  void shouldStartEvenIfTheAgentIsNotAvailable() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(false);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, 10, 200, MILLISECONDS, false)) {
      List<SimpleSpan> spans =
          singletonList(
              new SimpleSpan(
                  "service", "operation", "resource", "type", false, true, false, 0, 10, HTTP_OK));
      aggregator.start();

      // metrics not available
      aggregator.publish(spans);
      Thread.sleep(1_000);

      // no writer calls
      verifyNoInteractions(writer);

      // re-enable metrics
      when(features.supportsMetrics()).thenReturn(true);
      aggregator.publish(spans);
      Thread.sleep(1_000);

      // writer called at least once
      verify(writer, atLeastOnce()).startBucket(anyInt(), anyLong(), anyLong());
    }
  }

  @Test
  void forceFlushShouldWaitForAggregatorToStart() throws Exception {
    int maxAggregates = 10;
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, maxAggregates, 1, SECONDS, false)) {

      // call forceReport before start
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

      // start aggregator
      aggregator.start();
      Boolean flushed = async.get(3, TimeUnit.SECONDS);

      assertNotNull(flushed);
      assertTrue(flushed);
    }
  }

  @Test
  void shouldNotCountPartialSnapshotLongRunning() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, false)) {
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);
      AggregateEntry expectedEntry =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              true,
              "",
              emptyList(),
              null,
              null,
              null);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                assertTrue(AggregateEntryTestUtils.equals(e, expectedEntry));
                assertEquals(1, e.getHitCount());
                assertEquals(1, e.getTopLevelCount());
                assertEquals(100, e.getDuration());
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
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
                  12345),
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
                  0)));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1)).add(any(AggregateEntry.class));
      verify(writer).finishBucket();
    }
  }

  @Test
  void shouldNotChangeMetricBucketsWhenIncludeEndpointInMetricsIsDisabled() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, false)) {
      aggregator.start();

      // publishing spans with different http.method and http.endpoint
      CountDownLatch latch = new CountDownLatch(1);
      AggregateEntry expectedEntry =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "server",
              emptyList(),
              null,
              null,
              null);
      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                assertTrue(AggregateEntryTestUtils.equals(e, expectedEntry));
                assertEquals(3, e.getHitCount());
                assertEquals(3, e.getTopLevelCount());
                assertEquals(450, e.getDuration());
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
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
                      false,
                      true,
                      false,
                      0,
                      100,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "GET")
                  .setTag("http.endpoint", "/api/users/:id"),
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      false,
                      true,
                      false,
                      0,
                      200,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "POST")
                  .setTag("http.endpoint", "/api/orders"),
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      false,
                      true,
                      false,
                      0,
                      150,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "server")));
      aggregator.forceReport().get(2, SECONDS);
      boolean latchTriggered = latch.await(0, SECONDS);

      // all spans should go to the same bucket (httpMethod and httpEndpoint are ignored)
      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1)).add(any(AggregateEntry.class));
      verify(writer).finishBucket();
    }
  }

  @Test
  void shouldSeparateMetricBucketsWhenIncludeEndpointInMetricsIsEnabled() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, true)) {
      aggregator.start();

      // publishing spans with different http.method and http.endpoint
      CountDownLatch latch = new CountDownLatch(1);
      AggregateEntry expectedGetUsers =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "server",
              emptyList(),
              "GET",
              "/api/users/:id",
              null);
      AggregateEntry expectedPostOrders =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "server",
              emptyList(),
              "POST",
              "/api/orders",
              null);
      AggregateEntry expectedNoHttp =
          AggregateEntryTestUtils.of(
              "resource",
              "service",
              "operation",
              null,
              "type",
              HTTP_OK,
              false,
              false,
              "server",
              emptyList(),
              null,
              null,
              null);

      doAnswer(
              invocation -> {
                AggregateEntry e = invocation.getArgument(0);
                if (AggregateEntryTestUtils.equals(e, expectedGetUsers)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(1, e.getTopLevelCount());
                  assertEquals(100, e.getDuration());
                } else if (AggregateEntryTestUtils.equals(e, expectedPostOrders)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(1, e.getTopLevelCount());
                  assertEquals(200, e.getDuration());
                } else if (AggregateEntryTestUtils.equals(e, expectedNoHttp)) {
                  assertEquals(1, e.getHitCount());
                  assertEquals(1, e.getTopLevelCount());
                  assertEquals(150, e.getDuration());
                } else {
                  throw new AssertionError("Unexpected AggregateEntry in add()");
                }
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
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
                      false,
                      true,
                      false,
                      0,
                      100,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "GET")
                  .setTag("http.endpoint", "/api/users/:id"),
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      false,
                      true,
                      false,
                      0,
                      200,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag("http.method", "POST")
                  .setTag("http.endpoint", "/api/orders"),
              new SimpleSpan(
                      "service",
                      "operation",
                      "resource",
                      "type",
                      false,
                      true,
                      false,
                      0,
                      150,
                      HTTP_OK)
                  .setTag(Tags.SPAN_KIND, "server")));
      aggregator.forceReport().get(2, SECONDS);
      boolean latchTriggered = latch.await(0, SECONDS);

      // spans should go to separate buckets based on httpMethod and httpEndpoint
      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(3), anyLong(), anyLong());
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedGetUsers)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedPostOrders)));
      verify(writer, times(1)).add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedNoHttp)));
      verify(writer).finishBucket();
    }
  }

  @Test
  void shouldIncludeGrpcStatusCodeInMetricKeyForRpcSpans() throws Exception {
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, sink, writer, false)) {
      aggregator.start();

      CountDownLatch latch = new CountDownLatch(1);
      AggregateEntry expectedGrpcStatus0 =
          AggregateEntryTestUtils.of(
              "grpc.service/Method",
              "service",
              "grpc.server",
              null,
              "rpc",
              0,
              false,
              false,
              "server",
              emptyList(),
              null,
              null,
              "0");
      AggregateEntry expectedGrpcStatus5 =
          AggregateEntryTestUtils.of(
              "grpc.service/Method",
              "service",
              "grpc.server",
              null,
              "rpc",
              0,
              false,
              false,
              "server",
              emptyList(),
              null,
              null,
              "5");
      AggregateEntry expectedHttpSpan =
          AggregateEntryTestUtils.of(
              "GET /api",
              "service",
              "http.request",
              null,
              "web",
              200,
              false,
              false,
              "server",
              emptyList(),
              null,
              null,
              null);

      doAnswer(invocation -> null).when(writer).add(any(AggregateEntry.class));
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
                  .setTag(Tags.SPAN_KIND, "server")
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
                  .setTag(Tags.SPAN_KIND, "server")
                  .setTag(InstrumentationTags.GRPC_STATUS_CODE, 5),
              new SimpleSpan(
                      "service", "http.request", "GET /api", "web", true, false, false, 0, 75, 200)
                  .setTag(Tags.SPAN_KIND, "server")));
      aggregator.report();
      boolean latchTriggered = latch.await(2, SECONDS);

      assertTrue(latchTriggered);
      verify(writer).startBucket(eq(3), anyLong(), anyLong());
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedGrpcStatus0)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedGrpcStatus5)));
      verify(writer, times(1))
          .add(argThat(e -> AggregateEntryTestUtils.equals(e, expectedHttpSpan)));
      verify(writer).finishBucket();
    }
  }

  @Test
  void cardinalityLimitsResetBetweenReportCycles() throws Exception {
    List<AggregateEntry> cycle1Entries = new ArrayList<>();
    List<AggregateEntry> cycle2Entries = new ArrayList<>();
    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    try (ClientStatsAggregator aggregator =
        createClientStatsAggregator(features, HealthMetrics.NO_OP, sink, writer, 256, false)) {
      aggregator.start();

      // publish SERVICE+1 distinct services to fill and overflow the cardinality budget
      doAnswer(
              invocation -> {
                cycle1Entries.add(invocation.getArgument(0));
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch1.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();
      for (int i = 0; i <= MetricCardinalityLimits.SERVICE; i++) {
        aggregator.publish(
            singletonList(
                new SimpleSpan(
                    "svc-" + i, "op", "resource", "web", false, true, false, 0, 100, HTTP_OK)));
      }
      aggregator.report();
      latch1.await(2, SECONDS);

      // the overflow service maps to the tracer_blocked_value sentinel
      verify(writer).startBucket(eq(MetricCardinalityLimits.SERVICE + 1), anyLong(), anyLong());
      verify(writer, times(1)).finishBucket();
      assertEquals(
          1,
          cycle1Entries.stream()
              .filter(e -> e.getService().toString().equals("tracer_blocked_value"))
              .count());

      // publish the overflow service in the next cycle after the cardinality reset
      clearInvocations(writer);
      doAnswer(
              invocation -> {
                cycle2Entries.add(invocation.getArgument(0));
                return null;
              })
          .when(writer)
          .add(any(AggregateEntry.class));
      doAnswer(
              invocation -> {
                latch2.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();
      String overflowServiceName = "svc-" + MetricCardinalityLimits.SERVICE;
      aggregator.publish(
          singletonList(
              new SimpleSpan(
                  overflowServiceName,
                  "op",
                  "resource",
                  "web",
                  false,
                  true,
                  false,
                  0,
                  100,
                  HTTP_OK)));
      aggregator.report();
      latch2.await(2, SECONDS);

      // after reset the overflow service name is accepted as a real entry
      verify(writer).startBucket(eq(1), anyLong(), anyLong());
      verify(writer, times(1)).add(any(AggregateEntry.class));
      verify(writer, times(1)).finishBucket();
      assertEquals(1, cycle2Entries.size());
      assertEquals(overflowServiceName, cycle2Entries.get(0).getService().toString());
    }
  }

  private void waitUntilAggregatorIsEmpty(ClientStatsAggregator aggregator)
      throws InterruptedException {
    int i = 0;
    while (!aggregator.isEmpty() && i++ < 100) {
      Thread.sleep(10);
    }
  }

  private static ClientStatsAggregator createClientStatsAggregator(
      DDAgentFeaturesDiscovery features,
      HealthMetrics healthMetrics,
      Sink sink,
      MetricWriter writer,
      int maxAggregates,
      boolean includeEndpointInMetrics) {
    return new ClientStatsAggregator(
        emptySet(),
        features,
        healthMetrics,
        sink,
        writer,
        maxAggregates,
        QUEUE_SIZE,
        REPORTING_INTERVAL,
        SECONDS,
        includeEndpointInMetrics);
  }

  private static ClientStatsAggregator createClientStatsAggregator(
      DDAgentFeaturesDiscovery features,
      Sink sink,
      MetricWriter writer,
      int maxAggregates,
      long reportingInterval,
      TimeUnit timeUnit,
      boolean includeEndpointInMetrics) {
    return new ClientStatsAggregator(
        emptySet(),
        features,
        HealthMetrics.NO_OP,
        sink,
        writer,
        maxAggregates,
        QUEUE_SIZE,
        reportingInterval,
        timeUnit,
        includeEndpointInMetrics);
  }

  private static ClientStatsAggregator createClientStatsAggregator(
      DDAgentFeaturesDiscovery features,
      Sink sink,
      MetricWriter writer,
      boolean includeEndpointInMetrics) {
    return createClientStatsAggregator(
        features, HealthMetrics.NO_OP, sink, writer, 10, includeEndpointInMetrics);
  }
}
