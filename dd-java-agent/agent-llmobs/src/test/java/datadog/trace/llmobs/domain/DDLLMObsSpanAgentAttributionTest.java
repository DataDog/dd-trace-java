package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObsPropagationAccess;
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

  @Test
  void agentSpanStoresOwnIdAndNameAsPagent() throws Exception {
    DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "my-agent");
    try {
      AgentSpan inner = innerSpan(agentSpan);
      String pagentSpanId = (String) inner.getTag(PAGENT_SPAN_ID_TAG);
      String pagentName = (String) inner.getTag(PAGENT_NAME_TAG);

      assertEquals(String.valueOf(inner.getSpanId()), pagentSpanId);
      assertEquals("my-agent", pagentName);
    } finally {
      agentSpan.finish();
    }
  }

  @Test
  void agentSpanWithUnsafeNameStoresIdButNullName() throws Exception {
    // Comma is a separator in x-datadog-tags header — disallowed
    DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "bad,agent");
    try {
      AgentSpan inner = innerSpan(agentSpan);
      String pagentSpanId = (String) inner.getTag(PAGENT_SPAN_ID_TAG);
      Object pagentName = inner.getTag(PAGENT_NAME_TAG);

      assertEquals(String.valueOf(inner.getSpanId()), pagentSpanId);
      assertNull(pagentName);
    } finally {
      agentSpan.finish();
    }
  }

  @Test
  void nonAgentChildUnderAgentInheritsAttribution() throws Exception {
    DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "parent-agent");
    try {
      AgentSpan agentInner = innerSpan(agentSpan);
      String expectedPagentSpanId = String.valueOf(agentInner.getSpanId());

      // Created while agentSpan's ContextScope is active — should inherit attribution
      DDLLMObsSpan toolSpan = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "child-tool");
      try {
        AgentSpan toolInner = innerSpan(toolSpan);
        assertEquals(expectedPagentSpanId, toolInner.getTag(PAGENT_SPAN_ID_TAG));
        assertEquals("parent-agent", toolInner.getTag(PAGENT_NAME_TAG));
      } finally {
        toolSpan.finish();
      }
    } finally {
      agentSpan.finish();
    }
  }

  @Test
  void transitiveInheritanceAgentToLlmToTool() throws Exception {
    DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "root-agent");
    try {
      AgentSpan agentInner = innerSpan(agentSpan);
      String expectedPagentSpanId = String.valueOf(agentInner.getSpanId());

      DDLLMObsSpan llmSpan = newSpan(Tags.LLMOBS_LLM_SPAN_KIND, "intermediate-llm");
      try {
        DDLLMObsSpan toolSpan = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "leaf-tool");
        try {
          AgentSpan toolInner = innerSpan(toolSpan);
          // Tool must point to the original agent, not the intermediate LLM span
          assertEquals(expectedPagentSpanId, toolInner.getTag(PAGENT_SPAN_ID_TAG));
          assertEquals("root-agent", toolInner.getTag(PAGENT_NAME_TAG));
        } finally {
          toolSpan.finish();
        }
      } finally {
        llmSpan.finish();
      }
    } finally {
      agentSpan.finish();
    }
  }

  @Test
  void noAgentAncestorProducesNoPagentTags() throws Exception {
    DDLLMObsSpan workflowSpan = newSpan(Tags.LLMOBS_WORKFLOW_SPAN_KIND, "standalone-workflow");
    try {
      AgentSpan inner = innerSpan(workflowSpan);
      assertNull(inner.getTag(PAGENT_SPAN_ID_TAG));
      assertNull(inner.getTag(PAGENT_NAME_TAG));
    } finally {
      workflowSpan.finish();
    }
  }

  @Test
  void distributedParentPagentValuesAreInherited() throws Exception {
    // Simulate a distributed parent: an APM root span with pagent propagation tags already set
    // (e.g. injected by an upstream service during HTTP propagation).
    AgentSpan rootApmSpan = AgentTracer.get().buildSpan("apm", "http.server.request").start();
    AgentScope apmScope = AgentTracer.activateSpan(rootApmSpan);
    try {
      // Directly stamp the pagent values on the root span context via LLMObsPropagationAccess
      LLMObsPropagationAccess access = (LLMObsPropagationAccess) rootApmSpan.spanContext();
      access.setParentAgentSpanId("1234567890abcdef");
      access.setParentAgentName("upstream-agent");

      // No LLMObs context is active — should fall through to the distributed path
      DDLLMObsSpan llmSpan = newSpan(Tags.LLMOBS_LLM_SPAN_KIND, "downstream-llm");
      try {
        AgentSpan llmInner = innerSpan(llmSpan);
        assertEquals("1234567890abcdef", llmInner.getTag(PAGENT_SPAN_ID_TAG));
        assertEquals("upstream-agent", llmInner.getTag(PAGENT_NAME_TAG));
      } finally {
        llmSpan.finish();
      }
    } finally {
      apmScope.close();
      rootApmSpan.finish();
    }
  }
}
