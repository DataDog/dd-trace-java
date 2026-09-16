package datadog.trace.common.sampling;

import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.GeneralConfig.APM_TRACING_ENABLED;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.api.ProductTraceSource;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies that AI Guard traces are kept regardless of APM/ASM configuration. */
public class AIGuardSamplingTest extends DDCoreJavaSpecification {

  private ListWriter writer;
  private CoreTracer tracer;

  @BeforeEach
  void setUp() {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
  }

  @Test
  void aiGuardTraceIsKeptWhenApmEnabled() throws Exception {
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "operation").start();
    span.setTag(Tags.AI_GUARD_KEEP, true);
    span.setTag(Tags.AI_GUARD_EVENT, true);
    span.setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.AI_GUARD);
    span.finish();
    writer.waitForTraces(1);
    assertEquals(USER_KEEP, (int) span.getSamplingPriority());
    assertDecisionMakerIsAiGuard(span);
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void aiGuardTraceIsKeptWhenApmAndAsmDisabled() throws Exception {
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "operation").start();
    span.setTag(Tags.AI_GUARD_KEEP, true);
    span.setTag(Tags.AI_GUARD_EVENT, true);
    span.setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.AI_GUARD);
    span.finish();
    writer.waitForTraces(1);
    assertEquals(USER_KEEP, (int) span.getSamplingPriority());
    assertDecisionMakerIsAiGuard(span);
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  void aiGuardTraceIsKeptInAsmStandaloneMode() throws Exception {
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "operation").start();
    span.setTag(Tags.AI_GUARD_KEEP, true);
    span.setTag(Tags.AI_GUARD_EVENT, true);
    span.setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.AI_GUARD);
    span.finish();
    writer.waitForTraces(1);
    assertEquals(USER_KEEP, (int) span.getSamplingPriority());
    assertDecisionMakerIsAiGuard(span);
  }

  /** _dd.p.ts alone (without AI_GUARD_KEEP) must not bypass the sampler. */
  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  void propagatedTraceSourceAloneDoesNotBypassSampler() throws Exception {
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "op").start();
    span.setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.AI_GUARD);
    span.finish();
    writer.waitForTraces(1);

    // _dd.p.ts alone must not force-keep the trace; only AI_GUARD_KEEP does that.
    assertNotEquals(USER_KEEP, (int) span.getSamplingPriority());
  }

  /**
   * AI Guard traces must be kept even when AsmStandaloneSampler has exhausted its rate-limit slot.
   */
  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  void aiGuardTraceIsKeptAfterRateLimitSlotIsExhausted() throws Exception {
    // consume the first allowed slot
    DDSpan first = (DDSpan) tracer.buildSpan("datadog", "op").start();
    first.finish();
    writer.waitForTraces(1);

    // AI Guard trace must still be force-kept even though the rate-limit slot is gone
    DDSpan aiGuard = (DDSpan) tracer.buildSpan("datadog", "op").start();
    aiGuard.setTag(Tags.AI_GUARD_KEEP, true);
    aiGuard.setTag(Tags.AI_GUARD_EVENT, true);
    aiGuard.setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.AI_GUARD);
    aiGuard.finish();
    writer.waitForTraces(2);

    assertEquals(USER_KEEP, (int) aiGuard.getSamplingPriority());
    assertDecisionMakerIsAiGuard(aiGuard);
  }

  private static void assertDecisionMakerIsAiGuard(DDSpan span) {
    Map<String, String> ptags = span.spanContext().getPropagationTags().createTagMap();
    assertEquals("-13", ptags.get("_dd.p.dm"), "_dd.p.dm must be -13 (AI Guard decision maker)");
    assertEquals("20", ptags.get("_dd.p.ts"), "_dd.p.ts must have AI Guard bit set (0x20)");
  }
}
