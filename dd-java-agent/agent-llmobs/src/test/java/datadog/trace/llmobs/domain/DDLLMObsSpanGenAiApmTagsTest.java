package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.GenAiApmTags;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Covers the {@code gen_ai.*} attributes a manual LLM Observability span emits at finish. */
class DDLLMObsSpanGenAiApmTagsTest {
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
  void llmSpanEmitsEveryScalarAndTokenUsage() {
    DDLLMObsSpan llm = llmObsSpan(Tags.LLMOBS_LLM_SPAN_KIND, "chat", "sess-1");
    llm.setTag(LLMObsTags.MODEL_NAME, "gpt-4");
    llm.setTag(LLMObsTags.MODEL_PROVIDER, "OpenAI");
    Map<String, Number> metrics = new HashMap<>();
    metrics.put("input_tokens", 10);
    metrics.put("output_tokens", 20);
    metrics.put("total_tokens", 30);
    metrics.put("cache_read_input_tokens", 4);
    metrics.put("cache_write_input_tokens", 5);
    metrics.put("reasoning_output_tokens", 6);
    llm.setMetrics(metrics);
    llm.finish();

    AgentSpan span = spanOf(llm);
    assertEquals(Tags.LLMOBS_LLM_SPAN_KIND, span.getTag(GenAiApmTags.OPERATION_NAME));
    assertEquals("gpt-4", span.getTag(GenAiApmTags.REQUEST_MODEL));
    assertEquals("openai", span.getTag(GenAiApmTags.PROVIDER_NAME));
    assertEquals("test-ml-app", span.getTag(GenAiApmTags.APPLICATION_NAME));
    assertEquals("sess-1", span.getTag(GenAiApmTags.CONVERSATION_ID));
    assertEquals(10.0, span.getTag(GenAiApmTags.USAGE_INPUT_TOKENS));
    assertEquals(20.0, span.getTag(GenAiApmTags.USAGE_OUTPUT_TOKENS));
    assertEquals(30.0, span.getTag(GenAiApmTags.USAGE_TOTAL_TOKENS));
    assertEquals(4.0, span.getTag(GenAiApmTags.USAGE_CACHE_READ_INPUT_TOKENS));
    assertEquals(5.0, span.getTag(GenAiApmTags.USAGE_CACHE_WRITE_INPUT_TOKENS));
    assertEquals(6.0, span.getTag(GenAiApmTags.USAGE_REASONING_OUTPUT_TOKENS));
  }

  @Test
  void modelBackedSpanWithoutModelFallsBackToCustom() {
    DDLLMObsSpan embedding = llmObsSpan(Tags.LLMOBS_EMBEDDING_SPAN_KIND, "embed", null);
    embedding.finish();

    AgentSpan span = spanOf(embedding);
    assertEquals("custom", span.getTag(GenAiApmTags.REQUEST_MODEL));
    assertEquals("custom", span.getTag(GenAiApmTags.PROVIDER_NAME));
    assertNull(span.getTag(GenAiApmTags.CONVERSATION_ID));
  }

  @Test
  void nonModelBackedSpanEmitsNoModelFieldsOrTokenUsage() {
    DDLLMObsSpan workflow = llmObsSpan(Tags.LLMOBS_WORKFLOW_SPAN_KIND, "flow", null);
    workflow.setMetric("input_tokens", 10);
    workflow.finish();

    AgentSpan span = spanOf(workflow);
    assertEquals(Tags.LLMOBS_WORKFLOW_SPAN_KIND, span.getTag(GenAiApmTags.OPERATION_NAME));
    assertNull(span.getTag(GenAiApmTags.REQUEST_MODEL));
    assertNull(span.getTag(GenAiApmTags.PROVIDER_NAME));
    assertNull(span.getTag(GenAiApmTags.USAGE_INPUT_TOKENS));
  }

  @Test
  void nonModelBackedSpanKeepsExplicitModelFields() {
    DDLLMObsSpan agent = llmObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "agent", null);
    agent.setTag(LLMObsTags.MODEL_NAME, "gpt-4");
    agent.setTag(LLMObsTags.MODEL_PROVIDER, "OpenAI");
    agent.finish();

    AgentSpan span = spanOf(agent);
    assertEquals("gpt-4", span.getTag(GenAiApmTags.REQUEST_MODEL));
    assertEquals("openai", span.getTag(GenAiApmTags.PROVIDER_NAME));
  }

  private static DDLLMObsSpan llmObsSpan(String kind, String name, String sessionId) {
    WellKnownTags tags =
        new WellKnownTags("runtime-id", "hostname", "test", "service", "version", "java");
    return new DDLLMObsSpan(kind, name, "test-ml-app", sessionId, "service", tags);
  }

  private static AgentSpan spanOf(DDLLMObsSpan llmObsSpan) {
    try {
      return (AgentSpan) SPAN_FIELD.get(llmObsSpan);
    } catch (IllegalAccessException error) {
      throw new AssertionError(error);
    }
  }
}
