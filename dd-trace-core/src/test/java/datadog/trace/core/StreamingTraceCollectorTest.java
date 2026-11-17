package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
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
import java.util.Collections;
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
    when(traceConfig.getServiceMapping()).thenReturn(Collections.emptyMap());
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
    verify(healthMetrics, never()).onFinishSpan();
  }

  @Test
  void finishCallsOnFinishSpanAndWritesOnce() {
    DDSpan span = createSpan();
    collector.registerSpan(span);

    span.finish();
    span.finish();

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
            Collections.emptyMap(),
            false,
            "",
            0,
            collector,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            PropagationTags.factory().empty()),
        null);
  }
}
