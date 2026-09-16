package datadog.trace.llmobs;

import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ENABLED;
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

class LLMObsSystemTest extends DDJavaSpecification {

  /**
   * LLM Observability spans are backed by tracer spans, so the subsystem must stay off when tracing
   * is disabled. Starting it would install the real SDK implementation on top of the no-op tracer,
   * whose {@code buildSpan()} returns null, and every span-starting call would throw.
   */
  @WithConfig(key = LLMOBS_ENABLED, value = "true")
  @WithConfig(key = TRACE_ENABLED, value = "false")
  @Test
  void staysNoOpWhenTracingIsDisabled() {
    // The null SharedCommunicationObjects doubles as an assertion that start() returns before
    // touching it: any use would throw.
    LLMObsSystem.start(null, null);

    assertSame(
        NoOpLLMObsSpan.INSTANCE, LLMObs.startLLMSpan("span", "model", "provider", null, null));
  }

  /**
   * CI Visibility installs a {@code CoreTracer} even with tracing disabled (see {@code
   * TracerInstaller#installGlobalTracer}), so the guard above must not fire in that combination —
   * the tracer LLM Observability needs does exist.
   */
  @WithConfig(key = LLMOBS_ENABLED, value = "true")
  @WithConfig(key = TRACE_ENABLED, value = "false")
  @WithConfig(key = CIVISIBILITY_ENABLED, value = "true")
  @Test
  void startsWhenCiVisibilityInstallsTheTracer() {
    // Reaching the null SharedCommunicationObjects is the assertion: start() only dereferences it
    // after the guard, so the NPE proves the subsystem was not short-circuited.
    assertThrows(NullPointerException.class, () -> LLMObsSystem.start(null, null));
  }
}
