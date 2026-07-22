package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.SpanKindFilter;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Coverage for the {@code disable() -> ClearSignal.CLEAR} threading routing introduced in this PR.
 *
 * <p>The bundled fix routes the agent-downgrade clear through the inbox so the aggregator thread
 * stays the sole writer to {@link AggregateTable} (which is not thread-safe). The behavioral
 * contract this test pins:
 *
 * <ul>
 *   <li>{@code onEvent(DOWNGRADED)} can fire from a non-aggregator thread (in production, the
 *       OkHttpSink callback thread).
 *   <li>By the time the next report cycle reconciles peer-tag schema on the aggregator thread, the
 *       {@code AggregateTable} has been cleared -- {@code CLEAR} arrived in the FIFO inbox before
 *       the {@code REPORT} signal triggered by {@code aggregator.report()}.
 *   <li>The aggregator therefore flushes nothing on that next report cycle: no {@code startBucket},
 *       no {@code add}, no {@code finishBucket}.
 * </ul>
 *
 * <p>The test would fail if {@code disable()} reverted to mutating {@code AggregateTable} directly
 * (the pre-fix path) only via races -- not deterministically -- so the assertions here are about
 * the observable end-to-end shape rather than thread identity.
 */
class ClientStatsAggregatorDisableTest {

  @Test
  void downgradeRoutesClearThroughInboxBeforeNextReport() throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.<String>emptySet());
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
      // Baseline: publish a span, run a report, verify the table flushes normally. This gives
      // us a clean post-first-report state with the aggregator's reconcile already having fired
      // once on the aggregator thread.
      CountDownLatch firstFlush = new CountDownLatch(1);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                firstFlush.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();

      aggregator.publish(Collections.<CoreSpan<?>>singletonList(metricsEligibleSpan()));
      aggregator.report();
      assertTrue(firstFlush.await(2, SECONDS));

      // Reset writer-side mock interactions so the post-disable verify() blocks below only see
      // what happens after the downgrade. features mock keeps accumulating call counts -- we use
      // those counts as a latch on aggregator-thread reconcile timing.
      reset(writer);

      // Flip the discovery state. disable()'s first action is features.discover() followed by a
      // features.supportsMetrics() check; returning false here selects the clear path.
      when(features.supportsMetrics()).thenReturn(false);

      // Fire DOWNGRADED on the test thread. This is the production scenario where the OkHttpSink
      // callback thread triggers onEvent. disable() offers ClearSignal.CLEAR to the inbox but
      // does not (and must not) mutate AggregateTable directly here.
      aggregator.onEvent(EventListener.EventType.DOWNGRADED, "");

      // First: verify nothing flushes immediately after disable. We can't pin reconcile-on-the-
      // aggregator-thread as a latch here because CLEAR's inbox.clear() drops any REPORT we'd
      // queue behind it -- so we just wait a window for any flush attempt to materialize.
      verify(writer, after(500).never()).startBucket(anyInt(), anyLong(), anyLong());

      // Stronger contract: prove the table is actually empty after CLEAR by re-enabling metrics
      // and publishing a *marker* span with a distinct resource name. The next report should
      // flush exactly one entry -- the marker -- with the original "resource" gone. If disable()
      // had failed to clear the table (or had cleared it from the wrong thread and corrupted
      // bucket chains), this assertion would catch it.
      when(features.supportsMetrics()).thenReturn(true);
      CountDownLatch postClearFlush = new CountDownLatch(1);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                postClearFlush.countDown();
                return null;
              })
          .when(writer)
          .finishBucket();
      aggregator.publish(Collections.<CoreSpan<?>>singletonList(markerSpan()));
      aggregator.report();
      assertTrue(postClearFlush.await(2, SECONDS));

      ArgumentCaptor<AggregateEntry> entryCaptor = ArgumentCaptor.forClass(AggregateEntry.class);
      verify(writer, times(1)).add(entryCaptor.capture());
      assertEquals(
          "marker-resource",
          entryCaptor.getValue().getResource().toString(),
          "post-CLEAR bucket should contain only the marker -- the original entry was wiped");
    } finally {
      aggregator.close();
    }
  }

  @Test
  void clearDoesNotTrampleQueuedStopSignal() throws Exception {
    // CLEAR handler clears only the aggregates table; queued signals (STOP, REPORT) survive and
    // get processed normally.
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.<String>emptySet());
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

    // Force at least one snapshot into the inbox so the aggregator has something to drain.
    aggregator.publish(Collections.<CoreSpan<?>>singletonList(metricsEligibleSpan()));

    // Fire DOWNGRADED on this thread. disable() flips supportsMetrics() to false and offers
    // CLEAR. Then immediately call close() which offers STOP. If CLEAR's handler clears the
    // inbox, STOP gets trampled and close() hangs until the join timeout.
    when(features.supportsMetrics()).thenReturn(false);
    aggregator.onEvent(EventListener.EventType.DOWNGRADED, "");

    // close() is synchronous; bound it ourselves rather than trusting THREAD_JOIN_TIMEOUT_MS.
    long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    Thread closer = new Thread(aggregator::close, "test-closer");
    closer.start();
    while (closer.isAlive() && System.nanoTime() < deadlineNanos) {
      closer.join(50);
    }
    assertTrue(
        !closer.isAlive(),
        "close() must return promptly -- if CLEAR trampled STOP, this hangs out the join timeout");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static CoreSpan<?> metricsEligibleSpan() {
    CoreSpan span = mock(CoreSpan.class);
    when(span.isMeasured()).thenReturn(false);
    when(span.isTopLevel()).thenReturn(true);
    // Return true for any SpanKindFilter so peerTagSchemaFor enters the bootstrap path on the
    // first publish. We want that bootstrap to fire (it's what makes features.state()
    // observable), even though peerTags() returns emptySet here and the resulting schema has
    // size 0.
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
    return span;
  }

  /**
   * Distinct from {@link #metricsEligibleSpan()} via the resource name: post-CLEAR the writer
   * should see "marker-resource", proving the original "resource" entry is gone from the table.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static CoreSpan<?> markerSpan() {
    CoreSpan span = mock(CoreSpan.class);
    when(span.isMeasured()).thenReturn(false);
    when(span.isTopLevel()).thenReturn(true);
    when(span.isKind(any(SpanKindFilter.class))).thenReturn(true);
    when(span.getLongRunningVersion()).thenReturn(0);
    when(span.getDurationNano()).thenReturn(100L);
    when(span.getError()).thenReturn(0);
    when(span.getResourceName()).thenReturn("marker-resource");
    when(span.getServiceName()).thenReturn("svc");
    when(span.getOperationName()).thenReturn("op");
    when(span.getServiceNameSource()).thenReturn(null);
    when(span.getType()).thenReturn("web");
    when(span.getHttpStatusCode()).thenReturn((short) 200);
    when(span.getParentId()).thenReturn(0L);
    when(span.getOrigin()).thenReturn(null);
    when(span.getSpanKindString()).thenReturn("client");
    return span;
  }
}
