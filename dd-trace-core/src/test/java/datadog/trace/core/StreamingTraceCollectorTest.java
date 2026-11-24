package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.time.TimeSource;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamingTraceCollectorTest {
  private CoreTracer tracer;
  private HealthMetrics healthMetrics;
  private StreamingTraceCollector collector;

  @BeforeAll
  static void checkJvm() {
    Assumptions.assumeFalse(
        JavaVirtualMachine.isOracleJDK8(),
        "Oracle JDK 1.8 did not merge the fix in JDK-8058322, which breaks mocking here.");
  }

  @BeforeEach
  void setup() {
    tracer = mock(CoreTracer.class);
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    when(tracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(emptyMap());
    healthMetrics = mock(HealthMetrics.class);
    collector =
        new StreamingTraceCollector.Factory(tracer, mock(TimeSource.class), healthMetrics)
            .create(DDTraceId.from(1));
  }

  @Test
  void registerSpanPassesTheSpanToOnCreateSpan() {
    DDSpan span = createSpan();

    collector.registerSpan(span);

    verify(healthMetrics, times(1)).onCreateSpan(span);
    verify(healthMetrics, never()).onEndSpan();
    verify(healthMetrics, never()).onFinishSpan();
  }

  @Test
  void finishCallsOnEndSpanAndOnFinishSpanAndWritesOnce() {
    DDSpan span = createSpan();
    collector.registerSpan(span);

    span.finish();
    span.finish();

    verify(healthMetrics, times(1)).onEndSpan();
    verify(healthMetrics, times(1)).onFinishSpan();
    verify(tracer, times(1)).write(any());
  }

  @Test
  void phasedFinishCallsOnEndSpanAndPublishCallsOnFinishSpan() {
    DDSpan span = createSpan();
    collector.registerSpan(span);

    assertTrue(span.phasedFinish());
    assertFalse(span.phasedFinish());

    verify(healthMetrics, times(1)).onEndSpan();
    verify(healthMetrics, never()).onFinishSpan();
    verify(tracer, never()).write(any());

    span.publish();
    span.publish();

    verify(healthMetrics, times(1)).onEndSpan();
    verify(healthMetrics, times(1)).onFinishSpan();
    verify(tracer, times(1)).write(any());
  }

  private DDSpan createSpan() {
    return new DDSpan(
        "test",
        0L,
        new DDSpanContext(
            DDTraceId.from(1),
            1,
            0,
            null,
            "",
            "",
            "",
            UNSET,
            "",
            emptyMap(),
            false,
            "",
            collector,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            PropagationTags.factory().empty()),
        null);
  }
}
