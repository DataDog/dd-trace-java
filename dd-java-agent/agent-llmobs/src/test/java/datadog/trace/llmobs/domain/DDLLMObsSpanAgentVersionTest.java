package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.tooling.TracerInstaller;
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
 * Covers agent_version propagation to a versioned agent's subtree, mirroring the session_id
 * inheritance behavior in {@code DDLLMObsSpanTest}, but with the additional requirement that an
 * explicit version always wins for its own subtree (so a nested agent's own version overrides an
 * ancestor's).
 */
class DDLLMObsSpanAgentVersionTest {
  private static final String AGENT_VERSION_TAG = "_ml_obs_tag." + LLMObsTags.AGENT_VERSION;

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
  void agentSpanWithExplicitVersionTagsItself() {
    DDLLMObsSpan agent = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "agent1", "v3");
    try {
      assertEquals("v3", spanOf(agent).getTag(AGENT_VERSION_TAG));
    } finally {
      agent.finish();
    }
  }

  @Test
  void childSpanInheritsAgentVersionFromParentContext() {
    DDLLMObsSpan agent = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "agent1", "v3");
    try (AgentScope ignored = AgentTracer.activateSpan(spanOf(agent))) {
      DDLLMObsSpan child = llmObsSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "tool1", null);
      try {
        assertEquals("v3", spanOf(child).getTag(AGENT_VERSION_TAG));
      } finally {
        child.finish();
      }
    } finally {
      agent.finish();
    }
  }

  @Test
  void grandchildTransitivelyInheritsAgentVersionThroughIntermediateSpan() {
    DDLLMObsSpan agent = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "agent1", "v3");
    try (AgentScope agentScope = AgentTracer.activateSpan(spanOf(agent))) {
      DDLLMObsSpan workflow = llmObsSpan(Tags.LLMOBS_WORKFLOW_SPAN_KIND, "workflow1", null);
      try (AgentScope workflowScope = AgentTracer.activateSpan(spanOf(workflow))) {
        DDLLMObsSpan grandchild = llmObsSpan(Tags.LLMOBS_LLM_SPAN_KIND, "llm1", null);
        try {
          assertEquals("v3", spanOf(grandchild).getTag(AGENT_VERSION_TAG));
        } finally {
          grandchild.finish();
        }
      } finally {
        workflow.finish();
      }
    } finally {
      agent.finish();
    }
  }

  @Test
  void nestedAgentWithOwnVersionOverridesForItsOwnSubtree() {
    DDLLMObsSpan outerAgent = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "outer-agent", "v1");
    try (AgentScope outerScope = AgentTracer.activateSpan(spanOf(outerAgent))) {
      DDLLMObsSpan innerAgent = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "inner-agent", "v2");
      try (AgentScope innerScope = AgentTracer.activateSpan(spanOf(innerAgent))) {
        assertEquals("v2", spanOf(innerAgent).getTag(AGENT_VERSION_TAG));

        DDLLMObsSpan child = llmObsSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "inner-tool", null);
        try {
          assertEquals(
              "v2",
              spanOf(child).getTag(AGENT_VERSION_TAG),
              "child of the nested agent must inherit the nested agent's own version, not the outer one");
        } finally {
          child.finish();
        }
      } finally {
        innerAgent.finish();
      }
    } finally {
      outerAgent.finish();
    }
  }

  @Test
  void noVersionSetAnywhereMeansNoTagOnAnySpanInTheSubtree() {
    DDLLMObsSpan agent = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "agent1", null);
    try (AgentScope agentScope = AgentTracer.activateSpan(spanOf(agent))) {
      DDLLMObsSpan child = llmObsSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "tool1", null);
      try {
        assertNull(spanOf(agent).getTag(AGENT_VERSION_TAG));
        assertNull(spanOf(child).getTag(AGENT_VERSION_TAG));
      } finally {
        child.finish();
      }
    } finally {
      agent.finish();
    }
  }

  @Test
  void childDoesNotInheritAgentVersionWhenStaleContextIsFromADifferentTrace() {
    // Simulates a stale LLMObsContext (e.g. leaked across an async boundary): the parent's
    // context is attached, but its AgentScope is deliberately NOT activated, so the next span
    // started begins a fresh trace and the trace-consistency gate must skip inheritance.
    DDLLMObsSpan agent = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "stale-agent", "stale-v1");
    try {
      DDLLMObsSpan child = llmObsSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "tool1", null);
      try (AgentScope childScope = AgentTracer.activateSpan(spanOf(child))) {
        assertNotEquals(
            spanOf(agent).getTraceId(),
            spanOf(child).getTraceId(),
            "sanity: traces must differ for this scenario to be meaningful");
        assertNull(spanOf(child).getTag(AGENT_VERSION_TAG));

        DDLLMObsSpan grandchild = llmObsSpan(Tags.LLMOBS_LLM_SPAN_KIND, "llm1", null);
        try {
          assertNull(
              spanOf(grandchild).getTag(AGENT_VERSION_TAG),
              "the stale agent_version must not leak transitively into a grandchild either");
        } finally {
          grandchild.finish();
        }
      } finally {
        child.finish();
      }
    } finally {
      agent.finish();
    }
  }

  private static DDLLMObsSpan llmObsSpan(String kind, String name, String agentVersion) {
    WellKnownTags tags =
        new WellKnownTags("runtime-id", "hostname", "test", "service", "version", "java");
    return new DDLLMObsSpan(kind, name, "test-ml-app", null, "service", tags, agentVersion);
  }

  private static AgentSpan spanOf(DDLLMObsSpan llmObsSpan) {
    try {
      return (AgentSpan) SPAN_FIELD.get(llmObsSpan);
    } catch (IllegalAccessException error) {
      throw new AssertionError(error);
    }
  }
}
