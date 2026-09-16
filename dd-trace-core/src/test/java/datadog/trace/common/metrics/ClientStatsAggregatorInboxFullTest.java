package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.SpanKindFilter;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the inbox-full fast-path in {@code ClientStatsAggregator.publish}: when the
 * producer-side inbox is at capacity, the next {@code publish} call short-circuits before any tag
 * extraction or {@code SpanSnapshot} allocation and reports {@code onStatsInboxFull()} to health
 * metrics.
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

    // Small inbox; jctools MPSC array queue rounds up to the next power of two, so use a power of
    // two directly. Note: we deliberately do NOT call aggregator.start() so the consumer thread
    // never drains -- snapshots accumulate in the inbox until capacity, then the next publish hits
    // the size-vs-capacity fast path.
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

    // Publish well past capacity. The first `queueSize` calls land in the inbox; subsequent calls
    // see size >= capacity and hit the fast path.
    for (int i = 0; i < queueSize * 4; i++) {
      aggregator.publish(Collections.<CoreSpan<?>>singletonList(metricsEligibleSpan()));
    }

    verify(healthMetrics, atLeastOnce()).onStatsInboxFull();
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
