package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class PayloadDispatcherImplTest {

  private static final MonitoringImpl monitoring =
      new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);

  @BeforeEach
  void setup() {
    TagsPostProcessorFactory.withAddInternalTags(false);
    TagsPostProcessorFactory.withAddRemoteHostname(false);
  }

  @AfterEach
  void cleanup() {
    TagsPostProcessorFactory.reset();
  }

  @TableTest({
    "scenario      | traceEndpoint",
    "v0.5 endpoint | v0.5/traces  ",
    "v0.4 endpoint | v0.4/traces  "
  })
  @ParameterizedTest(name = "[{index}] flush automatically when data limit is breached - {0}")
  @Timeout(10)
  void flushAutomaticallyWhenDataLimitIsBreached(String scenario, String traceEndpoint)
      throws Exception {
    AtomicBoolean flushed = new AtomicBoolean();
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.getTraceEndpoint()).thenReturn(traceEndpoint);
    DDAgentApi api = mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any()))
        .thenAnswer(
            invocation -> {
              flushed.set(true);
              return RemoteApi.Response.success(200);
            });
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);
    List<DDSpan> trace = new ArrayList<>();
    trace.add(realSpan());

    while (!flushed.get()) {
      dispatcher.addTrace(trace);
    }

    assertTrue(flushed.get());
  }

  static Stream<Arguments> shouldFlushBufferOnDemandArguments() {
    return Stream.of(
        Arguments.of("v0.4/traces", 1),
        Arguments.of("v0.4/traces", 10),
        Arguments.of("v0.4/traces", 100),
        Arguments.of("v0.5/traces", 1),
        Arguments.of("v0.5/traces", 10),
        Arguments.of("v0.5/traces", 100));
  }

  @ParameterizedTest
  @MethodSource("shouldFlushBufferOnDemandArguments")
  void shouldFlushBufferOnDemand(String traceEndpoint, int traceCount) throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    DDAgentApi api = mock(DDAgentApi.class);
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);
    List<DDSpan> trace = new ArrayList<>();
    trace.add(realSpan());

    when(discovery.getTraceEndpoint()).thenReturn(traceEndpoint);
    when(api.sendSerializedTraces(argThat(p -> p.traceCount() == traceCount)))
        .thenReturn(RemoteApi.Response.success(200));

    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace);
    }
    dispatcher.flush();

    verify(discovery, times(2)).getTraceEndpoint();
    verify(healthMetrics, times(1)).onSerialize(intThat(size -> size > 0));
    verify(api, times(1)).sendSerializedTraces(argThat(p -> p.traceCount() == traceCount));
  }

  static Stream<Arguments> shouldReportFailedRequestToMonitorArguments() {
    return Stream.of(
        Arguments.of("v0.4/traces", 1),
        Arguments.of("v0.4/traces", 10),
        Arguments.of("v0.4/traces", 100),
        Arguments.of("v0.5/traces", 1),
        Arguments.of("v0.5/traces", 10),
        Arguments.of("v0.5/traces", 100));
  }

  @ParameterizedTest
  @MethodSource("shouldReportFailedRequestToMonitorArguments")
  void shouldReportFailedRequestToMonitor(String traceEndpoint, int traceCount) throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    DDAgentApi api = mock(DDAgentApi.class);
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);
    List<DDSpan> trace = new ArrayList<>();
    trace.add(realSpan());

    when(discovery.getTraceEndpoint()).thenReturn(traceEndpoint);
    when(api.sendSerializedTraces(argThat(p -> p.traceCount() == traceCount)))
        .thenReturn(RemoteApi.Response.failed(400));

    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace);
    }
    dispatcher.flush();

    verify(discovery, times(2)).getTraceEndpoint();
    verify(healthMetrics, times(1)).onSerialize(intThat(size -> size > 0));
    verify(api, times(1)).sendSerializedTraces(argThat(p -> p.traceCount() == traceCount));
  }

  @Test
  void shouldDropTraceWhenThereIsNoAgentConnectivity() throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);
    List<DDSpan> trace = new ArrayList<>();
    trace.add(realSpan());
    when(discovery.getTraceEndpoint()).thenReturn(null);

    dispatcher.addTrace(trace);

    verify(healthMetrics, times(1))
        .onFailedPublish(
            org.mockito.ArgumentMatchers.eq((int) PrioritySampling.UNSET),
            org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void traceAndSpanCountsAreResetAfterAccess() throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.getTraceEndpoint()).thenReturn("v0.4/traces");
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);

    dispatcher.addTrace(new ArrayList<>());
    dispatcher.onDroppedTrace(20);
    dispatcher.onDroppedTrace(2);
    Payload payload = dispatcher.newPayload(1, ByteBuffer.allocate(0));
    assertEquals(22, payload.droppedSpans());
    assertEquals(2, payload.droppedTraces());

    Payload newPayload = dispatcher.newPayload(1, ByteBuffer.allocate(0));
    assertEquals(0, newPayload.droppedSpans());
    assertEquals(0, newPayload.droppedTraces());
  }

  private DDSpan realSpan() {
    CoreTracer tracer = mock(CoreTracer.class);
    PendingTrace trace = mock(PendingTrace.class);
    when(trace.getTracer()).thenReturn(tracer);
    when(trace.mapServiceName(any())).thenAnswer(invocation -> invocation.getArgument(0));
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1,
            DDSpanId.ZERO,
            null,
            "",
            "",
            "",
            (int) PrioritySampling.UNSET,
            "",
            new java.util.HashMap<>(),
            false,
            "",
            0,
            trace,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            PropagationTags.factory().empty());
    return DDSpan.create("test", 0, context, null);
  }
}
