package datadog.trace.llmobs.domain;

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.api.config.GeneralConfig.GLOBAL_TAGS;
import static datadog.trace.api.llmobs.LLMObsTags.SESSION_ID;
import static datadog.trace.bootstrap.instrumentation.api.Tags.LLMOBS_AGENT_SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.LLMOBS_EMBEDDING_SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.LLMOBS_LLM_SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.LLMOBS_RETRIEVAL_SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.LLMOBS_TASK_SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.LLMOBS_TOOL_SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.LLMOBS_WORKFLOW_SPAN_KIND;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceApiInfo;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.api.telemetry.LLMObsMetricCollector;
import datadog.trace.api.telemetry.LLMObsMetricCollector.LLMObsMetric;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class DDLLMObsSpanTest extends DDJavaSpecification {
  // Prefix for tags
  private static final String LLMOBS_TAG_PREFIX = "_ml_obs_tag.";
  // Prefix for metrics
  private static final String LLMOBS_METRIC_PREFIX = "_ml_obs_metric.";

  // internal tags to be prefixed
  private static final String INPUT = LLMOBS_TAG_PREFIX + "input";
  private static final String INPUT_PROMPT = LLMOBS_TAG_PREFIX + "input_prompt";
  private static final String OUTPUT = LLMOBS_TAG_PREFIX + "output";
  private static final String METADATA = LLMOBS_TAG_PREFIX + LLMObsTags.METADATA;
  private static final String TOOL_DEFINITIONS = LLMOBS_TAG_PREFIX + LLMObsTags.TOOL_DEFINITIONS;
  private static final String PROMPT_TRACKING_INSTRUMENTATION_METHOD =
      LLMOBS_TAG_PREFIX + "prompt_tracking_instrumentation_method";
  private static final String UNNAMED_PROMPT_ID = "test-ml-app_unnamed-prompt";

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
    tracer =
        CoreTracer.builder()
            .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
            .build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
  }

  @AfterAll
  static void closeTracer() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @BeforeEach
  void setup() {
    assertNull(
        tracer.activeSpan(),
        () -> "Span is active before test has started: " + tracer.activeSpan());
    tracer.flush();
    LLMObsMetricCollector.get().resetForTesting();
  }

  @AfterEach
  void flushTracer() {
    tracer.flush();
  }

  @Test
  void testSpanSimple() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_WORKFLOW_SPAN_KIND, "test-span");

    // when:
    String input = "test input";
    String output = "test output";
    // initial set
    test.annotateIO(input, output);
    test.setMetadata(mapOf("sport", "baseball", "price_data", singletonMap("gpt4", 100)));
    test.setMetrics(singletonMap("rank", (Number) 1));
    test.setMetric("likelihood", 0.1);
    test.setTag("DOMAIN", "north-america");
    test.setTags(mapOf("bulk1", 1, "bulk2", "2"));
    String errMsg = "mr brady";
    test.setErrorMessage(errMsg);

    // then:
    AgentSpan innerSpan = agentSpan(test);
    assertEquals(LLMOBS_WORKFLOW_SPAN_KIND, innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"));

    assertNull(innerSpan.getTag("input"));
    assertEquals(input, innerSpan.getTag(INPUT));
    assertNull(innerSpan.getTag("output"));
    assertEquals(output, innerSpan.getTag(OUTPUT));

    assertNull(innerSpan.getTag("metadata"));
    Map<String, Object> expectedMetadata =
        mapOf("sport", "baseball", "price_data", singletonMap("gpt4", 100));
    assertEquals(expectedMetadata, innerSpan.getTag(METADATA));

    assertNull(innerSpan.getTag("rank"));
    Object rankMetric = innerSpan.getTag(LLMOBS_METRIC_PREFIX + "rank");
    assertInstanceOf(Number.class, rankMetric);
    assertEquals(1, ((Number) rankMetric).intValue());

    assertNull(innerSpan.getTag("likelihood"));
    Object likelihoodMetric = innerSpan.getTag(LLMOBS_METRIC_PREFIX + "likelihood");
    assertInstanceOf(Number.class, likelihoodMetric);
    assertEquals(0.1, ((Number) likelihoodMetric).doubleValue());

    assertNull(innerSpan.getTag("DOMAIN"));
    Object domain = innerSpan.getTag(LLMOBS_TAG_PREFIX + "DOMAIN");
    assertInstanceOf(String.class, domain);
    assertEquals("north-america", domain);

    assertNull(innerSpan.getTag("bulk1"));
    Object tagBulk1 = innerSpan.getTag(LLMOBS_TAG_PREFIX + "bulk1");
    assertInstanceOf(Number.class, tagBulk1);
    assertEquals(1, ((Number) tagBulk1).intValue());

    assertNull(innerSpan.getTag("bulk2"));
    Object tagBulk2 = innerSpan.getTag(LLMOBS_TAG_PREFIX + "bulk2");
    assertInstanceOf(String.class, tagBulk2);
    assertEquals("2", tagBulk2);

    assertTrue(innerSpan.isError());
    assertInstanceOf(String.class, innerSpan.getTag(DDTags.ERROR_MSG));
    assertEquals(errMsg, innerSpan.getTag(DDTags.ERROR_MSG));

    assertWellKnownTags(innerSpan);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void testSpanWithOverwrites() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_AGENT_SPAN_KIND, "test-span");
    try {
      // when:
      String input = "test input";
      // initial set
      test.annotateIO(input, "test output");
      // this should be a no-op
      test.annotateIO("", "");
      // this should replace the initial output
      List<Map<String, Object>> expectedOutput =
          singletonList(mapOf("role", "user", "content", "how much is gas"));
      test.annotateIO(null, (List) expectedOutput);

      // initial set
      test.setMetadata(mapOf("sport", "baseball", "price_data", singletonMap("gpt4", 100)));
      // this should replace baseball with hockey
      test.setMetadata(singletonMap("sport", "hockey"));
      // this should add a new key
      test.setMetadata(singletonMap("temperature", 30));

      // initial set
      test.setMetrics(singletonMap("rank", (Number) 1));
      // this should replace the metric
      test.setMetric("rank", 10);

      // initial set
      test.setTag("DOMAIN", "north-america");
      // add and replace
      test.setTags(mapOf("bulk1", 1, "DOMAIN", "europe"));

      String throwableMsg = "false positive";
      test.addThrowable(new Throwable(throwableMsg));
      test.setError(false);

      // then:
      AgentSpan innerSpan = agentSpan(test);
      assertEquals(LLMOBS_AGENT_SPAN_KIND, innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"));

      assertNull(innerSpan.getTag("input"));
      assertEquals(input, innerSpan.getTag(INPUT));
      assertNull(innerSpan.getTag("output"));
      assertEquals(expectedOutput, innerSpan.getTag(OUTPUT));

      assertNull(innerSpan.getTag("metadata"));
      Map<String, Object> expectedMetadata =
          mapOf("sport", "hockey", "price_data", singletonMap("gpt4", 100));
      expectedMetadata.put("temperature", 30);
      assertEquals(expectedMetadata, innerSpan.getTag(METADATA));

      assertNull(innerSpan.getTag("rank"));
      Object rankMetric = innerSpan.getTag(LLMOBS_METRIC_PREFIX + "rank");
      assertInstanceOf(Number.class, rankMetric);
      assertEquals(10, ((Number) rankMetric).intValue());

      assertNull(innerSpan.getTag("DOMAIN"));
      Object domain = innerSpan.getTag(LLMOBS_TAG_PREFIX + "DOMAIN");
      assertInstanceOf(String.class, domain);
      assertEquals("europe", domain);

      assertNull(innerSpan.getTag("bulk1"));
      Object tagBulk1 = innerSpan.getTag(LLMOBS_TAG_PREFIX + "bulk1");
      assertInstanceOf(Number.class, tagBulk1);
      assertEquals(1, ((Number) tagBulk1).intValue());

      assertFalse(innerSpan.isError());
      assertInstanceOf(String.class, innerSpan.getTag(DDTags.ERROR_MSG));
      assertEquals(throwableMsg, innerSpan.getTag(DDTags.ERROR_MSG));
      Object errorStack = innerSpan.getTag(DDTags.ERROR_STACK);
      assertInstanceOf(String.class, errorStack);
      assertTrue(((String) errorStack).contains(throwableMsg));

      assertWellKnownTags(innerSpan);
    } finally {
      test.finish();
    }
  }

  @Test
  void testLlmSpanStringInputFormattedToMessages() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");

    // when:
    String input = "test input";
    String output = "test output";
    // initial set
    test.annotateIO(input, output);

    // then:
    AgentSpan innerSpan = agentSpan(test);
    assertEquals(LLMOBS_LLM_SPAN_KIND, innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"));

    assertNull(innerSpan.getTag("input"));
    Object spanInput = innerSpan.getTag(INPUT);
    assertInstanceOf(List.class, spanInput);
    assertEquals(1, ((List<?>) spanInput).size());
    assertInstanceOf(LLMObs.LLMMessage.class, ((List<?>) spanInput).get(0));
    LLMObs.LLMMessage expectedInputMsg = LLMObs.LLMMessage.from("unknown", input);
    assertEquals(input, expectedInputMsg.getContent());
    assertEquals("unknown", expectedInputMsg.getRole());
    assertNull(expectedInputMsg.getToolCalls());

    assertNull(innerSpan.getTag("output"));
    Object spanOutput = innerSpan.getTag(OUTPUT);
    assertInstanceOf(List.class, spanOutput);
    assertEquals(1, ((List<?>) spanOutput).size());
    assertInstanceOf(LLMObs.LLMMessage.class, ((List<?>) spanOutput).get(0));
    LLMObs.LLMMessage expectedOutputMsg = LLMObs.LLMMessage.from("unknown", output);
    assertEquals(output, expectedOutputMsg.getContent());
    assertEquals("unknown", expectedOutputMsg.getRole());
    assertNull(expectedOutputMsg.getToolCalls());

    assertWellKnownTags(innerSpan);
  }

  @Test
  void testLlmSpanWithMessages() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");

    // when:
    LLMObs.LLMMessage inputMsg = LLMObs.LLMMessage.from("user", "input");
    LLMObs.LLMMessage outputMsg =
        LLMObs.LLMMessage.from(
            "assistant",
            "output",
            singletonList(
                LLMObs.ToolCall.from(
                    "weather-tool", "function", "6176241000", singletonMap("location", "paris"))));
    // initial set
    test.annotateIO(singletonList(inputMsg), singletonList(outputMsg));

    // then:
    AgentSpan innerSpan = agentSpan(test);
    assertEquals(LLMOBS_LLM_SPAN_KIND, innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"));

    assertNull(innerSpan.getTag("input"));
    Object spanInput = innerSpan.getTag(INPUT);
    assertInstanceOf(List.class, spanInput);
    assertEquals(1, ((List<?>) spanInput).size());
    LLMObs.LLMMessage spanInputMsg =
        assertInstanceOf(LLMObs.LLMMessage.class, ((List<?>) spanInput).get(0));
    assertEquals(inputMsg.getContent(), spanInputMsg.getContent());
    assertEquals("user", spanInputMsg.getRole());
    assertNull(spanInputMsg.getToolCalls());

    assertNull(innerSpan.getTag("output"));
    Object spanOutput = innerSpan.getTag(OUTPUT);
    assertInstanceOf(List.class, spanOutput);
    assertEquals(1, ((List<?>) spanOutput).size());
    LLMObs.LLMMessage spanOutputMsg =
        assertInstanceOf(LLMObs.LLMMessage.class, ((List<?>) spanOutput).get(0));
    assertEquals(outputMsg.getContent(), spanOutputMsg.getContent());
    assertEquals("assistant", spanOutputMsg.getRole());
    assertEquals(1, spanOutputMsg.getToolCalls().size());
    LLMObs.ToolCall toolCall = spanOutputMsg.getToolCalls().get(0);
    assertEquals("weather-tool", toolCall.getName());
    assertEquals("function", toolCall.getType());
    assertEquals("6176241000", toolCall.getToolId());
    Map<String, Object> expectedToolArgs = singletonMap("location", "paris");
    assertEquals(expectedToolArgs, toolCall.getArguments());

    assertWellKnownTags(innerSpan);
  }

  @Test
  void testLlmSpanWithPrompt() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");
    List<LLMObs.LLMMessage> messages =
        singletonList(LLMObs.LLMMessage.from("user", "What is the weather in Paris?"));
    LLMObs.Prompt prompt =
        LLMObs.Prompt.builder()
            .id("weather-prompt")
            .version("1.0.0")
            .template("What is the weather in {{city}}?")
            .variables(singletonMap("city", "Paris"))
            .tags(singletonMap("team", "weather"))
            .contextVariables(singletonList("forecast"))
            .queryVariables(singletonList("city"))
            .build();
    test.annotateIO(messages, null);

    // when:
    test.annotatePrompt(prompt);

    // then:
    AgentSpan innerSpan = agentSpan(test);
    assertEquals(messages, innerSpan.getTag(INPUT));
    Map<String, Object> expectedPrompt = new HashMap<>();
    expectedPrompt.put("id", "weather-prompt");
    expectedPrompt.put("version", "1.0.0");
    expectedPrompt.put("template", "What is the weather in {{city}}?");
    expectedPrompt.put("variables", singletonMap("city", "Paris"));
    expectedPrompt.put("tags", singletonMap("team", "weather"));
    expectedPrompt.put("_dd_context_variable_keys", singletonList("forecast"));
    expectedPrompt.put("_dd_query_variable_keys", singletonList("city"));
    assertEquals(expectedPrompt, innerSpan.getTag(INPUT_PROMPT));
    assertEquals("annotated", innerSpan.getTag(PROMPT_TRACKING_INSTRUMENTATION_METHOD));
  }

  @Test
  void testLlmSpanWithChatPrompt() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");
    List<LLMObs.LLMMessage> chatTemplate =
        asList(
            LLMObs.LLMMessage.from("system", "You are a weather assistant."),
            LLMObs.LLMMessage.from("user", "What is the weather in {{city}}?"));
    LLMObs.Prompt prompt =
        LLMObs.Prompt.builder().id("weather-prompt").template(chatTemplate).build();

    // when:
    test.annotatePrompt(prompt);

    // then:
    Map<String, Object> expectedPrompt = new HashMap<>();
    expectedPrompt.put("id", "weather-prompt");
    expectedPrompt.put(
        "chat_template",
        asList(
            mapOf("role", "system", "content", "You are a weather assistant."),
            mapOf("role", "user", "content", "What is the weather in {{city}}?")));
    expectedPrompt.put("_dd_context_variable_keys", singletonList("context"));
    expectedPrompt.put("_dd_query_variable_keys", singletonList("question"));
    assertEquals(expectedPrompt, agentSpan(test).getTag(INPUT_PROMPT));
  }

  @Test
  void inputMessagesAddedAfterAPromptPreserveThePrompt() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");
    LLMObs.Prompt prompt = LLMObs.Prompt.builder().id("weather-prompt").build();
    List<LLMObs.LLMMessage> messages =
        singletonList(LLMObs.LLMMessage.from("user", "What is the weather?"));
    test.annotatePrompt(prompt);

    // when:
    test.annotateIO(messages, null);

    // then:
    AgentSpan innerSpan = agentSpan(test);
    assertEquals("weather-prompt", promptTag(innerSpan).get("id"));
    assertEquals(messages, innerSpan.getTag(INPUT));
  }

  @Test
  void promptAnnotationsMergeWithPriorPromptAttributes() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");
    test.annotatePrompt(
        LLMObs.Prompt.builder()
            .id("weather-prompt")
            .template("Weather in {{city}}")
            .variables(singletonMap("city", "Paris"))
            .build());

    // when:
    test.annotatePrompt(
        LLMObs.Prompt.builder().version("2.0.0").tags(singletonMap("team", "weather")).build());

    // then:
    Map<String, Object> prompt = promptTag(agentSpan(test));
    assertEquals("weather-prompt", prompt.get("id"));
    assertEquals("2.0.0", prompt.get("version"));
    assertEquals("Weather in {{city}}", prompt.get("template"));
    assertEquals(singletonMap("city", "Paris"), prompt.get("variables"));
    assertEquals(singletonMap("team", "weather"), prompt.get("tags"));
  }

  @Test
  void laterPromptTemplateReplacesThePriorTemplateRepresentation() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");
    test.annotatePrompt(LLMObs.Prompt.builder().template("Weather in {{city}}").build());

    // when:
    test.annotatePrompt(
        LLMObs.Prompt.builder()
            .template(singletonList(LLMObs.LLMMessage.from("user", "Weather in {{city}}")))
            .build());

    // then:
    Map<String, Object> chatPrompt = promptTag(agentSpan(test));
    assertNull(chatPrompt.get("template"));
    assertEquals(
        singletonList(mapOf("role", "user", "content", "Weather in {{city}}")),
        chatPrompt.get("chat_template"));

    // when:
    test.annotatePrompt(LLMObs.Prompt.builder().template("Forecast for {{city}}").build());

    // then:
    Map<String, Object> textPrompt = promptTag(agentSpan(test));
    assertEquals("Forecast for {{city}}", textPrompt.get("template"));
    assertNull(textPrompt.get("chat_template"));
  }

  @Test
  void promptWithoutAnIdUsesTheMlAppDefault() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");

    // when:
    test.annotatePrompt(LLMObs.Prompt.builder().template("Hello").build());

    // then:
    assertEquals(UNNAMED_PROMPT_ID, promptTag(agentSpan(test)).get("id"));
  }

  @Test
  void emptyPromptIdIsTreatedAsMissing() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");

    // when:
    test.annotatePrompt(LLMObs.Prompt.builder().id("").build());

    // then:
    assertEquals(UNNAMED_PROMPT_ID, promptTag(agentSpan(test)).get("id"));

    // when:
    test.annotatePrompt(LLMObs.Prompt.builder().id("weather-prompt").build());
    test.annotatePrompt(LLMObs.Prompt.builder().id("").version("2.0.0").build());

    // then:
    Map<String, Object> prompt = promptTag(agentSpan(test));
    assertEquals("weather-prompt", prompt.get("id"));
    assertEquals("2.0.0", prompt.get("version"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        LLMOBS_AGENT_SPAN_KIND,
        LLMOBS_TOOL_SPAN_KIND,
        LLMOBS_TASK_SPAN_KIND,
        LLMOBS_WORKFLOW_SPAN_KIND,
        LLMOBS_EMBEDDING_SPAN_KIND,
        LLMOBS_RETRIEVAL_SPAN_KIND
      })
  void promptsAreIgnoredOnNonLlmSpans(String spanKind) throws Exception {
    LLMObsSpan test = llmObsSpan(spanKind, "test-span");
    try {
      // when:
      test.annotatePrompt(LLMObs.Prompt.builder().id("weather-prompt").build());

      // then:
      AgentSpan innerSpan = agentSpan(test);
      assertNull(innerSpan.getTag(INPUT));
      assertNull(innerSpan.getTag(INPUT_PROMPT));
      assertNull(innerSpan.getTag(PROMPT_TRACKING_INSTRUMENTATION_METHOD));
    } finally {
      test.finish();
    }
  }

  @Test
  void promptsCannotBeAnnotatedAfterTheSpanFinishes() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");
    test.annotatePrompt(LLMObs.Prompt.builder().id("first").build());
    test.finish();

    // when:
    test.annotatePrompt(LLMObs.Prompt.builder().id("second").build());

    // then:
    assertEquals("first", promptTag(agentSpan(test)).get("id"));
  }

  @Test
  void testLlmSpanWithToolDefinitions() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");
    Map<String, Object> schema =
        mapOf(
            "type",
            "object",
            "properties",
            singletonMap("location", singletonMap("type", "string")));
    LLMObs.ToolDefinition toolDefinition =
        LLMObs.ToolDefinition.from("get_weather", "Get the weather by location", schema, "1.2.3");

    // when:
    test.setToolDefinitions(
        asList(
            toolDefinition,
            LLMObs.ToolDefinition.from("get_time"),
            LLMObs.ToolDefinition.from(""),
            LLMObs.ToolDefinition.from(null),
            null));

    // then:
    Object toolDefinitions = agentSpan(test).getTag(TOOL_DEFINITIONS);
    assertInstanceOf(List.class, toolDefinitions);
    List<?> toolDefinitionList = (List<?>) toolDefinitions;
    assertEquals(2, toolDefinitionList.size());
    assertSame(toolDefinition, toolDefinitionList.get(0));
    LLMObs.ToolDefinition getTime = (LLMObs.ToolDefinition) toolDefinitionList.get(1);
    assertEquals("get_time", getTime.getName());
    assertNull(getTime.getDescription());
    assertNull(getTime.getSchema());
    assertNull(getTime.getVersion());
  }

  @Test
  void toolDefinitionsOverwritePriorAnnotationsAndIgnoreInvalidDefinitions() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");
    test.setToolDefinitions(singletonList(LLMObs.ToolDefinition.from("first")));

    // when:
    test.setToolDefinitions(singletonList(LLMObs.ToolDefinition.from("second")));

    // then:
    AgentSpan innerSpan = agentSpan(test);
    assertEquals(singletonList("second"), toolDefinitionNames(innerSpan));

    // when:
    test.setToolDefinitions(
        asList(LLMObs.ToolDefinition.from(""), LLMObs.ToolDefinition.from(null), null));

    // then:
    assertEquals(singletonList("second"), toolDefinitionNames(innerSpan));
  }

  @ParameterizedTest
  @NullAndEmptySource
  void nullAndEmptyToolDefinitionListsDoNotOverwritePriorAnnotations(
      List<LLMObs.ToolDefinition> toolDefinitions) throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");
    test.setToolDefinitions(singletonList(LLMObs.ToolDefinition.from("first")));

    // when:
    test.setToolDefinitions(toolDefinitions);

    // then:
    assertEquals(singletonList("first"), toolDefinitionNames(agentSpan(test)));
  }

  @Test
  void toolDefinitionsCannotBeAnnotatedAfterTheSpanFinishes() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "test-span");
    test.setToolDefinitions(singletonList(LLMObs.ToolDefinition.from("first")));
    test.finish();

    // when:
    test.setToolDefinitions(singletonList(LLMObs.ToolDefinition.from("second")));

    // then:
    assertEquals(singletonList("first"), toolDefinitionNames(agentSpan(test)));
  }

  @Test
  void finishRecordsSpanFinishedTelemetryWhenLlmObsEnabled() {
    LLMObsMetricCollector collector = LLMObsMetricCollector.get();

    // when:
    llmObsSpan(LLMOBS_WORKFLOW_SPAN_KIND, "workflow-span").finish();
    collector.prepareMetrics();

    // then:
    List<LLMObsMetric> metrics = new ArrayList<>(collector.drain());
    assertEquals(1, metrics.size());

    // and:
    LLMObsMetric metric = metrics.get(0);
    assertEquals("mlobs", metric.namespace);
    assertEquals("span.finished", metric.metricName);
    assertEquals("count", metric.type);
    assertEquals(1L, metric.value.longValue());
    assertTrue(metric.tags.contains("integration:llmobs"));
    assertTrue(metric.tags.contains("span_kind:workflow"));
    assertTrue(metric.tags.contains("autoinstrumented:0"));
    assertTrue(metric.tags.contains("is_root_span:1"));
  }

  @Test
  void finishRecordsSpanFinishedTelemetryForNonRootSpanWhenLlmObsEnabled() {
    LLMObsMetricCollector collector = LLMObsMetricCollector.get();

    // when:
    runUnderTrace(
        "parent",
        () -> {
          llmObsSpan(LLMOBS_LLM_SPAN_KIND, "child-llm").finish();
          return null;
        });
    collector.prepareMetrics();

    // then:
    List<LLMObsMetric> metrics = new ArrayList<>(collector.drain());
    assertEquals(1, metrics.size());

    // and:
    LLMObsMetric metric = metrics.get(0);
    assertEquals("mlobs", metric.namespace);
    assertEquals("span.finished", metric.metricName);
    assertEquals("count", metric.type);
    assertEquals(1L, metric.value.longValue());
    assertTrue(metric.tags.contains("integration:llmobs"));
    assertTrue(metric.tags.contains("span_kind:llm"));
    assertTrue(metric.tags.contains("autoinstrumented:0"));
    assertTrue(metric.tags.contains("is_root_span:0"));
  }

  @TableTest({
    "scenario           | sessionId   | expectedHasSessionIdTag",
    "with session id    | session-123 | has_session_id:1       ",
    "without session id |             | has_session_id:0       "
  })
  void spanHasExpectedSessionTagAndTelemetry(String sessionId, String expectedHasSessionIdTag) {
    LLMObsMetricCollector collector = LLMObsMetricCollector.get();

    // when:
    llmObsSpan(LLMOBS_WORKFLOW_SPAN_KIND, "workflow-span", sessionId).finish();
    collector.prepareMetrics();

    // then:
    List<LLMObsMetric> metrics = new ArrayList<>(collector.drain());
    assertEquals(1, metrics.size());

    // and:
    LLMObsMetric metric = metrics.get(0);
    assertEquals("mlobs", metric.namespace);
    assertEquals("span.finished", metric.metricName);
    assertTrue(metric.tags.contains(expectedHasSessionIdTag));
  }

  @Test
  void childLlmObsSpanInheritsSessionIdFromParentContextWhenNoneIsPassed() throws Exception {
    String expectedSessionId = "session-abc-123";
    LLMObsSpan parent = llmObsSpan(LLMOBS_WORKFLOW_SPAN_KIND, "parent-workflow", expectedSessionId);
    // Activate the parent's ContextScope so the child span is created in the same trace.
    // Without this, the child gets a fresh trace_id and the trace-consistency gate in
    // DDLLMObsSpan would (correctly) skip session_id inheritance.
    ContextScope parentScope = AgentTracer.activateSpan(agentSpan(parent));
    LLMObsSpan child = null;
    try {
      // when:
      // Child created with null sessionId — should inherit from the parent's LLMObsContext.
      child = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "child-llm", null);

      // then:
      AgentSpan innerChild = agentSpan(child);
      assertEquals(expectedSessionId, innerChild.getTag(LLMOBS_TAG_PREFIX + SESSION_ID));
    } finally {
      if (child != null) {
        child.finish();
      }
      parentScope.close();
      parent.finish();
    }
  }

  @Test
  void childLlmObsSpanHasNoSessionIdWhenNeitherParentNorChildPassesOne() throws Exception {
    LLMObsSpan parent = llmObsSpan(LLMOBS_WORKFLOW_SPAN_KIND, "parent-workflow", null);
    LLMObsSpan child = null;
    try {
      // when:
      child = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "child-llm", null);

      // then:
      AgentSpan innerChild = agentSpan(child);
      assertNull(innerChild.getTag(LLMOBS_TAG_PREFIX + SESSION_ID));
    } finally {
      if (child != null) {
        child.finish();
      }
      parent.finish();
    }
  }

  @Test
  void grandchildLlmObsSpanTransitivelyInheritsSessionIdThroughIntermediateSpan() throws Exception {
    String expectedSessionId = "session-grandparent-xyz";
    LLMObsSpan grandparent =
        llmObsSpan(LLMOBS_WORKFLOW_SPAN_KIND, "grandparent-workflow", expectedSessionId);
    // Activate each ancestor's ContextScope so descendants stay in the same trace —
    // session_id inheritance is gated on trace-id consistency in DDLLMObsSpan.
    ContextScope grandparentScope = AgentTracer.activateSpan(agentSpan(grandparent));
    LLMObsSpan parent = llmObsSpan(LLMOBS_WORKFLOW_SPAN_KIND, "parent-workflow", null);
    ContextScope parentScope = AgentTracer.activateSpan(agentSpan(parent));
    LLMObsSpan grandchild = null;
    try {
      // when:
      // Grandchild created with null sessionId — should inherit transitively
      // through parent's re-attached LLMObsContext (which itself inherited from grandparent).
      grandchild = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "grandchild-llm", null);

      // then:
      AgentSpan innerGrandchild = agentSpan(grandchild);
      assertEquals(expectedSessionId, innerGrandchild.getTag(LLMOBS_TAG_PREFIX + SESSION_ID));
    } finally {
      if (grandchild != null) {
        grandchild.finish();
      }
      parentScope.close();
      parent.finish();
      grandparentScope.close();
      grandparent.finish();
    }
  }

  @Test
  void childDoesNotInheritSessionIdWhenStaleLlmObsContextIsFromADifferentTrace() throws Exception {
    // Simulates a stale LLMObsContext (e.g. leaked across an async boundary). The parent's
    // LLMObsContext is attached, but its ContextScope is deliberately NOT activated — so the
    // next span we create starts a fresh trace and the trace-consistency gate must skip
    // session_id inheritance.
    LLMObsSpan parent = llmObsSpan(LLMOBS_WORKFLOW_SPAN_KIND, "stale-workflow", "stale-session-id");
    LLMObsSpan child = null;
    try {
      // when:
      child = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "child-llm", null);

      // then:
      AgentSpan innerParent = agentSpan(parent);
      AgentSpan innerChild = agentSpan(child);
      // Sanity: traces differ — confirms the scenario is set up correctly.
      assertNotEquals(innerParent.getTraceId(), innerChild.getTraceId());
      // Session_id from the stale-trace context must NOT leak into the new span.
      assertNull(innerChild.getTag(LLMOBS_TAG_PREFIX + SESSION_ID));
    } finally {
      if (child != null) {
        child.finish();
      }
      parent.finish();
    }
  }

  @Test
  @WithConfig(key = GLOBAL_TAGS, value = "team:backend,owner:ml-platform")
  void globalDdTagsAreIncludedInLlmObsSpanTags() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_WORKFLOW_SPAN_KIND, "test-span");

    AgentSpan innerSpan = agentSpan(test);
    assertEquals("backend", innerSpan.getTag(LLMOBS_TAG_PREFIX + "team"));
    assertEquals("ml-platform", innerSpan.getTag(LLMOBS_TAG_PREFIX + "owner"));
  }

  @Test
  void agentManifestFullAnnotationSetsCorrectTag() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_AGENT_SPAN_KIND, "my-agent");
    try {
      Map<String, Object> settings = mapOf("temperature", 0.7, "max_tokens", 1024);
      Map<String, Object> params = singletonMap("city", singletonMap("type", "string"));
      List<LLMObs.AgentTool> tools =
          singletonList(LLMObs.AgentTool.from("get_weather", "Look up weather", params));
      LLMObs.AgentManifest manifest =
          LLMObs.AgentManifest.builder()
              .name("travel_desk")
              .instructions("Book travel.")
              .model("gpt-4o")
              .modelSettings(settings)
              .tools(tools)
              .build();

      // when:
      test.annotateAgentManifest(manifest);

      // then:
      Map<String, Object> stored = storedManifest(agentSpan(test));
      assertEquals("travel_desk", stored.get("name"));
      assertEquals("Book travel.", stored.get("instructions"));
      assertEquals("gpt-4o", stored.get("model"));
      assertEquals("manual", stored.get("framework"));
      Map<?, ?> modelSettings = (Map<?, ?>) stored.get("model_settings");
      assertEquals(0.7, modelSettings.get("temperature"));
      assertEquals(1024, modelSettings.get("max_tokens"));
      List<?> toolList = (List<?>) stored.get("tools");
      assertEquals(1, toolList.size());
      Map<?, ?> storedTool = (Map<?, ?>) toolList.get(0);
      assertEquals("get_weather", storedTool.get("name"));
      assertEquals("Look up weather", storedTool.get("description"));
      assertEquals(
          singletonMap("city", singletonMap("type", "string")), storedTool.get("parameters"));
    } finally {
      test.finish();
    }
  }

  @Test
  void agentManifestNameDefaultsToSpanNameWhenNotProvided() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_AGENT_SPAN_KIND, "my-agent");
    try {
      LLMObs.AgentManifest manifest =
          LLMObs.AgentManifest.builder().instructions("Do something.").build();

      // when:
      test.annotateAgentManifest(manifest);

      // then:
      Map<String, Object> stored = storedManifest(agentSpan(test));
      assertEquals("my-agent", stored.get("name"));
      assertEquals("Do something.", stored.get("instructions"));
      assertEquals("manual", stored.get("framework"));
    } finally {
      test.finish();
    }
  }

  @Test
  void agentManifestDropsToolWithNullName() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_AGENT_SPAN_KIND, "my-agent");
    try {
      LLMObs.AgentTool validTool = LLMObs.AgentTool.from("valid-tool");
      LLMObs.AgentTool badTool = LLMObs.AgentTool.from(null);
      LLMObs.AgentManifest manifest =
          LLMObs.AgentManifest.builder().tools(asList(badTool, validTool)).build();

      // when:
      test.annotateAgentManifest(manifest);

      // then:
      Map<String, Object> stored = storedManifest(agentSpan(test));
      List<?> toolList = (List<?>) stored.get("tools");
      assertEquals(1, toolList.size());
      assertEquals("valid-tool", ((Map<?, ?>) toolList.get(0)).get("name"));
    } finally {
      test.finish();
    }
  }

  @Test
  void agentManifestWithEmptyToolsListOmitsToolsKey() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_AGENT_SPAN_KIND, "my-agent");
    try {
      LLMObs.AgentManifest manifest =
          LLMObs.AgentManifest.builder().name("agent").tools(emptyList()).build();

      // when:
      test.annotateAgentManifest(manifest);

      // then:
      Map<String, Object> stored = storedManifest(agentSpan(test));
      assertFalse(stored.containsKey("tools"));
    } finally {
      test.finish();
    }
  }

  @Test
  void agentManifestOnNonAgentSpanIsSilentlyDropped() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_LLM_SPAN_KIND, "llm-span");
    try {
      LLMObs.AgentManifest manifest = LLMObs.AgentManifest.builder().name("agent").build();

      // when:
      test.annotateAgentManifest(manifest);

      // then:
      assertNull(storedManifest(agentSpan(test)));
    } finally {
      test.finish();
    }
  }

  @Test
  void secondAnnotateAgentManifestCallMergesWithTheFirst() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_AGENT_SPAN_KIND, "my-agent");
    try {
      LLMObs.AgentManifest first =
          LLMObs.AgentManifest.builder()
              .name("first")
              .instructions("v1 instructions")
              .model("gpt-3.5")
              .build();
      LLMObs.AgentManifest second =
          LLMObs.AgentManifest.builder().name("second").model("gpt-4o").build();

      // when:
      test.annotateAgentManifest(first);
      test.annotateAgentManifest(second);

      // then:
      Map<String, Object> stored = storedManifest(agentSpan(test));
      assertEquals("second", stored.get("name")); // second call wins on name
      assertEquals("gpt-4o", stored.get("model")); // second call wins on model
      // first call's instructions preserved
      assertEquals("v1 instructions", stored.get("instructions"));
      assertEquals("manual", stored.get("framework"));
    } finally {
      test.finish();
    }
  }

  @Test
  void secondAnnotateAgentManifestCallMergesModelSettings() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_AGENT_SPAN_KIND, "my-agent");
    try {
      LLMObs.AgentManifest first =
          LLMObs.AgentManifest.builder()
              .name("agent")
              .modelSettings(mapOf("temperature", 0.5, "max_tokens", 512))
              .build();
      LLMObs.AgentManifest second =
          LLMObs.AgentManifest.builder()
              .modelSettings(mapOf("temperature", 0.9, "top_p", 0.95))
              .build();

      // when:
      test.annotateAgentManifest(first);
      test.annotateAgentManifest(second);

      // then:
      Map<String, Object> stored = storedManifest(agentSpan(test));
      Map<?, ?> modelSettings = (Map<?, ?>) stored.get("model_settings");
      assertEquals(0.9, modelSettings.get("temperature")); // second wins
      assertEquals(512, modelSettings.get("max_tokens")); // first preserved
      assertEquals(0.95, modelSettings.get("top_p")); // second adds
    } finally {
      test.finish();
    }
  }

  @Test
  void agentManifestModelSettingsForwardedAsIs() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_AGENT_SPAN_KIND, "my-agent");
    try {
      LLMObs.AgentManifest manifest =
          LLMObs.AgentManifest.builder()
              .name("agent")
              .modelSettings(mapOf("temperature", 0.5, "custom_key", "custom_val"))
              .build();

      // when:
      test.annotateAgentManifest(manifest);

      // then:
      Map<String, Object> stored = storedManifest(agentSpan(test));
      Map<?, ?> modelSettings = (Map<?, ?>) stored.get("model_settings");
      assertEquals(0.5, modelSettings.get("temperature"));
      assertEquals("custom_val", modelSettings.get("custom_key"));
    } finally {
      test.finish();
    }
  }

  @Test
  void annotateAgentManifestNullManifestIsIgnored() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_AGENT_SPAN_KIND, "my-agent");
    try {
      // when:
      test.annotateAgentManifest(null);

      // then:
      assertNull(storedManifest(agentSpan(test)));
    } finally {
      test.finish();
    }
  }

  @Test
  void annotateAgentManifestAfterFinishIsSilentlyIgnored() throws Exception {
    LLMObsSpan test = llmObsSpan(LLMOBS_AGENT_SPAN_KIND, "my-agent");
    LLMObs.AgentManifest manifest =
        LLMObs.AgentManifest.builder().name("agent").model("gpt-4o").build();

    // when:
    test.finish();
    assertDoesNotThrow(() -> test.annotateAgentManifest(manifest));

    // then:
    assertNull(storedManifest(agentSpan(test)));
  }

  private static void assertWellKnownTags(AgentSpan innerSpan) {
    assertNull(innerSpan.getTag("env"));
    Object tagEnv = innerSpan.getTag(LLMOBS_TAG_PREFIX + "env");
    assertInstanceOf(UTF8BytesString.class, tagEnv);
    assertEquals("test-env", tagEnv.toString());

    assertNull(innerSpan.getTag("service"));
    Object tagSvc = innerSpan.getTag(LLMOBS_TAG_PREFIX + "service");
    assertInstanceOf(UTF8BytesString.class, tagSvc);
    assertEquals("test-svc", tagSvc.toString());

    assertNull(innerSpan.getTag("version"));
    Object tagVersion = innerSpan.getTag(LLMOBS_TAG_PREFIX + "version");
    assertInstanceOf(UTF8BytesString.class, tagVersion);
    assertEquals("v1", tagVersion.toString());

    assertEquals(DDTraceApiInfo.VERSION, innerSpan.getTag(LLMOBS_TAG_PREFIX + "ddtrace.version"));
  }

  private static Map<String, Object> mapOf(String key, Object value) {
    Map<String, Object> map = new HashMap<>();
    map.put(key, value);
    return map;
  }

  private static Map<String, Object> mapOf(String key1, Object value1, String key2, Object value2) {
    Map<String, Object> map = mapOf(key1, value1);
    map.put(key2, value2);
    return map;
  }

  private static AgentSpan agentSpan(LLMObsSpan llmObsSpan) throws IllegalAccessException {
    return (AgentSpan) SPAN_FIELD.get(llmObsSpan);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> promptTag(AgentSpan span) {
    return (Map<String, Object>) span.getTag(INPUT_PROMPT);
  }

  private static List<String> toolDefinitionNames(AgentSpan span) {
    List<?> toolDefinitions = (List<?>) span.getTag(TOOL_DEFINITIONS);
    return toolDefinitions.stream()
        .map(toolDefinition -> ((LLMObs.ToolDefinition) toolDefinition).getName())
        .collect(Collectors.toList());
  }

  /** The manifest is carried inside the metadata tag, under the reserved _dd namespace. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> storedManifest(AgentSpan span) {
    Map<String, Object> metadata = (Map<String, Object>) span.getTag(METADATA);
    if (metadata == null) {
      return null;
    }
    Map<String, Object> dd = (Map<String, Object>) metadata.get("_dd");
    return dd == null ? null : (Map<String, Object>) dd.get("agent_manifest");
  }

  private static LLMObsSpan llmObsSpan(String kind, String name) {
    return llmObsSpan(kind, name, null);
  }

  private static LLMObsSpan llmObsSpan(String kind, String name, String sessionId) {
    WellKnownTags tags =
        new WellKnownTags("test-runtime-1", "host-1", "test-env", "test-svc", "v1", "java");
    return new DDLLMObsSpan(kind, name, "test-ml-app", sessionId, "test-svc", tags);
  }
}
