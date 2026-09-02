package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.TraceConfig;
import org.junit.jupiter.api.Test;

class AgentTracerTest {

  @Test
  void traceConfigOfNullSpanFallsBackToGlobalConfig() {
    assertSame(AgentTracer.traceConfig(), AgentTracer.traceConfig((AgentSpan) null));
  }

  @Test
  void traceConfigOfSpanWithOwnConfigReturnsThatConfig() {
    TraceConfig ownConfig = mock(TraceConfig.class);
    AgentSpan span = mock(AgentSpan.class);
    when(span.traceConfig()).thenReturn(ownConfig);

    assertSame(ownConfig, AgentTracer.traceConfig(span));
  }

  // Regression test: a span wrapping only an extracted remote context (e.g. built via
  // AgentSpan.fromSpanContext for a propagated-but-not-yet-local trace) reports a null
  // TraceConfig. Callers such as LogbackLoggerInstrumentation$CallAppendersAdvice do
  // `traceConfig(span).isLogsInjectionEnabled()` without a further null check, so
  // traceConfig(AgentSpan) must never return null for a non-null span.
  @Test
  void traceConfigOfExtractedSpanFallsBackToGlobalConfigInsteadOfNull() {
    AgentSpan extractedSpan = AgentSpan.fromSpanContext(new TagContext());

    assertNull(
        extractedSpan.traceConfig(),
        "test setup: expected the extracted span itself to report a null config");

    TraceConfig config = AgentTracer.traceConfig(extractedSpan);

    assertNotNull(config);
    assertSame(AgentTracer.traceConfig(), config);
  }
}
