package datadog.trace.api.llmobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GenAiApmTagsTest {
  private static final String LLMOBS_TAG_PREFIX = "_ml_obs_tag.";
  private static final String LLMOBS_METRIC_PREFIX = "_ml_obs_metric.";

  private final Map<String, Object> tags = new HashMap<>();
  private AgentSpan span;

  @BeforeEach
  void setUp() {
    span = mock(AgentSpan.class);
    when(span.getTag(anyString())).thenAnswer(call -> tags.get(call.<String>getArgument(0)));
    when(span.setTag(anyString(), anyString()))
        .thenAnswer(
            call -> {
              tags.put(call.getArgument(0), call.getArgument(1));
              return span;
            });
    when(span.setMetric(any(CharSequence.class), anyDouble()))
        .thenAnswer(
            call -> {
              tags.put(call.<CharSequence>getArgument(0).toString(), call.getArgument(1));
              return span;
            });
  }

  @Test
  void llmSpanEmitsEveryScalarAndTokenUsage() {
    llmObsTag(Tags.SPAN_KIND, Tags.LLMOBS_LLM_SPAN_KIND);
    llmObsTag(LLMObsTags.MODEL_NAME, "gpt-4");
    llmObsTag(LLMObsTags.MODEL_PROVIDER, "OpenAI");
    llmObsTag(LLMObsTags.ML_APP, "my-app");
    llmObsTag(LLMObsTags.SESSION_ID, "sess-1");
    llmObsMetric("input_tokens", 10);
    llmObsMetric("output_tokens", 20);
    llmObsMetric("total_tokens", 30);
    llmObsMetric("cache_read_input_tokens", 4);
    llmObsMetric("cache_write_input_tokens", 5);
    llmObsMetric("reasoning_output_tokens", 6);

    GenAiApmTags.apply(span);

    assertEquals(Tags.LLMOBS_LLM_SPAN_KIND, tags.get(GenAiApmTags.OPERATION_NAME));
    assertEquals("gpt-4", tags.get(GenAiApmTags.REQUEST_MODEL));
    assertEquals("openai", tags.get(GenAiApmTags.PROVIDER_NAME));
    assertEquals("my-app", tags.get(GenAiApmTags.APPLICATION_NAME));
    assertEquals("sess-1", tags.get(GenAiApmTags.CONVERSATION_ID));
    assertEquals(10.0, tags.get(GenAiApmTags.USAGE_INPUT_TOKENS));
    assertEquals(20.0, tags.get(GenAiApmTags.USAGE_OUTPUT_TOKENS));
    assertEquals(30.0, tags.get(GenAiApmTags.USAGE_TOTAL_TOKENS));
    assertEquals(4.0, tags.get(GenAiApmTags.USAGE_CACHE_READ_INPUT_TOKENS));
    assertEquals(5.0, tags.get(GenAiApmTags.USAGE_CACHE_WRITE_INPUT_TOKENS));
    assertEquals(6.0, tags.get(GenAiApmTags.USAGE_REASONING_OUTPUT_TOKENS));
  }

  @Test
  void modelBackedSpanWithoutModelFallsBackToCustom() {
    llmObsTag(Tags.SPAN_KIND, Tags.LLMOBS_EMBEDDING_SPAN_KIND);

    GenAiApmTags.apply(span);

    assertEquals("custom", tags.get(GenAiApmTags.REQUEST_MODEL));
    assertEquals("custom", tags.get(GenAiApmTags.PROVIDER_NAME));
  }

  @Test
  void emptyModelValuesAreTreatedAsAbsent() {
    llmObsTag(Tags.SPAN_KIND, Tags.LLMOBS_LLM_SPAN_KIND);
    llmObsTag(LLMObsTags.MODEL_NAME, "");
    llmObsTag(LLMObsTags.MODEL_PROVIDER, "");
    llmObsTag(LLMObsTags.ML_APP, "");
    llmObsTag(LLMObsTags.SESSION_ID, "");

    GenAiApmTags.apply(span);

    assertEquals("custom", tags.get(GenAiApmTags.REQUEST_MODEL));
    assertEquals("custom", tags.get(GenAiApmTags.PROVIDER_NAME));
    assertNull(tags.get(GenAiApmTags.APPLICATION_NAME));
    assertNull(tags.get(GenAiApmTags.CONVERSATION_ID));
  }

  @Test
  void nonModelBackedSpanKeepsExplicitModelFieldsWithoutFallbacks() {
    llmObsTag(Tags.SPAN_KIND, Tags.LLMOBS_AGENT_SPAN_KIND);
    llmObsTag(LLMObsTags.MODEL_NAME, "gpt-4");
    llmObsTag(LLMObsTags.MODEL_PROVIDER, "OpenAI");

    GenAiApmTags.apply(span);

    assertEquals(Tags.LLMOBS_AGENT_SPAN_KIND, tags.get(GenAiApmTags.OPERATION_NAME));
    assertEquals("gpt-4", tags.get(GenAiApmTags.REQUEST_MODEL));
    assertEquals("openai", tags.get(GenAiApmTags.PROVIDER_NAME));
  }

  @Test
  void nonModelBackedSpanWithoutModelEmitsNoModelFields() {
    llmObsTag(Tags.SPAN_KIND, Tags.LLMOBS_WORKFLOW_SPAN_KIND);

    GenAiApmTags.apply(span);

    assertFalse(tags.containsKey(GenAiApmTags.REQUEST_MODEL));
    assertFalse(tags.containsKey(GenAiApmTags.PROVIDER_NAME));
  }

  @Test
  void nonModelBackedSpanDropsTokenUsage() {
    llmObsTag(Tags.SPAN_KIND, Tags.LLMOBS_TASK_SPAN_KIND);
    llmObsMetric("input_tokens", 10);

    GenAiApmTags.apply(span);

    assertFalse(tags.containsKey(GenAiApmTags.USAGE_INPUT_TOKENS));
  }

  @Test
  void nonNumericTokenMetricIsSkipped() {
    llmObsTag(Tags.SPAN_KIND, Tags.LLMOBS_LLM_SPAN_KIND);
    tags.put(LLMOBS_METRIC_PREFIX + "input_tokens", "not-a-number");

    GenAiApmTags.apply(span);

    assertFalse(tags.containsKey(GenAiApmTags.USAGE_INPUT_TOKENS));
  }

  @Test
  void spanWithoutLlmObsKindEmitsNothing() {
    llmObsTag(LLMObsTags.MODEL_NAME, "gpt-4");

    GenAiApmTags.apply(span);

    assertTrue(tags.keySet().stream().noneMatch(key -> key.startsWith("gen_ai.")));
  }

  @Test
  void nullSpanIsANoOp() {
    GenAiApmTags.apply(null);
  }

  @Test
  void withoutLlmObsEmitsScalarsButNoUsageOrConversation() {
    GenAiApmTags.applyWithoutLlmObs(span, Tags.LLMOBS_LLM_SPAN_KIND, "gpt-4", "OpenAI", "my-app");

    assertEquals(Tags.LLMOBS_LLM_SPAN_KIND, tags.get(GenAiApmTags.OPERATION_NAME));
    assertEquals("gpt-4", tags.get(GenAiApmTags.REQUEST_MODEL));
    assertEquals("openai", tags.get(GenAiApmTags.PROVIDER_NAME));
    assertEquals("my-app", tags.get(GenAiApmTags.APPLICATION_NAME));
    assertFalse(tags.containsKey(GenAiApmTags.CONVERSATION_ID));
    assertFalse(tags.containsKey(GenAiApmTags.USAGE_INPUT_TOKENS));
  }

  @Test
  void withoutLlmObsFallsBackToCustomForModelBackedKinds() {
    GenAiApmTags.applyWithoutLlmObs(span, Tags.LLMOBS_EMBEDDING_SPAN_KIND, null, "", "app");

    assertEquals("custom", tags.get(GenAiApmTags.REQUEST_MODEL));
    assertEquals("custom", tags.get(GenAiApmTags.PROVIDER_NAME));
  }

  @Test
  void withoutLlmObsIgnoresSpanWithNoOperationName() {
    GenAiApmTags.applyWithoutLlmObs(span, null, "gpt-4", "openai", "my-app");

    assertTrue(tags.keySet().stream().noneMatch(key -> key.startsWith("gen_ai.")));
  }

  @Test
  void withoutLlmObsNullSpanIsANoOp() {
    GenAiApmTags.applyWithoutLlmObs(null, Tags.LLMOBS_LLM_SPAN_KIND, "gpt-4", "openai", "app");
  }

  private void llmObsTag(String key, String value) {
    tags.put(LLMOBS_TAG_PREFIX + key, value);
  }

  private void llmObsMetric(String key, Number value) {
    tags.put(LLMOBS_METRIC_PREFIX + key, value);
  }
}
