package datadog.trace.core;

import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.time.TimeSource;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIf;
import org.mockito.Mockito;

@DisabledIf("isOracleJdk8")
public class PendingTraceTest extends PendingTraceTestBase {

  static boolean isOracleJdk8() {
    return datadog.environment.JavaVirtualMachine.isOracleJDK8();
  }

  @Override
  protected CoreTracer.CoreTracerBuilder tracerBuilder() {
    // This tests the behavior of the relaxed pending trace implementation
    return new RelaxedTracerBuilder();
  }

  static class RelaxedTracerBuilder extends CoreTracer.CoreTracerBuilder {
    RelaxedTracerBuilder() {
      statsDClient(StatsDClient.NO_OP);
      strictTraceWrites(false);
    }

    @Override
    public CoreTracer build() {
      CoreTracer tracer = super.build();
      unclosedTracers.add(tracer);
      return tracer;
    }
  }

  /**
   * Creates a span context for a given PendingTrace and span id. The span is NOT registered with
   * the trace (doesn't call registerSpan). Callers can then manually register if needed.
   */
  protected DDSpanContext createSimpleContextWithID(PendingTrace trace, long id) {
    return new DDSpanContext(
        DDTraceId.from(1),
        id,
        0,
        null,
        "",
        "",
        "",
        (int) PrioritySampling.UNSET,
        "",
        java.util.Collections.emptyMap(),
        false,
        "",
        0,
        trace,
        null,
        null,
        NoopPathwayContext.INSTANCE,
        false,
        PropagationTags.factory().empty());
  }

  /** Creates and registers a span with the trace (using DDSpan.create which auto-registers). */
  protected DDSpan createSimpleSpan(PendingTrace trace) {
    return createSimpleSpanWithID(trace, 1);
  }

  protected DDSpan createSimpleSpanWithID(PendingTrace trace, long id) {
    return DDSpan.create("test", 0L, createSimpleContextWithID(trace, id), null);
  }

  /**
   * Sets durationNano field via reflection to mark a span as finished without triggering side
   * effects.
   */
  static void setDurationNano(DDSpan span, long duration) throws Exception {
    Field field = DDSpan.class.getDeclaredField("durationNano");
    field.setAccessible(true);
    field.set(span, duration);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void traceIsStillReportedWhenUnfinishedContinuationDiscarded() throws Exception {
    datadog.trace.context.TraceScope scope = tracer.activateSpan(rootSpan);
    tracer.captureActiveSpan();
    scope.close();
    rootSpan.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(1, traceCollector.getSpans().size());
    assertEquals(
        rootSpan,
        ((java.util.concurrent.ConcurrentLinkedDeque<DDSpan>) traceCollector.getSpans()).peek());
    assertEquals(0, writer.size());

    // root span buffer delay expires
    writer.waitForTraces(1);

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(1, writer.size());
    assertEquals(1, writer.get(0).size());
    assertEquals(rootSpan, writer.get(0).get(0));
    assertEquals(1, writer.traceCount.get());
  }

  @Test
  void verifyHealthMetricsCalled() throws Exception {
    CoreTracer stubTracer = Mockito.mock(CoreTracer.class, Mockito.RETURNS_DEEP_STUBS);
    CoreTracer.ConfigSnapshot traceConfig = Mockito.mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = Mockito.mock(PendingTraceBuffer.class);
    HealthMetrics healthMetrics = Mockito.mock(HealthMetrics.class);
    Mockito.when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    Mockito.when(stubTracer.getTagInterceptor()).thenReturn(null);
    Mockito.when(traceConfig.getServiceMapping()).thenReturn(java.util.Collections.emptyMap());
    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            buffer,
            Mockito.mock(TimeSource.class),
            null,
            false,
            healthMetrics);

    // Create span and register it manually (matching Groovy test behavior)
    DDSpan span = DDSpan.create("test", 0L, createSimpleContextWithID(trace, 1), null);
    // Note: DDSpan.create auto-registers. The Groovy test used new DDSpan() (private bypass) then
    // manually registered. Since we can't bypass private constructor, DDSpan.create auto-registers.
    // The verify below checks that onCreateSpan was called exactly once (which happened in create).

    Mockito.verify(healthMetrics, Mockito.times(1)).onCreateSpan();

    span.finish();

    Mockito.verify(healthMetrics, Mockito.times(1)).onCreateTrace();
  }

  @Test
  void writeWhenWriteRunningSpansDisabledOnlyCompletedSpansAreWritten() throws Exception {
    CoreTracer stubTracer = Mockito.mock(CoreTracer.class, Mockito.RETURNS_DEEP_STUBS);
    CoreTracer.ConfigSnapshot traceConfig = Mockito.mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = Mockito.mock(PendingTraceBuffer.class);
    HealthMetrics healthMetrics = Mockito.mock(HealthMetrics.class);
    Mockito.when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    Mockito.when(stubTracer.getTagInterceptor()).thenReturn(null);
    Mockito.when(traceConfig.getServiceMapping()).thenReturn(java.util.Collections.emptyMap());
    Mockito.when(buffer.longRunningSpansEnabled()).thenReturn(true);
    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            buffer,
            Mockito.mock(TimeSource.class),
            null,
            false,
            healthMetrics);

    // span1: finished with USER_KEEP priority
    DDSpan span1 = DDSpan.create("test", 0L, createSimpleContextWithID(trace, 39), null);
    setDurationNano(span1, 31);
    span1.context().setSamplingPriority((int) PrioritySampling.USER_KEEP, MANUAL);

    // unfinished span
    DDSpan unfinishedSpan = DDSpan.create("test", 0L, createSimpleContextWithID(trace, 191), null);

    // span2: finished
    DDSpan span2 = DDSpan.create("test", 0L, createSimpleContextWithID(trace, 9999), null);
    setDurationNano(span2, 9191);

    ArrayList<DDSpan> traceToWrite = new ArrayList<>(0);

    int completedSpans = trace.enqueueSpansToWrite(traceToWrite, false);

    assertEquals(2, completedSpans);
    assertEquals(2, traceToWrite.size());
    assertTrue(traceToWrite.contains(span1));
    assertTrue(traceToWrite.contains(span2));
    assertEquals(1, trace.getSpans().size());
    assertEquals(
        unfinishedSpan,
        ((java.util.concurrent.ConcurrentLinkedDeque<DDSpan>) trace.getSpans()).pop());
  }

  @Test
  void writeWhenWriteRunningSpansEnabledCompleteAndRunningSpansAreWritten() throws Exception {
    CoreTracer stubTracer = Mockito.mock(CoreTracer.class, Mockito.RETURNS_DEEP_STUBS);
    CoreTracer.ConfigSnapshot traceConfig = Mockito.mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = Mockito.mock(PendingTraceBuffer.class);
    HealthMetrics healthMetrics = Mockito.mock(HealthMetrics.class);
    Mockito.when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    Mockito.when(stubTracer.getTagInterceptor()).thenReturn(null);
    Mockito.when(traceConfig.getServiceMapping()).thenReturn(java.util.Collections.emptyMap());
    Mockito.when(buffer.longRunningSpansEnabled()).thenReturn(true);
    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            buffer,
            Mockito.mock(TimeSource.class),
            null,
            false,
            healthMetrics);

    // span1: finished with USER_KEEP priority
    DDSpan span1 = DDSpan.create("test", 0L, createSimpleContextWithID(trace, 39), null);
    setDurationNano(span1, 31);
    span1.context().setSamplingPriority((int) PrioritySampling.USER_KEEP, MANUAL);

    // unfinished span
    DDSpan unfinishedSpan = DDSpan.create("test", 0L, createSimpleContextWithID(trace, 191), null);

    // span2: finished with service name override
    DDSpan span2 = DDSpan.create("test", 0L, createSimpleContextWithID(trace, 9999), null);
    span2.setServiceName("9191");
    setDurationNano(span2, 9191);

    // another unfinished span
    DDSpan unfinishedSpan2 =
        DDSpan.create("test", 0L, createSimpleContextWithID(trace, 77771), null);

    ArrayList<DDSpan> traceToWrite = new ArrayList<>(0);

    int completedSpans = trace.enqueueSpansToWrite(traceToWrite, true);

    assertEquals(2, completedSpans);
    assertEquals(4, traceToWrite.size());
    assertTrue(traceToWrite.contains(span1));
    assertTrue(traceToWrite.contains(span2));
    assertTrue(traceToWrite.contains(unfinishedSpan));
    assertTrue(traceToWrite.contains(unfinishedSpan2));
    assertEquals(2, trace.getSpans().size());
    assertTrue(trace.getSpans().contains(unfinishedSpan));
    assertTrue(trace.getSpans().contains(unfinishedSpan2));
  }
}
