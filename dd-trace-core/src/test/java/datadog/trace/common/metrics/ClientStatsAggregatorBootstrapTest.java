package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.SpanKindFilter;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the {@code ClientStatsAggregator} peer-tag schema bootstrap and reconcile
 * paths.
 *
 * <ul>
 *   <li>{@link #bootstrapHappensOnceOnFirstPublish()} -- verifies the synchronized producer-side
 *       bootstrap runs exactly once and is skipped on subsequent publishes.
 *   <li>{@link #reconcileSkipsDeepCompareWhenTimestampMatches()} -- verifies the aggregator-thread
 *       reconcile's timestamp-only fast path: when the cached schema's {@code lastTimeDiscovered}
 *       matches {@code features.getLastTimeDiscovered()}, reconcile returns without calling {@code
 *       features.peerTags()}.
 *   <li>{@link #reconcileSurvivesTimestampBumpWhenTagsUnchanged()} -- verifies that when the
 *       discovery timestamp changes but the tag set is identical, the schema continues to function
 *       correctly across cycles.
 * </ul>
 */
class ClientStatsAggregatorBootstrapTest {

  @Test
  void bootstrapHappensOnceOnFirstPublish() {
    // Producer-side bootstrap is synchronized; we want to confirm only the first publish
    // queries features and subsequent publishes hit the cached schema.
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.<String>singleton("peer.hostname"));
    when(features.getLastTimeDiscovered()).thenReturn(1000L);

    ClientStatsAggregator aggregator =
        new ClientStatsAggregator(
            Collections.<String>emptySet(),
            features,
            healthMetrics,
            sink,
            writer,
            /* maxAggregates */ 16,
            /* queueSize */ 64,
            /* reportingInterval */ 10,
            SECONDS,
            /* includeEndpointInMetrics */ false);

    // Do not start the aggregator thread -- reconcile must not run, only bootstrap.
    aggregator.publish(Collections.<CoreSpan<?>>singletonList(peerAggregationSpan()));
    aggregator.publish(Collections.<CoreSpan<?>>singletonList(peerAggregationSpan()));
    aggregator.publish(Collections.<CoreSpan<?>>singletonList(peerAggregationSpan()));

    // Bootstrap is the only path that queries features for peer-tag schema, and it runs
    // exactly once across three publishes.
    verify(features, times(1)).peerTags();
    verify(features, times(1)).getLastTimeDiscovered();
    aggregator.close();
  }

  @Test
  void reconcileSkipsDeepCompareWhenTimestampMatches() throws Exception {
    // Two reporting cycles with the same (mocked-constant) discovery timestamp -- the second
    // reconcile must short-circuit on the timestamp compare and avoid touching peerTags().
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.<String>singleton("peer.hostname"));
    when(features.getLastTimeDiscovered()).thenReturn(1000L);

    ClientStatsAggregator aggregator =
        new ClientStatsAggregator(
            Collections.<String>emptySet(),
            features,
            healthMetrics,
            sink,
            writer,
            /* maxAggregates */ 16,
            /* queueSize */ 64,
            /* reportingInterval */ 10,
            SECONDS,
            /* includeEndpointInMetrics */ false);
    aggregator.start();
    try {
      CountDownLatch cycle1 = new CountDownLatch(1);
      CountDownLatch cycle2 = new CountDownLatch(1);
      // Both reports flush a bucket; the cycle1/cycle2 countdowns synchronize the test thread
      // with the aggregator thread's per-cycle completion.
      org.mockito.Mockito.doAnswer(
              invocation -> {
                cycle1.countDown();
                return null;
              })
          .doAnswer(
              invocation -> {
                cycle2.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(Collections.<CoreSpan<?>>singletonList(peerAggregationSpan()));
      aggregator.report();
      assertTrue(cycle1.await(2, SECONDS));

      aggregator.publish(Collections.<CoreSpan<?>>singletonList(peerAggregationSpan()));
      aggregator.report();
      assertTrue(cycle2.await(2, SECONDS));

      // peerTags() is called only by bootstrap; both reconciles short-circuit on the timestamp
      // fast path (cached lastTimeDiscovered == features.getLastTimeDiscovered() == 1000L), so
      // neither reconcile reaches the deep set compare. Total peerTags() calls: 1.
      verify(features, times(1)).peerTags();
      // getLastTimeDiscovered() is called by bootstrap (1) + each reconcile (2) = 3 total.
      verify(features, times(3)).getLastTimeDiscovered();
    } finally {
      aggregator.close();
    }
  }

  @Test
  void reconcileSurvivesTimestampBumpWhenTagsUnchanged() throws Exception {
    // Behavioral cross-check on the "set is unchanged, just bump timestamp" branch: discovery
    // refreshes (timestamp moves) but the underlying tag set is identical. The aggregator must
    // continue producing valid buckets for the same logical peer tag across cycles.
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    // peerTags() returns content-equal sets across calls -- the reconcile slow path's
    // hasSameTagsAs check should return true.
    when(features.peerTags())
        .thenReturn(new LinkedHashSet<>(Collections.<String>singleton("peer.hostname")))
        .thenReturn(new LinkedHashSet<>(Collections.<String>singleton("peer.hostname")))
        .thenReturn(new LinkedHashSet<>(Collections.<String>singleton("peer.hostname")));
    // Timestamp bumps every reconcile -- forces reconcile into the slow path each time.
    when(features.getLastTimeDiscovered()).thenReturn(1L, 2L, 3L);

    ClientStatsAggregator aggregator =
        new ClientStatsAggregator(
            Collections.<String>emptySet(),
            features,
            healthMetrics,
            sink,
            writer,
            /* maxAggregates */ 16,
            /* queueSize */ 64,
            /* reportingInterval */ 10,
            SECONDS,
            /* includeEndpointInMetrics */ false);
    aggregator.start();
    try {
      CountDownLatch cycle1 = new CountDownLatch(1);
      CountDownLatch cycle2 = new CountDownLatch(1);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                cycle1.countDown();
                return null;
              })
          .doAnswer(
              invocation -> {
                cycle2.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(Collections.<CoreSpan<?>>singletonList(peerAggregationSpan()));
      aggregator.report();
      assertTrue(cycle1.await(2, SECONDS));

      aggregator.publish(Collections.<CoreSpan<?>>singletonList(peerAggregationSpan()));
      aggregator.report();
      assertTrue(cycle2.await(2, SECONDS));

      // Both cycles flushed (both latches counted down via writer.finishBucket). The schema kept
      // producing buckets across the timestamp bumps; if the schema had been broken by the
      // bump-in-place path, the second cycle's flush would not have happened.
      verify(writer, times(2)).finishBucket();
      // Bootstrap (1) + two reconciles (2) -- each reconcile saw a timestamp mismatch and went
      // through the deep compare, calling peerTags() once = 3 total.
      verify(features, times(3)).peerTags();
      verify(features, atLeastOnce()).getLastTimeDiscovered();
    } finally {
      aggregator.close();
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static CoreSpan<?> peerAggregationSpan() {
    CoreSpan span = mock(CoreSpan.class);
    when(span.isMeasured()).thenReturn(false);
    when(span.isTopLevel()).thenReturn(true);
    // Return true for any SpanKindFilter -- shouldComputeMetric will see METRICS_ELIGIBLE_KINDS
    // match, and peerTagSchemaFor will see PEER_AGGREGATION_KINDS match (checked first), which
    // routes the span through the bootstrap path.
    when(span.isKind(any(SpanKindFilter.class))).thenReturn(true);
    when(span.getLongRunningVersion()).thenReturn(0);
    when(span.getDurationNano()).thenReturn(100L);
    when(span.getError()).thenReturn(0);
    when(span.getResourceName()).thenReturn("resource");
    when(span.getServiceName()).thenReturn("svc");
    when(span.getOperationName()).thenReturn("op");
    when(span.getServiceNameSource()).thenReturn(null);
    when(span.getType()).thenReturn("web");
    when(span.getHttpStatusCode()).thenReturn((short) 200);
    when(span.getParentId()).thenReturn(0L);
    when(span.getOrigin()).thenReturn(null);
    when(span.unsafeGetTag(eq(Tags.SPAN_KIND), any(CharSequence.class))).thenReturn("client");
    // peer.hostname tag is set so capturePeerTagValues fires for the bootstrapped schema.
    when(span.unsafeGetTag("peer.hostname")).thenReturn("localhost");
    return span;
  }
}
