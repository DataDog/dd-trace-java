package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
public class PendingTraceTest extends PendingTraceTestBase {

  @BeforeAll
  static void checkJvm() {
    Assumptions.assumeFalse(
        JavaVirtualMachine.isOracleJDK8(),
        "Oracle JDK 1.8 did not merge the fix in JDK-8058322, leading to the JVM failing to"
            + " correctly extract method parameters without args, when the code is compiled on a"
            + " later JDK (targeting 8). This can manifest when creating mocks.");
  }

  @Override
  protected boolean useStrictTraceWrites() {
    // This tests the behavior of the relaxed pending trace implementation
    return false;
  }

  private DDSpan createSimpleSpan(PendingTrace trace) {
    return createSimpleSpanWithID(trace, 1);
  }

  private DDSpan createSimpleSpanWithID(PendingTrace trace, long id) {
    return new DDSpan(
        "test",
        0L,
        new DDSpanContext(
            DDTraceId.from(1),
            id,
            0,
            null,
            "",
            "",
            "",
            UNSET,
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
            PropagationTags.factory().empty()),
        null);
  }

  @Test
  void traceStillReportedWhenUnfinishedContinuationDiscarded()
      throws InterruptedException, TimeoutException {
    AgentScope scope = tracer.activateSpan(rootSpan);
    tracer.captureActiveSpan();
    scope.close();

    rootSpan.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(Arrays.asList(rootSpan), new ArrayList<>(traceCollector.getSpans()));
    assertTrue(writer.isEmpty());
    // root span buffer delay expires
    writer.waitForTraces(1);

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(Arrays.asList(Arrays.asList(rootSpan)), new ArrayList<>(writer));
    assertEquals(1, writer.getTraceCount());
  }

  @Test
  void verifyHealthMetricsCalled() {
    CoreTracer stubTracer = mock(CoreTracer.class);
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = mock(PendingTraceBuffer.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);

    when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(Collections.emptyMap());

    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            buffer,
            mock(TimeSource.class),
            null,
            false,
            healthMetrics);

    DDSpan span = createSimpleSpan(trace);
    trace.registerSpan(span);

    verify(healthMetrics, times(1)).onCreateSpan();

    span.finish();

    verify(healthMetrics, times(1)).onCreateTrace();
  }

  @Test
  void writeWhenRunningSpansDisabledOnlyCompletedSpansWritten() {
    CoreTracer stubTracer = mock(CoreTracer.class);
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = mock(PendingTraceBuffer.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);

    when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(Collections.emptyMap());
    when(buffer.longRunningSpansEnabled()).thenReturn(true);

    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            buffer,
            mock(TimeSource.class),
            null,
            false,
            healthMetrics);

    DDSpan span1 = createSimpleSpanWithID(trace, 39);
    span1.setDurationNano(31);
    span1.setSamplingPriority(USER_KEEP);
    trace.registerSpan(span1);

    DDSpan unfinishedSpan = createSimpleSpanWithID(trace, 191);
    trace.registerSpan(unfinishedSpan);

    DDSpan span2 = createSimpleSpanWithID(trace, 9999);
    span2.setDurationNano(9191);
    trace.registerSpan(span2);

    List<DDSpan> traceToWrite = new ArrayList<>(0);
    int completedSpans = trace.enqueueSpansToWrite(traceToWrite, false);

    assertEquals(2, completedSpans);
    assertEquals(2, traceToWrite.size());
    assertTrue(traceToWrite.containsAll(Arrays.asList(span1, span2)));
    assertEquals(1, trace.getSpans().size());
    assertEquals(unfinishedSpan, trace.getSpans().iterator().next());
  }

  @Test
  void writeWhenRunningSpansEnabledCompleteAndRunningSpansWritten() {
    CoreTracer stubTracer = mock(CoreTracer.class);
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = mock(PendingTraceBuffer.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);

    when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(Collections.emptyMap());
    when(buffer.longRunningSpansEnabled()).thenReturn(true);

    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            buffer,
            mock(TimeSource.class),
            null,
            false,
            healthMetrics);

    DDSpan span1 = createSimpleSpanWithID(trace, 39);
    span1.setDurationNano(31);
    span1.setSamplingPriority(USER_KEEP);
    trace.registerSpan(span1);

    DDSpan unfinishedSpan = createSimpleSpanWithID(trace, 191);
    trace.registerSpan(unfinishedSpan);

    DDSpan span2 = createSimpleSpanWithID(trace, 9999);
    span2.setServiceName("9191");
    span2.setDurationNano(9191);
    trace.registerSpan(span2);

    DDSpan unfinishedSpan2 = createSimpleSpanWithID(trace, 77771);
    trace.registerSpan(unfinishedSpan2);

    List<DDSpan> traceToWrite = new ArrayList<>(0);
    int completedSpans = trace.enqueueSpansToWrite(traceToWrite, true);

    assertEquals(2, completedSpans);
    assertEquals(4, traceToWrite.size());
    assertTrue(
        traceToWrite.containsAll(Arrays.asList(span1, span2, unfinishedSpan, unfinishedSpan2)));
    assertEquals(2, trace.getSpans().size());
    assertTrue(
        new ArrayList<>(trace.getSpans())
            .containsAll(Arrays.asList(unfinishedSpan, unfinishedSpan2)));
  }
}
