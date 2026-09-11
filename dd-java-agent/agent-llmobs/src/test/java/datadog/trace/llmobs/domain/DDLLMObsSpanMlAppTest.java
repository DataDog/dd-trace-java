package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObsTags;
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
 * Covers ml_app resolution, which mirrors session_id and agent_version inheritance: an explicit
 * value always wins, otherwise the enclosing LLMObs span's value applies, and the service default
 * is reached only when nothing else names an application. Applying the default any earlier would
 * make "the caller passed nothing" indistinguishable from "the caller named the service", which is
 * what would discard an ml_app propagated from another service.
 */
class DDLLMObsSpanMlAppTest {
  private static final String ML_APP_TAG = "_ml_obs_tag." + LLMObsTags.ML_APP;

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
  void explicitMlAppTagsTheSpan() {
    DDLLMObsSpan agent = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "agent1", "research-bot");
    try {
      assertEquals("research-bot", spanOf(agent).getTag(ML_APP_TAG));
    } finally {
      agent.finish();
    }
  }

  @Test
  void childSpanInheritsMlAppFromParentContext() {
    DDLLMObsSpan agent = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "agent1", "research-bot");
    try (AgentScope ignored = AgentTracer.activateSpan(spanOf(agent))) {
      DDLLMObsSpan child = llmObsSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "tool1", null);
      try {
        assertEquals("research-bot", spanOf(child).getTag(ML_APP_TAG));
      } finally {
        child.finish();
      }

      // An empty ml_app is not a value, so it inherits the same way a null one does.
      DDLLMObsSpan blank = llmObsSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "tool2", "");
      try {
        assertEquals("research-bot", spanOf(blank).getTag(ML_APP_TAG));
      } finally {
        blank.finish();
      }
    } finally {
      agent.finish();
    }
  }

  @Test
  void explicitMlAppOverridesAnInheritedOneForItsOwnSubtree() {
    DDLLMObsSpan outer = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "agent1", "research-bot");
    try (AgentScope ignored = AgentTracer.activateSpan(spanOf(outer))) {
      DDLLMObsSpan inner = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "agent2", "summarizer");
      try (AgentScope innerScope = AgentTracer.activateSpan(spanOf(inner))) {
        DDLLMObsSpan child = llmObsSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "tool1", null);
        try {
          assertEquals("summarizer", spanOf(inner).getTag(ML_APP_TAG));
          assertEquals("summarizer", spanOf(child).getTag(ML_APP_TAG));
        } finally {
          child.finish();
        }
      } finally {
        inner.finish();
      }
    } finally {
      outer.finish();
    }
  }

  @Test
  void fallsBackToTheServiceDefaultWhenNothingNamesAnApplication() {
    DDLLMObsSpan span = llmObsSpan(Tags.LLMOBS_WORKFLOW_SPAN_KIND, "work", null);
    try {
      assertEquals(Config.get().getLlmObsMlApp(), spanOf(span).getTag(ML_APP_TAG));
    } finally {
      span.finish();
    }
  }

  private static DDLLMObsSpan llmObsSpan(String kind, String name, String mlApp) {
    WellKnownTags tags =
        new WellKnownTags("runtime-id", "hostname", "test", "service", "version", "java");
    return new DDLLMObsSpan(kind, name, mlApp, null, "service", tags);
  }

  private static AgentSpan spanOf(DDLLMObsSpan llmObsSpan) {
    try {
      return (AgentSpan) SPAN_FIELD.get(llmObsSpan);
    } catch (IllegalAccessException error) {
      throw new AssertionError(error);
    }
  }
}
