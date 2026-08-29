package datadog.trace.common.writer;

import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SERIALIZATION;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SINGLE_SPAN_SAMPLING;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentMapperDiscovery;
import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class DDIntakeWriterTest extends DDCoreJavaSpecification {

  HealthMetrics healthMetrics = mock(HealthMetrics.class);
  TraceProcessingWorker worker = mock(TraceProcessingWorker.class);
  DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
  DDAgentApi api = mock(DDAgentApi.class);
  MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
  PayloadDispatcherImpl dispatcher =
      new PayloadDispatcherImpl(
          new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);
  DDIntakeWriter writer = new DDIntakeWriter(worker, dispatcher, healthMetrics, false);

  // Only used to create spans
  CoreTracer dummyTracer;

  @BeforeEach
  void setup() {
    dummyTracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void cleanup() {
    writer.close();
    if (dummyTracer != null) {
      dummyTracer.close();
    }
  }

  @Test
  void testWriterBuilder() {
    DDIntakeWriter builtWriter =
        DDIntakeWriter.builder().addTrack(TrackType.NOOP, mock(RemoteApi.class)).build();

    assertNotNull(builtWriter);
  }

  @Test
  void testWriterStart() {
    int capacity = 5;

    when(worker.getCapacity()).thenReturn(capacity);
    writer.start();

    verify(healthMetrics).start();
    verify(worker).start();
    verify(worker).getCapacity();
    verify(healthMetrics).onStart(capacity);
    verifyNoMoreInteractions(healthMetrics, worker, discovery, api);
  }

  @Test
  void testWriterFlush() {
    when(worker.flush(1, TimeUnit.SECONDS)).thenReturn(true, false);

    // first flush succeeds
    writer.flush();

    // monitor is notified
    verify(worker).flush(1, TimeUnit.SECONDS);
    verify(healthMetrics).onFlush(false);
    verifyNoMoreInteractions(healthMetrics, worker, discovery, api);

    clearInvocations(healthMetrics, worker, discovery, api);

    // second flush returns false
    writer.flush();

    // no additional monitor notifications
    verify(worker).flush(1, TimeUnit.SECONDS);
    verifyNoMoreInteractions(healthMetrics, worker, discovery, api);
  }

  @Test
  void testWriterFlushClosed() {
    writer.close();
    clearInvocations(healthMetrics, worker, discovery, api);

    writer.flush();

    verifyNoMoreInteractions(healthMetrics, worker, discovery, api);
  }

  @Test
  void testWriterWritePublishSucceeds() {
    List<DDSpan> trace =
        Collections.singletonList(
            (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start());

    // publish succeeds
    when(worker.publish(any(), anyInt(), eq(trace))).thenReturn(ENQUEUED_FOR_SERIALIZATION);
    when(worker.flush(anyLong(), any(TimeUnit.class))).thenReturn(true);
    writer.write(trace);

    // monitor is notified of successful publication
    verify(worker).publish(any(), anyInt(), eq(trace));
    verify(healthMetrics).onPublish(any(), anyInt());
    verifyNoMoreInteractions(healthMetrics);
  }

  @Test
  void testWriterWritePublishForSingleSpanSampling() {
    List<DDSpan> trace =
        Collections.singletonList(
            (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start());

    // publish succeeds for single span sampling
    when(worker.publish(any(), anyInt(), eq(trace))).thenReturn(ENQUEUED_FOR_SINGLE_SPAN_SAMPLING);
    when(worker.flush(anyLong(), any(TimeUnit.class))).thenReturn(true);
    writer.write(trace);

    // monitor should not call onPublish for single span sampling
    verify(worker).publish(any(), anyInt(), eq(trace));
    verifyNoMoreInteractions(healthMetrics);
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
    when(worker.flush(anyLong(), any(TimeUnit.class))).thenReturn(true);
    writer.write(trace);

    // monitor is notified of unsuccessful publication
    verify(worker).publish(any(), anyInt(), eq(trace));
    verify(healthMetrics).onFailedPublish(anyInt(), eq(1));
    verifyNoMoreInteractions(healthMetrics);
  }

  @Test
  void testEmptyTracesShouldBeReportedAsFailures() {
    // trace is empty
    when(worker.flush(anyLong(), any(TimeUnit.class))).thenReturn(true);
    writer.write(Collections.emptyList());

    // monitor is notified of unsuccessful publication
    verify(healthMetrics).onFailedPublish(anyInt(), eq(0));
    verifyNoMoreInteractions(healthMetrics);
  }

  @Test
  void testWriterWriteClosed() {
    writer.close();
    clearInvocations(healthMetrics, worker, discovery, api);
    List<DDSpan> trace =
        Collections.singletonList(
            (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start());

    when(worker.flush(anyLong(), any(TimeUnit.class))).thenReturn(true);
    writer.write(trace);

    verify(healthMetrics).onFailedPublish(anyInt(), eq(1));
    verifyNoMoreInteractions(healthMetrics);
  }

  @TableTest({
    "scenario          | publishResult          ",
    "dropped by policy | DROPPED_BY_POLICY      ",
    "buffer overflow   | DROPPED_BUFFER_OVERFLOW"
  })
  void testDroppedTraceIsCounted(PublishResult publishResult) {
    // setup - use local mocks
    PayloadDispatcherImpl localDispatcher = mock(PayloadDispatcherImpl.class);
    DDIntakeWriter localWriter = new DDIntakeWriter(worker, localDispatcher, healthMetrics, true);

    DDSpan p0 = newSpan();
    List<DDSpan> trace = java.util.Arrays.asList(p0, newSpan());

    when(worker.publish(eq(trace.get(0)), anyInt(), eq(trace))).thenReturn(publishResult);
    localWriter.write(trace);

    verify(worker).publish(eq(trace.get(0)), anyInt(), eq(trace));
    verify(localDispatcher).onDroppedTrace(trace.size());
  }

  DDSpan newSpan() {
    // Use the UNSET-priority variant so setSamplingPriority() can change the priority later
    return buildSpan(0L, "test.tag", "test.value", PropagationTags.factory().empty());
  }
}
