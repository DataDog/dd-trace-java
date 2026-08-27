package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.SpanKindFilter;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Coverage for publishing into a full inbox: the {@link datadog.common.queue.WorkQueue} reserves a
 * slot before it calls back, so a rejected span costs no tag extraction and no {@code SpanSnapshot}
 * allocation, and the rejection is reported to health metrics.
 */
class ClientStatsAggregatorInboxFullTest {

  @Test
  void publishFiresOnStatsInboxFullOnceInboxIsAtCapacity() {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.<String>emptySet());

    // Small inbox; the MPSC array backing rounds up to the next power of two, so use a power of
    // two directly. Note: we deliberately do NOT call aggregator.start() so the consumer thread
    // never drains -- snapshots accumulate in the inbox until capacity, then admission rejects.
    int queueSize = 8;
    ClientStatsAggregator aggregator =
        new ClientStatsAggregator(
            Collections.<String>emptySet(),
            features,
            healthMetrics,
            sink,
            writer,
            /* maxAggregates */ 16,
            queueSize,
            /* reportingInterval */ 10,
            SECONDS,
            /* includeEndpointInMetrics */ false);

    // Publish well past capacity. The first `queueSize` calls land in the inbox; the rest are
    // rejected.
    for (int i = 0; i < queueSize * 4; i++) {
      aggregator.publish(Collections.<CoreSpan<?>>singletonList(metricsEligibleSpan()));
    }

    verify(healthMetrics, atLeastOnce()).onStatsInboxFull();
    aggregator.close();
  }

  /** The point of the reserve-first admission: a rejected span is never turned into a snapshot. */
  @Test
  void publishBuildsNoSnapshotOnceInboxIsAtCapacity() {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    MetricWriter writer = mock(MetricWriter.class);
    Sink sink = mock(Sink.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(Collections.<String>emptySet());

    int queueSize = 8;
    ClientStatsAggregator aggregator =
        new ClientStatsAggregator(
            Collections.<String>emptySet(),
            features,
            healthMetrics,
            sink,
            writer,
            /* maxAggregates */ 16,
            queueSize,
            /* reportingInterval */ 10,
            SECONDS,
            /* includeEndpointInMetrics */ false);

    for (int i = 0; i < queueSize; i++) {
      aggregator.publish(Collections.<CoreSpan<?>>singletonList(metricsEligibleSpan()));
    }

    // A fresh span published into the now-full inbox: only the eligibility checks should touch it.
    CoreSpan<?> rejected = metricsEligibleSpan();
    aggregator.publish(Collections.<CoreSpan<?>>singletonList(rejected));

    verify(rejected, never()).getServiceName();
    verify(rejected, never()).getOperationName();
    verify(rejected, never()).getSpanKindString();
    aggregator.close();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static CoreSpan<?> metricsEligibleSpan() {
    CoreSpan span = mock(CoreSpan.class);
    when(span.isMeasured()).thenReturn(false);
    when(span.isTopLevel()).thenReturn(true);
    when(span.isKind(any(SpanKindFilter.class))).thenReturn(false);
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
}
