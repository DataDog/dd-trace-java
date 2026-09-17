package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DDLLMObsSpanAgentAttributionTest {

  private static final String PAGENT_SPAN_ID_TAG = "_ml_obs_tag.pagent_span_id";
  private static final String PAGENT_NAME_TAG = "_ml_obs_tag.pagent_name";
  private static final Field SPAN_FIELD;

  private static CoreTracer tracer;

  static {
    try {
      SPAN_FIELD = DDLLMObsSpan.class.getDeclaredField("span");
      SPAN_FIELD.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
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

  private static DDLLMObsSpan newSpan(String kind, String name) {
    WellKnownTags tags =
        new WellKnownTags("runtime-id", "hostname", "test", "service", "version", "java");
    return new DDLLMObsSpan(kind, name, "test-ml-app", null, "service", tags);
  }

  private static AgentSpan innerSpan(DDLLMObsSpan llmObsSpan) throws IllegalAccessException {
    return (AgentSpan) SPAN_FIELD.get(llmObsSpan);
  }

  /** Starts a root APM span and activates it, so all LLMObs spans created within share a trace. */
  private static AgentScope startRootApmScope() {
    AgentSpan root = AgentTracer.get().buildSpan("apm", "http.server.request").start();
    return AgentTracer.activateSpan(root);
  }

  @Test
  void agentSpanStoresOwnIdAndNameAsPagent() throws Exception {
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "my-agent");
      try {
        AgentSpan inner = innerSpan(agentSpan);
        String parentAgentSpanId = (String) inner.getTag(PAGENT_SPAN_ID_TAG);
        String parentAgentName = (String) inner.getTag(PAGENT_NAME_TAG);

        assertEquals(String.valueOf(inner.getSpanId()), parentAgentSpanId);
        assertEquals("my-agent", parentAgentName);
      } finally {
        agentSpan.finish();
        apmScope.span().finish();
      }
    }
  }

  @Test
  void agentSpanNameIsPreservedRegardlessOfCharacters() throws Exception {
    // Agent names are stored as-is — no character restrictions since the only wire consumer
    // is the msgpack intake mapper, which accepts any string.
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "router~v2,résumé");
      try {
        AgentSpan inner = innerSpan(agentSpan);
        assertEquals(String.valueOf(inner.getSpanId()), inner.getTag(PAGENT_SPAN_ID_TAG));
        assertEquals("router~v2,résumé", inner.getTag(PAGENT_NAME_TAG));
      } finally {
        agentSpan.finish();
        apmScope.span().finish();
      }
    }
  }

  @Test
  void innerAgentNameOverridesOuterAgentNameForDescendants() throws Exception {
    // Descendants of an inner agent must see the inner agent's name, not the outer agent's.
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan outerAgent = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "outer-agent");
      try {
        DDLLMObsSpan innerAgent = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "inner-agent");
        try {
          DDLLMObsSpan tool = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "tool");
          try {
            AgentSpan toolInner = innerSpan(tool);
            AgentSpan innerAgentSpan = innerSpan(innerAgent);
            assertEquals(
                String.valueOf(innerAgentSpan.getSpanId()), toolInner.getTag(PAGENT_SPAN_ID_TAG));
            assertEquals("inner-agent", toolInner.getTag(PAGENT_NAME_TAG));
          } finally {
            tool.finish();
          }
        } finally {
          innerAgent.finish();
        }
      } finally {
        outerAgent.finish();
        apmScope.span().finish();
      }
    }
  }

  @Test
  void nonAgentChildUnderAgentInheritsAttribution() throws Exception {
    // All LLMObs spans share the same APM trace so the trace-ID consistency gate passes.
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "parent-agent");
      try {
        AgentSpan agentInner = innerSpan(agentSpan);
        String expectedParentAgentSpanId = String.valueOf(agentInner.getSpanId());

        // Created while agentSpan's ContextScope is active — should inherit attribution
        DDLLMObsSpan toolSpan = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "child-tool");
        try {
          AgentSpan toolInner = innerSpan(toolSpan);
          assertEquals(expectedParentAgentSpanId, toolInner.getTag(PAGENT_SPAN_ID_TAG));
          assertEquals("parent-agent", toolInner.getTag(PAGENT_NAME_TAG));
        } finally {
          toolSpan.finish();
        }
      } finally {
        agentSpan.finish();
        apmScope.span().finish();
      }
    }
  }

  @Test
  void transitiveInheritanceAgentToLlmToTool() throws Exception {
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "root-agent");
      try {
        AgentSpan agentInner = innerSpan(agentSpan);
        String expectedParentAgentSpanId = String.valueOf(agentInner.getSpanId());

        DDLLMObsSpan llmSpan = newSpan(Tags.LLMOBS_LLM_SPAN_KIND, "intermediate-llm");
        try {
          DDLLMObsSpan toolSpan = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "leaf-tool");
          try {
            AgentSpan toolInner = innerSpan(toolSpan);
            // Tool must point to the original agent, not the intermediate LLM span
            assertEquals(expectedParentAgentSpanId, toolInner.getTag(PAGENT_SPAN_ID_TAG));
            assertEquals("root-agent", toolInner.getTag(PAGENT_NAME_TAG));
          } finally {
            toolSpan.finish();
          }
        } finally {
          llmSpan.finish();
        }
      } finally {
        agentSpan.finish();
        apmScope.span().finish();
      }
    }
  }

  @Test
  void noAgentAncestorProducesNoPagentTags() throws Exception {
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan workflowSpan = newSpan(Tags.LLMOBS_WORKFLOW_SPAN_KIND, "standalone-workflow");
      try {
        AgentSpan inner = innerSpan(workflowSpan);
        assertNull(inner.getTag(PAGENT_SPAN_ID_TAG));
        assertNull(inner.getTag(PAGENT_NAME_TAG));
      } finally {
        workflowSpan.finish();
        apmScope.span().finish();
      }
    }
  }

  @Test
  void innerAgentFinishRestoresOuterAgentPropagationTags() throws Exception {
    // Outer agent's LLMObsContext is active. Inner agent starts (its own context pushed on top).
    // After inner agent finishes its context is popped, restoring outer agent's context.
    // A sibling span then sees outer agent's attribution via LLMObsContext.
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan outerAgent = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "outer-agent");
      try {
        AgentSpan outerInner = innerSpan(outerAgent);
        String outerParentAgentSpanId = String.valueOf(outerInner.getSpanId());

        DDLLMObsSpan innerAgent = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "inner-agent");
        innerAgent.finish();
        // After finish(), inner agent's LLMObsContext scope is closed — outer agent's is restored.

        // Sibling span created now sees outer agent's attribution via the restored LLMObsContext.
        DDLLMObsSpan siblingTool = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "sibling-tool");
        try {
          AgentSpan siblingInner = innerSpan(siblingTool);
          assertEquals(outerParentAgentSpanId, siblingInner.getTag(PAGENT_SPAN_ID_TAG));
          assertEquals("outer-agent", siblingInner.getTag(PAGENT_NAME_TAG));
        } finally {
          siblingTool.finish();
        }
      } finally {
        outerAgent.finish();
        apmScope.span().finish();
      }
    }
  }

  @Test
  void staleContextInDifferentTraceDoesNotInheritPagent() throws Exception {
    // Create an agent span in one APM trace, then create a non-agent span in a different APM
    // trace. The stale LLMObsContext from the first trace must not leak pagent attribution.
    AgentSpan firstRoot = AgentTracer.get().buildSpan("apm", "http.request.1").start();
    AgentScope firstScope = AgentTracer.activateSpan(firstRoot);

    DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "first-trace-agent");
    // agentSpan's LLMObsContext is now active in the current thread

    // Close the first APM scope (but do NOT close agentSpan's scope yet) — simulate a context
    // leak where the LLMObsContext outlives the APM scope it was created in.
    firstScope.close();

    // Start a fresh APM root (different trace) while the first trace's LLMObsContext is active
    AgentSpan secondRoot = AgentTracer.get().buildSpan("apm", "http.request.2").start();
    AgentScope secondScope = AgentTracer.activateSpan(secondRoot);
    try {
      DDLLMObsSpan toolInSecondTrace = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "second-trace-tool");
      try {
        // The tool span's APM parent is secondRoot (different trace from agentSpan).
        // The trace-ID gate must block inheritance from the stale LLMObsContext.
        AgentSpan toolInner = innerSpan(toolInSecondTrace);
        assertNull(toolInner.getTag(PAGENT_SPAN_ID_TAG));
        assertNull(toolInner.getTag(PAGENT_NAME_TAG));
      } finally {
        toolInSecondTrace.finish();
      }
    } finally {
      secondScope.close();
      secondRoot.finish();
      agentSpan.finish();
      firstRoot.finish();
    }
  }

  @Test
  void manifestNameOverridesSpanNameForPagent() throws Exception {
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "span-name");
      try {
        AgentSpan inner = innerSpan(agentSpan);
        assertEquals("span-name", inner.getTag(PAGENT_NAME_TAG));

        agentSpan.annotateAgentManifest(
            LLMObs.AgentManifest.builder().name("manifest-name").build());

        // The internal tag (used by the serializer for this span's agent_attribution) updates
        // to the manifest name. Context-propagated pagent name for children remains the span name.
        assertEquals("manifest-name", inner.getTag(PAGENT_NAME_TAG));
      } finally {
        agentSpan.finish();
        apmScope.span().finish();
      }
    }
  }
}
