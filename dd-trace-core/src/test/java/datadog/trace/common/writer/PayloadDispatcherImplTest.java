package datadog.trace.common.writer;

import static datadog.trace.api.ProtocolVersion.V0_4;
import static datadog.trace.api.ProtocolVersion.V0_5;
import static datadog.trace.api.ProtocolVersion.V1_0;
import static datadog.trace.core.monitor.OtelExporterId.Signal.SPAN;
import static java.util.Arrays.asList;
import static java.util.Collections.nCopies;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.serialization.Writable;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentMapperDiscovery;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.PendingTrace;
import datadog.trace.core.monitor.ExportRecorder;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.OtelComponentId;
import datadog.trace.core.monitor.OtelExporterId;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.tabletest.junit.TableTest;

class PayloadDispatcherImplTest extends DDJavaSpecification {

  static final MonitoringImpl monitoring =
      new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);

  @Timeout(180)
  // spotless:off
  @TableTest({
      "scenario | traceEndpoint",
      "v0.5     | 'v0.5/traces'",
      "v0.4     | 'v0.4/traces'"
  })
  // spotless:on
  void testFlushAutomaticallyWhenDataLimitIsBreached(String traceEndpoint) throws Exception {
    AtomicBoolean flushed = new AtomicBoolean();
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.getTraceEndpoint()).thenReturn(traceEndpoint);
    DDAgentApi api = mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any()))
        .thenAnswer(inv -> {
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
    Payload payload = dispatcher.newPayload(1, singletonList(ByteBuffer.allocate(0)));

    // dropped counts are accumulated
    assertEquals(22, payload.droppedSpans());
    assertEquals(2, payload.droppedTraces());

    // create another payload
    Payload newPayload = dispatcher.newPayload(1, singletonList(ByteBuffer.allocate(0)));

    // counts are reset after access
    assertEquals(0, newPayload.droppedSpans());
    assertEquals(0, newPayload.droppedTraces());
  }

  @Test
  void payloadsAfterTheAgentChangesProtocolUseTheNewProtocol() throws Exception {
    AgentFixture agent = new AgentFixture("v0.5/traces");
    List<DDSpan> trace = singletonList(realSpan());

    agent.dispatcher.addTrace(trace);
    agent.dispatcher.flush();
    when(agent.features.getTraceEndpoint()).thenReturn("v0.4/traces");
    agent.dispatcher.addTrace(trace);
    agent.dispatcher.flush();

    List<Payload> sent = agent.sentPayloads(2);
    assertEquals(V0_5, sent.get(0).protocol());
    assertEquals(V0_4, sent.get(1).protocol());
  }

  /** The Agent starts advertising the preferred endpoint while a v0.4 payload is in progress. */
  @Test
  void aProtocolChangeMidPayloadSwitchesAtTheNextPayload() throws Exception {
    AgentFixture agent = new AgentFixture("v0.4/traces");
    List<DDSpan> trace = singletonList(realSpan());

    agent.dispatcher.addTrace(trace);
    when(agent.features.getTraceEndpoint()).thenReturn("v1.0/traces");
    // the payload in progress stays v0.4 (the API sends it to v0.4/traces); the next one is v1.0
    agent.dispatcher.addTrace(trace);
    agent.dispatcher.flush();
    agent.dispatcher.addTrace(trace);
    agent.dispatcher.flush();

    List<Payload> sent = agent.sentPayloads(2);
    assertEquals(V0_4, sent.get(0).protocol());
    assertEquals(2, sent.get(0).traceCount());
    assertEquals(V1_0, sent.get(1).protocol());
    assertEquals(1, sent.get(1).traceCount());
  }

  /**
   * With periodic flushes off, every payload is sent because the buffer filled. The overflow path
   * must still pick up a protocol change: only the payload in progress keeps the old protocol.
   */
  @Test
  void aProtocolChangeTakesEffectWhenOnlyOverflowSendsPayloads() throws Exception {
    List<DDSpan> trace = singletonList(realSpan());
    // small buffers that hold a few traces, so overflow sends payloads without any flush()
    TraceMapperV0_5 v05 =
        new TraceMapperV0_5(64 << 10, 2 * payloadSize(new TraceMapperV0_5(), trace));
    TraceMapperV0_4 v04 = new TraceMapperV0_4(2 * payloadSize(new TraceMapperV0_4(), trace));
    SwitchingDiscovery discovery = new SwitchingDiscovery(v05);
    DDAgentApi api = mock(DDAgentApi.class);
    List<Payload> sent = new ArrayList<>();
    when(api.sendSerializedTraces(any()))
        .thenAnswer(invocation -> {
          sent.add(invocation.getArgument(0));
          return RemoteApi.Response.success(200);
        });
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(discovery, api, mock(HealthMetrics.class), monitoring);

    for (int i = 0; i < 200 && sent.isEmpty(); i++) {
      dispatcher.addTrace(trace);
    }
    int beforeSwitch = sent.size();
    assertTrue(beforeSwitch > 0, "the v0.5 buffer should have overflowed");
    discovery.switchTo(v04);
    for (int i = 0; i < 200 && sent.size() < beforeSwitch + 2; i++) {
      dispatcher.addTrace(trace);
    }

    List<Payload> afterSwitch = sent.subList(beforeSwitch, sent.size());
    assertEquals(2, afterSwitch.size(), () -> "payloads after the switch: " + afterSwitch.size());
    assertEquals(V0_5, afterSwitch.get(0).protocol(), "the payload in progress keeps v0.5");
    assertEquals(V0_4, afterSwitch.get(1).protocol(), "the next payload uses the new protocol");
  }

  /** A trace that overflows a partly filled buffer is retried as a retry, as the writer does. */
  @Test
  void aTraceRetriedAfterAFullBufferIsMappedAsARetry() throws Exception {
    List<DDSpan> trace = singletonList(realSpan());
    RecordingMapper mapper =
        new RecordingMapper(new TraceMapperV0_4(2 * payloadSize(new TraceMapperV0_4(), trace)));
    DDAgentApi api = mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.success(200));
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new SwitchingDiscovery(mapper), api, mock(HealthMetrics.class), monitoring);

    int sends = 0;
    for (int i = 0; i < 200 && sends == 0; i++) {
      dispatcher.addTrace(trace);
      sends = mockingDetails(api).getInvocations().size();
    }

    List<Boolean> retries = mapper.retries;
    assertTrue(sends > 0, "the buffer should have overflowed");
    // the overflowing trace: a first attempt that didn't fit, then a retry in the empty buffer
    assertEquals(false, retries.get(retries.size() - 2));
    assertEquals(true, retries.get(retries.size() - 1));
  }

  /** A trace too large for an empty buffer is mapped once and rejected; nothing is sent. */
  @Test
  void aTraceTooLargeForAnEmptyBufferIsRejectedWithoutARetry() throws Exception {
    List<DDSpan> trace = singletonList(realSpan());
    RecordingMapper mapper = new RecordingMapper(new TraceMapperV0_4(16));
    DDAgentApi api = mock(DDAgentApi.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(new SwitchingDiscovery(mapper), api, healthMetrics, monitoring);

    dispatcher.addTrace(trace);

    assertEquals(singletonList(false), mapper.retries);
    verify(healthMetrics).onFailedPublish(anyInt(), eq(1));
    verifyNoInteractions(api);
  }

  /**
   * A full buffer is sent before the overflowing trace is retried, so that payload counts only the
   * traces written before it.
   */
  @Test
  void aBufferFullFlushCountsOnlyTheSpansAlreadyWritten() throws Exception {
    DDSpan span = realSpan();
    List<DDSpan> oneSpan = singletonList(span);
    // a small buffer, so overflow sends payloads without any flush()
    TraceMapperV0_4 mapper = new TraceMapperV0_4(2 * payloadSize(new TraceMapperV0_4(), oneSpan));
    DDAgentApi api = mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.success(200));
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    PayloadDispatcherImpl dispatcher = countingDispatcher(mapper, api, healthMetrics);

    // traces of 1 and 2 spans in turn, so a count including the retried trace shows
    List<Integer> traceSizes = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      traceSizes.add(1 + i % 2);
      dispatcher.addTrace(nCopies(1 + i % 2, span));
    }
    dispatcher.flush();

    ArgumentCaptor<Payload> payloads = ArgumentCaptor.forClass(Payload.class);
    verify(api, atLeast(3)).sendSerializedTraces(payloads.capture());
    List<Long> expected = new ArrayList<>();
    int next = 0;
    for (Payload payload : payloads.getAllValues()) {
      long spans = 0;
      for (int i = 0; i < payload.traceCount(); i++) {
        spans += traceSizes.get(next++);
      }
      expected.add(spans);
    }
    assertEquals(traceSizes.size(), next, "every trace is sent");
    assertEquals(expected, exportedSpanCounts(healthMetrics));
  }

  /** A trace too large for an empty buffer is rejected, and its spans aren't counted. */
  @Test
  void aRejectedTraceIsNotCounted() throws Exception {
    DDSpan span = realSpan();
    List<DDSpan> oneSpan = singletonList(span);
    List<DDSpan> tooLarge = nCopies(20, span);
    TraceMapperV0_4 mapper = new TraceMapperV0_4(2 * payloadSize(new TraceMapperV0_4(), oneSpan));
    DDAgentApi api = mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.success(200));
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    PayloadDispatcherImpl dispatcher = countingDispatcher(mapper, api, healthMetrics);

    dispatcher.addTrace(tooLarge); // into the empty buffer
    dispatcher.addTrace(oneSpan);
    dispatcher.addTrace(tooLarge); // sends the one-span trace, then is rejected
    dispatcher.addTrace(oneSpan);
    dispatcher.flush();

    assertEquals(asList(1L, 1L), exportedSpanCounts(healthMetrics));
  }

  /** A dispatcher reporting each send's span export to {@code healthMetrics}. */
  private static PayloadDispatcherImpl countingDispatcher(
      RemoteMapper mapper, RemoteApi api, HealthMetrics healthMetrics) {
    OtelExporterId exporter =
        new OtelExporterId(SPAN, OtelComponentId.next("test_span_exporter"), null, false);
    return new PayloadDispatcherImpl(
        new SwitchingDiscovery(mapper),
        api,
        healthMetrics,
        monitoring,
        ExportRecorder.create(healthMetrics, exporter));
  }

  /** The span count of each export reported to {@code healthMetrics}, in order. */
  private static List<Long> exportedSpanCounts(HealthMetrics healthMetrics) {
    ArgumentCaptor<Long> counts = ArgumentCaptor.forClass(Long.class);
    verify(healthMetrics, atLeast(0)).onExportStart(any(), counts.capture());
    return counts.getAllValues();
  }

  /** Records each map call's retry flag, then encodes with {@code delegate}. */
  private static final class RecordingMapper implements RemoteMapper {
    private final RemoteMapper delegate;
    final List<Boolean> retries = new ArrayList<>();

    RecordingMapper(RemoteMapper delegate) {
      this.delegate = delegate;
    }

    @Override
    public void map(List<? extends CoreSpan<?>> trace, Writable packer) {
      retries.add(false);
      delegate.map(trace, packer);
    }

    @Override
    public void map(List<? extends CoreSpan<?>> trace, Writable packer, boolean retry) {
      retries.add(retry);
      delegate.map(trace, packer);
    }

    @Override
    public void reset() {
      delegate.reset();
    }

    @Override
    public Payload newPayload() {
      return delegate.newPayload();
    }

    @Override
    public int messageBufferSize() {
      return delegate.messageBufferSize();
    }

    @Override
    public String endpoint() {
      return delegate.endpoint();
    }
  }

  /** The size of a one-trace payload encoded with {@code mapper}. */
  private int payloadSize(RemoteMapper mapper, List<DDSpan> trace) {
    DDAgentApi api = mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.success(200));
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new SwitchingDiscovery(mapper), api, mock(HealthMetrics.class), monitoring);
    dispatcher.addTrace(trace);
    dispatcher.flush();
    ArgumentCaptor<Payload> payload = ArgumentCaptor.forClass(Payload.class);
    verify(api).sendSerializedTraces(payload.capture());
    return payload.getValue().sizeInBytes();
  }

  /** A discovery that reports itself outdated once {@link #switchTo} names a new mapper. */
  private static final class SwitchingDiscovery implements RemoteMapperDiscovery {
    private RemoteMapper current;
    private RemoteMapper next;

    SwitchingDiscovery(RemoteMapper initial) {
      this.current = initial;
    }

    void switchTo(RemoteMapper mapper) {
      this.next = mapper;
    }

    @Override
    public void discover() {
      if (next != null) {
        current = next;
        next = null;
      }
    }

    @Override
    public RemoteMapper getMapper() {
      return current;
    }

    @Override
    public boolean isOutdated() {
      return next != null;
    }
  }

  /** A dispatcher over a real mapper discovery, with the agent's features and API mocked. */
  private static final class AgentFixture {
    final DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    final DDAgentApi api = mock(DDAgentApi.class);
    final PayloadDispatcherImpl dispatcher;

    AgentFixture(String traceEndpoint) {
      when(features.getTraceEndpoint()).thenReturn(traceEndpoint);
      when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.success(200));
      dispatcher =
          new PayloadDispatcherImpl(
              new DDAgentMapperDiscovery(features), api, mock(HealthMetrics.class), monitoring);
    }

    List<Payload> sentPayloads(int count) {
      ArgumentCaptor<Payload> payloads = ArgumentCaptor.forClass(Payload.class);
      verify(api, times(count)).sendSerializedTraces(payloads.capture());
      return payloads.getAllValues();
    }
  }

  DDSpan realSpan() throws Exception {
    // A real config snapshot, since serialization reads the trace's tag levels from it. Its type
    // isn't visible from this package, so it's held as an Object and matched by name below.
    CoreTracer tracer = CoreTracer.builder().writer(new ListWriter()).build();
    Object traceConfig = tracer.captureTraceConfig();
    tracer.close();

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
              if (returnType.getSimpleName().equals("ConfigSnapshot")) {
                return traceConfig;
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
