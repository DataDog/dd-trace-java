package datadog.trace.common.writer;

import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SERIALIZATION;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SINGLE_SPAN_SAMPLING;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentMapperDiscovery;
import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class DDAgentWriterTest extends DDCoreJavaSpecification {

  HealthMetrics monitor = mock(HealthMetrics.class);
  TraceProcessingWorker worker = mock(TraceProcessingWorker.class);
  DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
  DDAgentApi api = mock(DDAgentApi.class);
  MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, SECONDS);
  PayloadDispatcherImpl dispatcher =
      new PayloadDispatcherImpl(new DDAgentMapperDiscovery(discovery), api, monitor, monitoring);
  DDAgentWriter writer = new DDAgentWriter(worker, dispatcher, monitor, 1, SECONDS, false);

  // Only used to create spans
  CoreTracer dummyTracer = tracerBuilder().writer(new ListWriter()).build();

  @AfterEach
  void cleanup() {
    writer.close();
    dummyTracer.close();
  }

  @Test
  void testWriterBuilder() {
    DDAgentWriter builtWriter = DDAgentWriter.builder().build();

    assertNotNull(builtWriter);
  }

  @Test
  void testWriterStart() {
    int capacity = 5;

    when(worker.getCapacity()).thenReturn(capacity);
    writer.start();

    verify(monitor).start();
    verify(worker).start();
    verify(worker).getCapacity();
    verify(monitor).onStart(capacity);
    verifyNoMoreInteractions(monitor, worker, discovery, api);
  }

  @Test
  void testWriterStartClosed() {
    writer.close();
    clearInvocations(monitor, worker, discovery, api);

    writer.start();

    verifyNoMoreInteractions(monitor, worker, discovery, api);
  }

  @Test
  void testWriterFlush() {
    when(worker.flush(1, SECONDS)).thenReturn(true, false);

    // first flush succeeds
    writer.flush();

    // monitor is notified
    verify(worker).flush(1, SECONDS);
    verify(monitor).onFlush(false);
    verifyNoMoreInteractions(monitor, worker, discovery, api);

    clearInvocations(monitor, worker, discovery, api);

    // second flush returns false
    writer.flush();

    // no additional monitor notifications
    verify(worker).flush(1, SECONDS);
    verifyNoMoreInteractions(monitor, worker, discovery, api);
  }

  @Test
  void testWriterFlushClosed() {
    writer.close();
    clearInvocations(monitor, worker, discovery, api);

    writer.flush();

    verifyNoMoreInteractions(monitor, worker, discovery, api);
  }

  @Test
  void testWriterWritePublishSucceeds() {
    List<DDSpan> trace =
        Collections.singletonList(
            (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start());

    // publish succeeds
    when(worker.publish(any(), anyInt(), eq(trace))).thenReturn(ENQUEUED_FOR_SERIALIZATION);
    writer.write(trace);

    // monitor is notified of successful publication
    verify(worker).publish(any(), anyInt(), eq(trace));
    verify(monitor).onPublish(any(), anyInt());
    verifyNoMoreInteractions(monitor, worker, discovery, api);
  }

  @Test
  void testWriterWritePublishForSingleSpanSampling() {
    List<DDSpan> trace =
        Collections.singletonList(
            (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start());

    // publish succeeds (single span sampling)
    when(worker.publish(any(), anyInt(), eq(trace))).thenReturn(ENQUEUED_FOR_SINGLE_SPAN_SAMPLING);
    writer.write(trace);

    // monitor should not call onPublish for single span sampling
    verify(worker).publish(any(), anyInt(), eq(trace));
    verifyNoMoreInteractions(monitor, worker, discovery, api);
  }

  @TableTest({
    "scenario          | publishResult          ",
    "buffer overflow   | DROPPED_BUFFER_OVERFLOW",
    "dropped by policy | DROPPED_BY_POLICY      "
  })
  void testWriterWritePublishFails(PublishResult publishResult) {
    List<DDSpan> trace =
        Collections.singletonList(
            (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start());

    // publish fails
    when(worker.publish(any(), anyInt(), eq(trace))).thenReturn(publishResult);
    writer.write(trace);

    // monitor is notified of unsuccessful publication
    verify(worker).publish(any(), anyInt(), eq(trace));
    verify(monitor).onFailedPublish(anyInt(), anyInt());
    verifyNoMoreInteractions(monitor, worker, discovery, api);
  }

  @Test
  void testEmptyTracesShouldBeReportedAsFailures() {
    // trace is empty
    writer.write(Collections.emptyList());

    // monitor is notified of unsuccessful publication
    verify(monitor).onFailedPublish(anyInt(), anyInt());
    verifyNoMoreInteractions(monitor, worker, discovery, api);
  }

  @Test
  void testWriterWriteClosed() {
    writer.close();
    clearInvocations(monitor, worker, discovery, api);
    List<DDSpan> trace =
        Collections.singletonList(
            (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start());

    writer.write(trace);

    verify(monitor).onFailedPublish(anyInt(), anyInt());
    verifyNoMoreInteractions(monitor, worker, discovery, api);
  }

  @TableTest({
    "scenario          | publishResult          ",
    "dropped by policy | DROPPED_BY_POLICY      ",
    "buffer overflow   | DROPPED_BUFFER_OVERFLOW"
  })
  void testDroppedTraceIsCounted(PublishResult publishResult) {
    // setup - use local mocks to avoid interference with instance-level mocks
    TraceProcessingWorker localWorker = mock(TraceProcessingWorker.class);
    HealthMetrics localMonitor = mock(HealthMetrics.class);
    PayloadDispatcherImpl localDispatcher = mock(PayloadDispatcherImpl.class);
    DDAgentWriter localWriter =
        new DDAgentWriter(localWorker, localDispatcher, localMonitor, 1, SECONDS, false);

    DDSpan p0 = newSpan();
    p0.setSamplingPriority(PrioritySampling.SAMPLER_DROP);
    List<DDSpan> trace = Arrays.asList(p0, newSpan());

    when(localWorker.publish(eq(trace.get(0)), eq((int) PrioritySampling.SAMPLER_DROP), eq(trace)))
        .thenReturn(publishResult);
    localWriter.write(trace);

    verify(localWorker)
        .publish(eq(trace.get(0)), eq((int) PrioritySampling.SAMPLER_DROP), eq(trace));
    verify(localDispatcher).onDroppedTrace(trace.size());
  }

  DDSpan newSpan() {
    // Use the UNSET-priority variant so setSamplingPriority() can change the priority later
    return buildSpan(0L, "test.tag", "test.value", PropagationTags.factory().empty());
  }
}
