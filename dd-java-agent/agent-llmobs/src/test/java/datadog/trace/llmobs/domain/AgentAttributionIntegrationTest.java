package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsInternal;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test: exercises agent attribution through the PUBLIC LLMObs API (LLMObs.start*Span)
 * rather than constructing DDLLMObsSpan directly. This validates the full stack from user-facing
 * API to wire-ready span tags.
 */
class AgentAttributionIntegrationTest {

  private static final String PAGENT_SPAN_ID_TAG = "_ml_obs_tag.pagent_span_id";
  private static final String PAGENT_NAME_TAG = "_ml_obs_tag.pagent_name";
  private static final Field SPAN_FIELD;

  private static CoreTracer tracer;
  private static LLMObs.LLMObsSpanFactory previousFactory;

  static {
    try {
      SPAN_FIELD = DDLLMObsSpan.class.getDeclaredField("span");
      SPAN_FIELD.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeAll
  static void setUp() throws Exception {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);

    // Capture whatever factory is currently registered so we can restore it after the test.
    Field factoryField = LLMObs.class.getDeclaredField("SPAN_FACTORY");
    factoryField.setAccessible(true);
    previousFactory = (LLMObs.LLMObsSpanFactory) factoryField.get(null);

    // Register a factory backed by the real DDLLMObsSpan — same as what LLMObsSystem installs
    // when the agent is attached with DD_LLMOBS_ENABLED=true.
    WellKnownTags tags =
        new WellKnownTags("runtime-id", "hostname", "test", "my-service", "v1", "java");
    LLMObsInternal.setSpanFactory(new RealSpanFactory("test-ml-app", tags));
  }

  @AfterAll
  static void tearDown() {
    // Restore the previous factory and shut down the tracer.
    LLMObsInternal.setSpanFactory(previousFactory);
    TracerInstaller.forceInstallGlobalTracer(null);
    tracer.close();
  }

  // ─────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────

  private static AgentSpan innerSpan(LLMObsSpan llmObsSpan) throws IllegalAccessException {
    return (AgentSpan) SPAN_FIELD.get(llmObsSpan);
  }

  private static String parentAgentSpanId(LLMObsSpan span) throws Exception {
    return (String) innerSpan(span).getTag(PAGENT_SPAN_ID_TAG);
  }

  private static String parentAgentName(LLMObsSpan span) throws Exception {
    return (String) innerSpan(span).getTag(PAGENT_NAME_TAG);
  }

  // ─────────────────────────────────────────────────────────────────
  // Tests
  // ─────────────────────────────────────────────────────────────────

  @Test
  void agentSpanAttributesItself() throws Exception {
    LLMObsSpan agent = LLMObs.startAgentSpan("router", null, null);
    try {
      AgentSpan inner = innerSpan(agent);
      assertEquals(String.valueOf(inner.getSpanId()), parentAgentSpanId(agent));
      assertEquals("router", parentAgentName(agent));
    } finally {
      agent.finish();
    }
  }

  @Test
  void llmSpanUnderAgentInheritsAttribution() throws Exception {
    LLMObsSpan agent = LLMObs.startAgentSpan("router", null, null);
    try {
      AgentSpan agentInner = innerSpan(agent);
      String expectedId = String.valueOf(agentInner.getSpanId());

      LLMObsSpan llm = LLMObs.startLLMSpan("gpt-4-call", "gpt-4", "openai", null, null);
      try {
        assertEquals(expectedId, parentAgentSpanId(llm));
        assertEquals("router", parentAgentName(llm));
      } finally {
        llm.finish();
      }
    } finally {
      agent.finish();
    }
  }

  @Test
  void toolSpanTransitivelyInheritsFromAgent() throws Exception {
    LLMObsSpan agent = LLMObs.startAgentSpan("orchestrator", null, null);
    try {
      AgentSpan agentInner = innerSpan(agent);
      String expectedId = String.valueOf(agentInner.getSpanId());

      LLMObsSpan llm = LLMObs.startLLMSpan("intermediate-llm", "gpt-4", "openai", null, null);
      try {
        LLMObsSpan tool = LLMObs.startToolSpan("web-search", null, null);
        try {
          // Tool must point to the agent, not the intermediate LLM.
          assertEquals(expectedId, parentAgentSpanId(tool));
          assertEquals("orchestrator", parentAgentName(tool));
        } finally {
          tool.finish();
        }
      } finally {
        llm.finish();
      }
    } finally {
      agent.finish();
    }
  }

  @Test
  void innerAgentOverridesOuterAgentForDescendants() throws Exception {
    LLMObsSpan outerAgent = LLMObs.startAgentSpan("outer-router", null, null);
    try {
      LLMObsSpan innerAgent = LLMObs.startAgentSpan("inner-executor", null, null);
      try {
        AgentSpan innerAgentSpan = innerSpan(innerAgent);
        String expectedId = String.valueOf(innerAgentSpan.getSpanId());

        LLMObsSpan tool = LLMObs.startToolSpan("search", null, null);
        try {
          // Tool must point to inner-executor, not outer-router.
          assertEquals(expectedId, parentAgentSpanId(tool));
          assertEquals("inner-executor", parentAgentName(tool));
        } finally {
          tool.finish();
        }
      } finally {
        innerAgent.finish();
      }

      // After inner agent finishes, a sibling span must see outer agent's attribution.
      AgentSpan outerAgentSpan = innerSpan(outerAgent);
      String outerExpectedId = String.valueOf(outerAgentSpan.getSpanId());

      LLMObsSpan siblingLlm =
          LLMObs.startLLMSpan("post-executor-llm", "gpt-4", "openai", null, null);
      try {
        assertEquals(outerExpectedId, parentAgentSpanId(siblingLlm));
        assertEquals("outer-router", parentAgentName(siblingLlm));
      } finally {
        siblingLlm.finish();
      }
    } finally {
      outerAgent.finish();
    }
  }

  @Test
  void noAgentAncestorProducesNoAttributionTags() throws Exception {
    LLMObsSpan llm = LLMObs.startLLMSpan("standalone-llm", "gpt-4", "openai", null, null);
    try {
      assertNull(parentAgentSpanId(llm));
      assertNull(parentAgentName(llm));
    } finally {
      llm.finish();
    }
  }

  @Test
  void agentWithUnsafeNameHasNullParentAgentName() throws Exception {
    // Comma is a delimiter in x-datadog-tags — must be rejected.
    LLMObsSpan agent = LLMObs.startAgentSpan("bad,agent", null, null);
    try {
      assertNotNull(parentAgentSpanId(agent)); // ID is still set
      assertNull(parentAgentName(agent)); // name is null because unsafe

      // Children inherit the ID but also get null name.
      LLMObsSpan tool = LLMObs.startToolSpan("child-tool", null, null);
      try {
        assertEquals(parentAgentSpanId(agent), parentAgentSpanId(tool));
        assertNull(parentAgentName(tool));
      } finally {
        tool.finish();
      }
    } finally {
      agent.finish();
    }
  }

  @Test
  void agentWithTildeInNameHasNullParentAgentName() throws Exception {
    // Tilde (0x7E) is rewritten by W3C tracestate encoding — must be rejected.
    LLMObsSpan agent = LLMObs.startAgentSpan("agent~v2", null, null);
    try {
      assertNotNull(parentAgentSpanId(agent));
      assertNull(parentAgentName(agent));
    } finally {
      agent.finish();
    }
  }

  /**
   * Realistic multi-agent scenario: a router agent dispatches work to an executor agent. Spans
   * under each agent must attribute to their nearest agent ancestor.
   *
   * <pre>
   * [router-agent]
   *   [planning-llm]   → pagent = router-agent
   *   [executor-agent]
   *     [tool-call]    → pagent = executor-agent
   *     [result-llm]   → pagent = executor-agent
   *   [summary-llm]    → pagent = router-agent  (after executor finishes)
   * </pre>
   */
  @Test
  void realisticMultiAgentWorkflowAttributionIsCorrect() throws Exception {
    LLMObsSpan router = LLMObs.startAgentSpan("router-agent", null, null);
    try {
      AgentSpan routerInner = innerSpan(router);
      String routerId = String.valueOf(routerInner.getSpanId());

      LLMObsSpan planningLlm = LLMObs.startLLMSpan("planning-llm", "gpt-4", "openai", null, null);
      try {
        assertEquals(routerId, parentAgentSpanId(planningLlm));
        assertEquals("router-agent", parentAgentName(planningLlm));
      } finally {
        planningLlm.finish();
      }

      LLMObsSpan executor = LLMObs.startAgentSpan("executor-agent", null, null);
      try {
        AgentSpan executorInner = innerSpan(executor);
        String executorId = String.valueOf(executorInner.getSpanId());

        LLMObsSpan toolCall = LLMObs.startToolSpan("tool-call", null, null);
        try {
          assertEquals(executorId, parentAgentSpanId(toolCall));
          assertEquals("executor-agent", parentAgentName(toolCall));
        } finally {
          toolCall.finish();
        }

        LLMObsSpan resultLlm = LLMObs.startLLMSpan("result-llm", "gpt-4", "openai", null, null);
        try {
          assertEquals(executorId, parentAgentSpanId(resultLlm));
          assertEquals("executor-agent", parentAgentName(resultLlm));
        } finally {
          resultLlm.finish();
        }
      } finally {
        executor.finish();
      }

      // After executor finishes, summary-llm should attribute back to router.
      LLMObsSpan summaryLlm = LLMObs.startLLMSpan("summary-llm", "gpt-4", "openai", null, null);
      try {
        assertEquals(routerId, parentAgentSpanId(summaryLlm));
        assertEquals("router-agent", parentAgentName(summaryLlm));
      } finally {
        summaryLlm.finish();
      }
    } finally {
      router.finish();
    }
  }

  // ─────────────────────────────────────────────────────────────────
  // Factory — mirrors LLMObsSystem.LLMObsManualSpanFactory
  // ─────────────────────────────────────────────────────────────────

  private static final class RealSpanFactory implements LLMObs.LLMObsSpanFactory {
    private final String defaultMlApp;
    private final String serviceName;
    private final WellKnownTags wellKnownTags;

    RealSpanFactory(String defaultMlApp, WellKnownTags wellKnownTags) {
      this.defaultMlApp = defaultMlApp;
      this.serviceName = wellKnownTags.getService().toString();
      this.wellKnownTags = wellKnownTags;
    }

    private String mlApp(@Nullable String override) {
      return (override != null && !override.isEmpty()) ? override : defaultMlApp;
    }

    @Override
    public LLMObsSpan startLLMSpan(
        String spanName,
        String modelName,
        String modelProvider,
        @Nullable String mlApp,
        @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_LLM_SPAN_KIND, spanName, mlApp(mlApp), sessionId, serviceName, wellKnownTags);
    }

    @Override
    public LLMObsSpan startAgentSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_AGENT_SPAN_KIND,
          spanName,
          mlApp(mlApp),
          sessionId,
          serviceName,
          wellKnownTags);
    }

    @Override
    public LLMObsSpan startToolSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_TOOL_SPAN_KIND,
          spanName,
          mlApp(mlApp),
          sessionId,
          serviceName,
          wellKnownTags);
    }

    @Override
    public LLMObsSpan startTaskSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_TASK_SPAN_KIND,
          spanName,
          mlApp(mlApp),
          sessionId,
          serviceName,
          wellKnownTags);
    }

    @Override
    public LLMObsSpan startWorkflowSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_WORKFLOW_SPAN_KIND,
          spanName,
          mlApp(mlApp),
          sessionId,
          serviceName,
          wellKnownTags);
    }

    @Override
    public LLMObsSpan startEmbeddingSpan(
        String spanName,
        @Nullable String mlApp,
        @Nullable String modelProvider,
        @Nullable String modelName,
        @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_EMBEDDING_SPAN_KIND,
          spanName,
          mlApp(mlApp),
          sessionId,
          serviceName,
          wellKnownTags);
    }

    @Override
    public LLMObsSpan startRetrievalSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId) {
      return new DDLLMObsSpan(
          Tags.LLMOBS_RETRIEVAL_SPAN_KIND,
          spanName,
          mlApp(mlApp),
          sessionId,
          serviceName,
          wellKnownTags);
    }
  }
}
