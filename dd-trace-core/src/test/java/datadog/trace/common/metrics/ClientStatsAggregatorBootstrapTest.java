package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.SpanKindFilter;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Coverage for the {@code ClientStatsAggregator} peer-tag schema bootstrap and reconcile paths.
 *
 * <ul>
 *   <li>{@link #bootstrapHappensOnceOnFirstPublish()} -- verifies the synchronized producer-side
 *       bootstrap runs exactly once and is skipped on subsequent publishes.
 *   <li>{@link #reconcileSkipsDeepCompareWhenStateMatches()} -- verifies the aggregator-thread
 *       reconcile's state-only fast path: when the cached schema's {@code state} matches {@code
 *       features.state()}, reconcile returns without calling {@code features.peerTags()}.
 *   <li>{@link #reconcileSurvivesStateChangeWhenTagsUnchanged()} -- verifies that when the
 *       discovery state hash changes but the tag set is identical, the schema continues to function
 *       correctly across cycles.
 *   <li>{@link #reconcileSwapsSchemaWhenTagSetChanges()} -- verifies the slow-path swap branch:
 *       when discovery refreshes with a new tag set, the cached schema is replaced and subsequent
 *       publishes see the new tags.
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
    when(features.state()).thenReturn("state-1");

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
    verify(features, times(1)).state();
    aggregator.close();
  }

  @Test
  void reconcileSkipsDeepCompareWhenStateMatches() throws Exception {
    // Two reporting cycles with the same (mocked-constant) discovery state -- the second
    // reconcile must short-circuit on the state compare and avoid touching peerTags().
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.<String>singleton("peer.hostname"));
    when(features.state()).thenReturn("state-1");

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

      // peerTags() is called only by bootstrap; both reconciles short-circuit on the state
      // fast path (cached state == features.state() == "state-1"), so neither reconcile reaches
      // the deep set compare. Total peerTags() calls: 1.
      verify(features, times(1)).peerTags();
      // state() is called by bootstrap (1) + each reconcile (2) = 3 total.
      verify(features, times(3)).state();
    } finally {
      aggregator.close();
    }
  }

  @Test
  void reconcileSurvivesStateChangeWhenTagsUnchanged() throws Exception {
    // Behavioral cross-check on the "set is unchanged, just update state" branch: discovery
    // refreshes (state hash moves) but the underlying tag set is identical. The aggregator must
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
    // State hash changes every reconcile -- forces reconcile into the slow path each time.
    when(features.state()).thenReturn("state-1", "state-2", "state-3");

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
      // producing buckets across the state-hash changes; if the schema had been broken by the
      // update-in-place path, the second cycle's flush would not have happened.
      verify(writer, times(2)).finishBucket();
      // Bootstrap (1) + two reconciles (2) -- each reconcile saw a state mismatch and went
      // through the deep compare, calling peerTags() once = 3 total.
      verify(features, times(3)).peerTags();
      verify(features, atLeastOnce()).state();
    } finally {
      aggregator.close();
    }
  }

  @Test
  void reconcileSwapsSchemaWhenTagSetChanges() throws Exception {
    // The reconcile slow-path's swap branch: discovery refreshes the state AND the tag set
    // grows. Cached schema is rebuilt and the volatile reference points at the new schema.
    // Verification is end-to-end -- we look at the AggregateEntry the writer receives. Pre-swap
    // the span snapshot was pinned to the old schema so only peer.hostname appears; post-swap a
    // new publish reads the new schema and the next flush carries both peer tags.
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    // peerTags() shape evolves across calls:
    //   - bootstrap reads {peer.hostname}
    //   - cycle 1 reconcile slow-path reads {peer.hostname, peer.service}
    //   - cycle 2 reconcile is state fast-path (no peerTags call)
    when(features.peerTags())
        .thenReturn(Collections.<String>singleton("peer.hostname"))
        .thenReturn(new LinkedHashSet<>(Arrays.asList("peer.hostname", "peer.service")));
    // state() evolves: bootstrap = "state-1", then changes to "state-2" for cycle 1's reconcile
    // (mismatch -> slow path), stable at "state-2" for cycle 2's reconcile (match -> fast path).
    when(features.state()).thenReturn("state-1", "state-2", "state-2");

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

      // Publish 1: snapshot pinned to the original {peer.hostname} schema. cycle 1's reconcile
      // will swap the cached schema BEFORE the flush, but this snapshot is already pinned so the
      // resulting AggregateEntry will still carry only peer.hostname.
      aggregator.publish(
          Collections.<CoreSpan<?>>singletonList(peerAggregationSpanWithBothPeerTags()));
      aggregator.report();
      assertTrue(cycle1.await(2, SECONDS));

      // Publish 2: now reads the post-swap schema {peer.hostname, peer.service} so the snapshot
      // captures both tag values. cycle 2's reconcile short-circuits on timestamp match.
      aggregator.publish(
          Collections.<CoreSpan<?>>singletonList(peerAggregationSpanWithBothPeerTags()));
      aggregator.report();
      assertTrue(cycle2.await(2, SECONDS));

      // Capture every AggregateEntry the writer saw across both cycles. Pre-swap snapshot has 1
      // peer tag, post-swap has 2.
      ArgumentCaptor<AggregateEntry> entryCaptor = ArgumentCaptor.forClass(AggregateEntry.class);
      verify(writer, times(2)).add(entryCaptor.capture());
      List<AggregateEntry> entries = entryCaptor.getAllValues();
      assertEquals(
          Collections.singletonList(UTF8BytesString.create("peer.hostname:localhost")),
          entries.get(0).getPeerTags(),
          "pre-swap snapshot should encode only peer.hostname");
      assertEquals(
          Arrays.asList(
              UTF8BytesString.create("peer.hostname:localhost"),
              UTF8BytesString.create("peer.service:billing")),
          entries.get(1).getPeerTags(),
          "post-swap snapshot should encode both peer.hostname and peer.service");

      // Bootstrap (1) + cycle 1 slow-path (1) -- cycle 2 is fast-path so doesn't reach peerTags().
      verify(features, times(2)).peerTags();
      verify(features, atLeastOnce()).state();
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
    when(span.getSpanKindString()).thenReturn("client");
    // peer.hostname tag is set so capturePeerTagValues fires for the bootstrapped schema.
    when(span.unsafeGetTag("peer.hostname")).thenReturn("localhost");
    return span;
  }

  /**
   * Variant of {@link #peerAggregationSpan()} that sets both {@code peer.hostname} and {@code
   * peer.service}. Used by {@link #reconcileSwapsSchemaWhenTagSetChanges()} where the schema
   * evolves from {@code {peer.hostname}} to {@code {peer.hostname, peer.service}} mid-test, and the
   * post-swap snapshot must be able to capture the newly-relevant tag value.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static CoreSpan<?> peerAggregationSpanWithBothPeerTags() {
    CoreSpan span = peerAggregationSpan();
    when(span.unsafeGetTag("peer.service")).thenReturn("billing");
    return span;
  }
}
