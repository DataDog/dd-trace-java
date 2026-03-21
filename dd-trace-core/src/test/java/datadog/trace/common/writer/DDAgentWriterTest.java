package datadog.trace.common.writer;

import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.DROPPED_BUFFER_OVERFLOW;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.DROPPED_BY_POLICY;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SERIALIZATION;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SINGLE_SPAN_SAMPLING;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.PendingTrace;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DDAgentWriterTest extends DDCoreSpecification {

  HealthMetrics monitor = mock(HealthMetrics.class);
  TraceProcessingWorker worker = mock(TraceProcessingWorker.class);
  DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
  DDAgentApi api = mock(DDAgentApi.class);
  MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
  PayloadDispatcherImpl dispatcher =
      new PayloadDispatcherImpl(new DDAgentMapperDiscovery(discovery), api, monitor, monitoring);

  DDAgentWriter writer = new DDAgentWriter(worker, dispatcher, monitor, 1, TimeUnit.SECONDS, false);

  CoreTracer dummyTracer;

  @AfterEach
  void tearDown() throws Exception {
    writer.close();
    if (dummyTracer != null) {
      dummyTracer.close();
    }
  }

  DDSpan buildFakeSpan() {
    if (dummyTracer == null) {
      dummyTracer = tracerBuilder().writer(new ListWriter()).build();
    }
    return (DDSpan) dummyTracer.buildSpan("fakeOperation").start();
  }

  @Test
  void testWriterBuilder() {
    DDAgentWriter w = DDAgentWriter.builder().build();
    assertNotNull(w);
  }

  @Test
  void testWriterStart() throws Exception {
    int capacity = 5;
    when(worker.getCapacity()).thenReturn(capacity);

    writer.start();

    verify(monitor, times(1)).start();
    verify(worker, times(1)).start();
    verify(worker, times(1)).getCapacity();
    verify(monitor, times(1)).onStart(capacity);
    verifyNoMoreInteractions(monitor, worker);
  }

  @Test
  void testWriterStartClosed() throws Exception {
    writer.close();
    clearInvocations(monitor, worker);

    writer.start();

    verifyNoMoreInteractions(monitor, worker);
  }

  @Test
  void testWriterFlush() throws Exception {
    when(worker.flush(1, TimeUnit.SECONDS)).thenReturn(true);

    writer.flush();

    verify(worker, times(1)).flush(1, TimeUnit.SECONDS);
    verify(monitor, times(1)).onFlush(false);
    verifyNoMoreInteractions(monitor, worker);

    when(worker.flush(1, TimeUnit.SECONDS)).thenReturn(false);

    writer.flush();

    verify(worker, times(2)).flush(1, TimeUnit.SECONDS);
    verifyNoMoreInteractions(monitor, worker);
  }

  @Test
  void testWriterFlushClosed() throws Exception {
    writer.close();
    clearInvocations(monitor, worker);

    writer.flush();

    verifyNoMoreInteractions(monitor, worker);
  }

  @Test
  void testWriterWritePublishSucceeds() throws Exception {
    List<DDSpan> trace = Collections.singletonList(buildFakeSpan());

    when(worker.publish(any(), anyInt(), eq(trace))).thenReturn(ENQUEUED_FOR_SERIALIZATION);

    writer.write(trace);

    verify(worker, times(1)).publish(any(), anyInt(), eq(trace));
    verify(monitor, times(1)).onPublish(eq(trace), anyInt());
    verifyNoMoreInteractions(monitor, worker);
  }

  @Test
  void testWriterWritePublishForSingleSpanSampling() throws Exception {
    List<DDSpan> trace = Collections.singletonList(buildFakeSpan());

    when(worker.publish(any(), anyInt(), eq(trace))).thenReturn(ENQUEUED_FOR_SINGLE_SPAN_SAMPLING);

    writer.write(trace);

    verify(worker, times(1)).publish(any(), anyInt(), eq(trace));
    verifyNoMoreInteractions(monitor, worker);
  }

  static Stream<Arguments> publishFailureArgs() {
    return Stream.of(Arguments.of(DROPPED_BUFFER_OVERFLOW), Arguments.of(DROPPED_BY_POLICY));
  }

  @ParameterizedTest
  @MethodSource("publishFailureArgs")
  void testWriterWritePublishFails(PublishResult publishResult) throws Exception {
    List<DDSpan> trace = Collections.singletonList(buildFakeSpan());

    when(worker.publish(any(), anyInt(), eq(trace))).thenReturn(publishResult);

    writer.write(trace);

    verify(worker, times(1)).publish(any(), anyInt(), eq(trace));
    verify(monitor, times(1)).onFailedPublish(anyInt(), anyInt());
    verifyNoMoreInteractions(monitor, worker);
  }

  @Test
  void emptyTracesShouldBeReportedAsFailures() throws Exception {
    writer.write(Collections.<DDSpan>emptyList());

    verify(monitor, times(1)).onFailedPublish(anyInt(), anyInt());
    verifyNoMoreInteractions(monitor, worker);
  }

  @Test
  void testWriterWriteClosed() throws Exception {
    writer.close();
    clearInvocations(monitor, worker);
    List<DDSpan> trace = Collections.singletonList(buildFakeSpan());

    writer.write(trace);

    verify(monitor, times(1)).onFailedPublish(anyInt(), anyInt());
    verifyNoMoreInteractions(monitor, worker);
  }

  @ParameterizedTest
  @MethodSource("publishFailureArgs")
  void droppedTraceIsCounted(PublishResult publishResult) throws Exception {
    TraceProcessingWorker mockWorker = mock(TraceProcessingWorker.class);
    HealthMetrics mockMonitor = mock(HealthMetrics.class);
    PayloadDispatcherImpl mockDispatcher = mock(PayloadDispatcherImpl.class);
    DDAgentWriter writerUnderTest =
        new DDAgentWriter(mockWorker, mockDispatcher, mockMonitor, 1, TimeUnit.SECONDS, false);

    DDSpan p0 = newSpan();
    p0.setSamplingPriority((int) PrioritySampling.SAMPLER_DROP);
    List<DDSpan> trace = Arrays.asList(p0, newSpan());

    when(mockWorker.publish(eq(trace.get(0)), eq((int) PrioritySampling.SAMPLER_DROP), eq(trace)))
        .thenReturn(publishResult);

    writerUnderTest.write(trace);

    verify(mockWorker).publish(trace.get(0), (int) PrioritySampling.SAMPLER_DROP, trace);
    verify(mockDispatcher).onDroppedTrace(trace.size());

    writerUnderTest.close();
  }

  DDSpan newSpan() {
    CoreTracer tracer = mock(CoreTracer.class);
    PendingTrace trace = mock(PendingTrace.class);
    when(trace.mapServiceName(any())).thenAnswer(inv -> inv.getArgument(0));
    when(trace.getTracer()).thenReturn(tracer);
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
            Collections.<String, String>emptyMap(),
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
