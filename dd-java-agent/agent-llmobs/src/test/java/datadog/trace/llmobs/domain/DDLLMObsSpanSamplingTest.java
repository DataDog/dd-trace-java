package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Covers where the head-based sampling decision is made and where it is inherited: the decision is
 * computed once, at the root of an LLMObs trace, and every descendant reports it verbatim.
 */
class DDLLMObsSpanSamplingTest {
  private static final String SAMPLE_RATE_TAG = "_ml_obs_tag.sample_rate";
  private static final String SAMPLING_DECISION_TAG = "_ml_obs_tag.sampling_decision";

  private static final Field SPAN_FIELD;

  private static CoreTracer tracer;

  static {
    try {
      SPAN_FIELD = DDLLMObsSpan.class.getDeclaredField("span");
      SPAN_FIELD.setAccessible(true);
    } catch (ReflectiveOperationException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @BeforeAll
  static void installTracer() {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
  }

  @AfterAll
  static void closeTracer() {
    TracerInstaller.forceInstallGlobalTracer(null);
    tracer.close();
  }

  @Test
  void stampsNothingAtTheDefaultRate() throws IllegalAccessException {
    DDLLMObsSpan llmObsSpan = newSpan(new LLMObsSampler(1.0));
    try {
      AgentSpan span = spanOf(llmObsSpan);
      assertNull(span.getTag(SAMPLING_DECISION_TAG));
      assertNull(span.getTag(SAMPLE_RATE_TAG));
    } finally {
      llmObsSpan.finish();
    }
  }

  @Test
  void stampsRetainedDecisionOnRoot() throws IllegalAccessException {
    DDLLMObsSpan llmObsSpan = newSpan(new LLMObsSampler(1.0 - Math.ulp(1.0)));
    try {
      AgentSpan span = spanOf(llmObsSpan);
      // A rate just under 1.0 is "configured" but keeps every trace, so the decision is
      // deterministic without depending on the generated trace ID.
      assertEquals("1", span.getTag(SAMPLING_DECISION_TAG));
      assertNotNull(span.getTag(SAMPLE_RATE_TAG));
    } finally {
      llmObsSpan.finish();
    }
  }

  @Test
  void stampsDroppedDecisionOnRoot() throws IllegalAccessException {
    DDLLMObsSpan llmObsSpan = newSpan(new LLMObsSampler(0.0));
    try {
      AgentSpan span = spanOf(llmObsSpan);
      assertEquals("0", span.getTag(SAMPLING_DECISION_TAG));
      assertEquals("0", span.getTag(SAMPLE_RATE_TAG));
    } finally {
      llmObsSpan.finish();
    }
  }

  @Test
  void childInheritsTheRootDecisionInsteadOfRecomputingIt() throws IllegalAccessException {
    // The child's sampler drops everything. If the decision were recomputed per span, the child
    // would report "0" and the trace would be torn in half at the intake.
    DDLLMObsSpan root = newSpan(new LLMObsSampler(1.0 - Math.ulp(1.0)));
    try {
      AgentSpan rootSpan = spanOf(root);
      // Inheritance is gated on the two spans sharing an APM trace, so the root's APM span has to
      // be active for the child to be started under it.
      try (AgentScope ignored = AgentTracer.activateSpan(rootSpan)) {
        DDLLMObsSpan child = newSpan(new LLMObsSampler(0.0));
        try {
          AgentSpan childSpan = spanOf(child);
          assertEquals("1", childSpan.getTag(SAMPLING_DECISION_TAG));
          assertEquals(
              rootSpan.getTag(SAMPLE_RATE_TAG),
              childSpan.getTag(SAMPLE_RATE_TAG),
              "every span in a trace must report the rate the decision was made at");
        } finally {
          child.finish();
        }
      }
    } finally {
      root.finish();
    }
  }

  @Test
  void childOfAnUnsampledRootStaysUnsampled() throws IllegalAccessException {
    // Symmetric case, at the single rate a process actually runs at: nothing is stamped anywhere in
    // the trace. Note that an absent parent decision is by construction the same signal as "I am
    // the root", so a child under a differently-configured sampler would decide for itself — that
    // cannot arise in practice because the sampler is resolved once per process.
    DDLLMObsSpan root = newSpan(new LLMObsSampler(1.0));
    try {
      try (AgentScope ignored = AgentTracer.activateSpan(spanOf(root))) {
        DDLLMObsSpan child = newSpan(new LLMObsSampler(1.0));
        try {
          AgentSpan childSpan = spanOf(child);
          assertNull(childSpan.getTag(SAMPLING_DECISION_TAG));
          assertNull(childSpan.getTag(SAMPLE_RATE_TAG));
        } finally {
          child.finish();
        }
      }
    } finally {
      root.finish();
    }
  }

  private static DDLLMObsSpan newSpan(LLMObsSampler sampler) {
    WellKnownTags tags =
        new WellKnownTags("runtime-id", "hostname", "test", "service", "version", "java");
    return new DDLLMObsSpan(
        Tags.LLMOBS_LLM_SPAN_KIND, "span", "ml-app", null, "service", tags, sampler);
  }

  private static AgentSpan spanOf(DDLLMObsSpan llmObsSpan) throws IllegalAccessException {
    return (AgentSpan) SPAN_FIELD.get(llmObsSpan);
  }
}
