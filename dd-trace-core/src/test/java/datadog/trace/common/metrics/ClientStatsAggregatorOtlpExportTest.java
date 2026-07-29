package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.SpanKindFilter;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.otlp.metrics.OtlpStatsMetricWriter;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Coverage for the OTLP-export aggregation path added alongside {@link OtlpStatsMetricWriter}. The
 * OTLP path (selected because the injected writer {@code instanceof OtlpStatsMetricWriter}) probes
 * every known gRPC status-code convention with no span-type gate, unlike the native v0.6 path's
 * single-key {@code rpc.grpc.status_code} lookup gated on {@code rpc}-typed spans.
 */
class ClientStatsAggregatorOtlpExportTest {

  @Test
  void grpcStatusExtractedFromGrpcTypedSpanOnOtlpPath() throws Exception {
    OtlpStatsMetricWriter writer = mock(OtlpStatsMetricWriter.class);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.peerTags()).thenReturn(Collections.<String>emptySet());
    Sink sink = mock(Sink.class);

    ClientStatsAggregator aggregator =
        new ClientStatsAggregator(
            Collections.<String>emptySet(),
            features,
            HealthMetrics.NO_OP,
            sink,
            writer,
            /* maxAggregates */ 16,
            /* queueSize */ 16,
            /* reportingInterval */ 10,
            SECONDS,
            /* includeEndpointInMetrics */ false);
    aggregator.start();
    try {
      // A span typed "grpc" (not "rpc") carrying grpc.status.code -- a key + type combo the native
      // v0.6 path would not surface. On the OTLP path it must still be extracted.
      aggregator.publish(Collections.<CoreSpan<?>>singletonList(grpcSpan("grpc.status.code", "0")));
      aggregator.forceReport().get(5, SECONDS);

      ArgumentCaptor<AggregateEntry> captor = ArgumentCaptor.forClass(AggregateEntry.class);
      verify(writer).add(captor.capture());
      assertEquals("0", captor.getValue().getGrpcStatusCode().toString());
    } finally {
      aggregator.close();
    }
  }

  /** A metrics-eligible, top-level span typed {@code grpc} carrying a single tag. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static CoreSpan<?> grpcSpan(String tagKey, String tagValue) {
    CoreSpan span = mock(CoreSpan.class);
    when(span.isMeasured()).thenReturn(false);
    when(span.isTopLevel()).thenReturn(true);
    when(span.isKind(any(SpanKindFilter.class))).thenReturn(false);
    when(span.getLongRunningVersion()).thenReturn(0);
    when(span.getDurationNano()).thenReturn(SECONDS.toNanos(1));
    when(span.getError()).thenReturn(0);
    when(span.getResourceName()).thenReturn("grpc.request");
    when(span.getServiceName()).thenReturn("svc");
    when(span.getOperationName()).thenReturn("grpc.request");
    when(span.getServiceNameSource()).thenReturn(null);
    when(span.getType()).thenReturn("grpc");
    when(span.getHttpStatusCode()).thenReturn((short) 0);
    when(span.getParentId()).thenReturn(0L);
    when(span.getOrigin()).thenReturn(null);
    when(span.unsafeGetTag(eq(Tags.SPAN_KIND), any(CharSequence.class))).thenReturn("");
    when(span.unsafeGetTag(tagKey)).thenReturn(tagValue);
    return span;
  }
}
