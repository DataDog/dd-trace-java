package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentMapperDiscovery;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.PendingTrace;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.tabletest.junit.TableTest;

class PayloadDispatcherImplTest extends DDJavaSpecification {

  static final MonitoringImpl monitoring =
      new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);

  // Groovy baseline: v0.5 ~5.5s, v0.4 ~1.3s; Java has higher mock overhead so use 30s timeout
  @Timeout(30)
  @TableTest({"scenario | traceEndpoint", "v0.5     | 'v0.5/traces'", "v0.4     | 'v0.4/traces'"})
  void testFlushAutomaticallyWhenDataLimitIsBreached(String traceEndpoint) throws Exception {
    AtomicBoolean flushed = new AtomicBoolean();
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.getTraceEndpoint()).thenReturn(traceEndpoint);
    DDAgentApi api = mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any()))
        .thenAnswer(
            inv -> {
              flushed.set(true);
              return RemoteApi.Response.success(200);
            });
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);
    List<DDSpan> trace = Collections.singletonList(realSpan());

    while (!flushed.get()) {
      dispatcher.addTrace(trace);
    }

    // the dispatcher has flushed
    assertTrue(flushed.get());
  }

  @TableTest({
    "scenario        | traceEndpoint | traceCount",
    "v0.4 1 trace    | 'v0.4/traces' | 1         ",
    "v0.4 10 traces  | 'v0.4/traces' | 10        ",
    "v0.4 100 traces | 'v0.4/traces' | 100       ",
    "v0.5 1 trace    | 'v0.5/traces' | 1         ",
    "v0.5 10 traces  | 'v0.5/traces' | 10        ",
    "v0.5 100 traces | 'v0.5/traces' | 100       "
  })
  void testShouldFlushBufferOnDemand(String traceEndpoint, int traceCount) throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    DDAgentApi api = mock(DDAgentApi.class);
    when(discovery.getTraceEndpoint()).thenReturn(traceEndpoint);
    when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.success(200));
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);
    List<DDSpan> trace = Collections.singletonList(realSpan());

    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace);
    }
    dispatcher.flush();

    verify(discovery, org.mockito.Mockito.times(2)).getTraceEndpoint();
    verify(healthMetrics).onSerialize(intThat(size -> size > 0));
    verify(api).sendSerializedTraces(argThat(p -> p.traceCount() == traceCount));
  }

  @TableTest({
    "scenario        | traceEndpoint | traceCount",
    "v0.4 1 trace    | 'v0.4/traces' | 1         ",
    "v0.4 10 traces  | 'v0.4/traces' | 10        ",
    "v0.4 100 traces | 'v0.4/traces' | 100       ",
    "v0.5 1 trace    | 'v0.5/traces' | 1         ",
    "v0.5 10 traces  | 'v0.5/traces' | 10        ",
    "v0.5 100 traces | 'v0.5/traces' | 100       "
  })
  void testShouldReportFailedRequestToMonitor(String traceEndpoint, int traceCount)
      throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    DDAgentApi api = mock(DDAgentApi.class);
    when(discovery.getTraceEndpoint()).thenReturn(traceEndpoint);
    when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.failed(400));
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);
    List<DDSpan> trace = Collections.singletonList(realSpan());

    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace);
    }
    dispatcher.flush();

    verify(discovery, org.mockito.Mockito.times(2)).getTraceEndpoint();
    verify(healthMetrics).onSerialize(intThat(size -> size > 0));
    verify(api).sendSerializedTraces(argThat(p -> p.traceCount() == traceCount));
  }

  @Test
  void testShouldDropTraceWhenThereIsNoAgentConnectivity() throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.getTraceEndpoint()).thenReturn(null);
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);
    List<DDSpan> trace = Collections.singletonList(realSpan());

    dispatcher.addTrace(trace);

    verify(healthMetrics).onFailedPublish(eq((int) PrioritySampling.UNSET), anyInt());
  }

  @Test
  void testTraceAndSpanCountsAreResetAfterAccess() {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.getTraceEndpoint()).thenReturn("v0.4/traces");
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);

    // add traces and dropped counts
    dispatcher.addTrace(Collections.emptyList());
    dispatcher.onDroppedTrace(20);
    dispatcher.onDroppedTrace(2);
    Payload payload = dispatcher.newPayload(1, ByteBuffer.allocate(0));

    // dropped counts are accumulated
    assertEquals(22, payload.droppedSpans());
    assertEquals(2, payload.droppedTraces());

    // create another payload
    Payload newPayload = dispatcher.newPayload(1, ByteBuffer.allocate(0));

    // counts are reset after access
    assertEquals(0, newPayload.droppedSpans());
    assertEquals(0, newPayload.droppedTraces());
  }

  DDSpan realSpan() throws Exception {
    // getTracer() and mapServiceName() are package-private in TraceCollector; use a custom
    // Answer to handle them at runtime without compile-time accessibility issues
    PendingTrace trace =
        mock(
            PendingTrace.class,
            invocation -> {
              Class<?> returnType = invocation.getMethod().getReturnType();
              if (CoreTracer.class.isAssignableFrom(returnType)) {
                // Use RETURNS_DEFAULTS so getTagInterceptor() returns null (matching Groovy Stub
                // behavior)
                return mock(CoreTracer.class);
              }
              if (returnType == String.class) {
                Object[] args = invocation.getArguments();
                // mapServiceName(String) - return the argument unchanged
                if (args.length > 0 && args[0] instanceof String) {
                  return args[0];
                }
                return "";
              }
              return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1L,
            DDSpanId.ZERO,
            null,
            "",
            "",
            "",
            PrioritySampling.UNSET,
            "",
            Collections.emptyMap(),
            false,
            "",
            0,
            trace,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            PropagationTags.factory().empty());
    Constructor<DDSpan> ctor =
        DDSpan.class.getDeclaredConstructor(
            String.class, long.class, DDSpanContext.class, List.class);
    ctor.setAccessible(true);
    return ctor.newInstance("test", 0L, context, null);
  }
}
