package datadog.trace.instrumentation.openai_java;

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace;
import static datadog.trace.instrumentation.openai_java.CommonTags.TOOL_DEFINITIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ChatCompletionServiceTest extends OpenAiTest {
  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createChatCompletionTest(boolean json) {
    ChatCompletionCreateParams params = chatCompletionCreateParams(json);

    ChatCompletion resp =
        runUnderTrace("parent", () -> openAiClient.chat().completions().create(params));

    assertNotNull(resp);
    assertChatCompletionTrace();
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createChatCompletionTestWithRawResponse(boolean json) {
    ChatCompletionCreateParams params = chatCompletionCreateParams(json);

    try (HttpResponseFor<ChatCompletion> resp =
        runUnderTrace(
            "parent", () -> openAiClient.chat().withRawResponse().completions().create(params))) {

      assertEquals(200, resp.statusCode());
      assertTrue(resp.parse().isValid()); // force response parsing, so it sets all the tags
      assertChatCompletionTrace();
    }
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingChatCompletionTest(boolean json) {
    ChatCompletionCreateParams params = chatCompletionCreateParams(json);

    runnableUnderTrace(
        "parent",
        () -> {
          try (StreamResponse<ChatCompletionChunk> streamCompletion =
                  openAiClient.chat().completions().createStreaming(params);
              Stream<?> stream = streamCompletion.stream()) {
            stream.forEach(
                chunk -> {
                  // consume the stream
                });
          }
        });

    assertChatCompletionTrace(true, null, map("stream", true));
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingChatCompletionTestWithRawResponse(boolean json) {
    ChatCompletionCreateParams params = chatCompletionCreateParams(json);

    runnableUnderTrace(
        "parent",
        () -> {
          try (HttpResponseFor<StreamResponse<ChatCompletionChunk>> streamCompletion =
                  openAiClient.chat().completions().withRawResponse().createStreaming(params);
              Stream<?> stream = streamCompletion.parse().stream()) {
            stream.forEach(
                chunk -> {
                  // consume the stream
                });
          }
        });

    assertChatCompletionTrace(true, null, map("stream", true));
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createAsyncChatCompletionTest(boolean json) throws Exception {
    ChatCompletionCreateParams params = chatCompletionCreateParams(json);

    CompletableFuture<ChatCompletion> completionFuture =
        runUnderTrace("parent", () -> openAiClient.async().chat().completions().create(params));

    completionFuture.get();

    assertChatCompletionTrace();
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createAsyncChatCompletionTestWithRawResponse(boolean json) throws Exception {
    ChatCompletionCreateParams params = chatCompletionCreateParams(json);

    CompletableFuture<HttpResponseFor<ChatCompletion>> completionFuture =
        runUnderTrace(
            "parent",
            () -> openAiClient.async().chat().completions().withRawResponse().create(params));

    try (HttpResponseFor<ChatCompletion> resp = completionFuture.get()) {
      resp.parse().isValid(); // force response parsing, so it sets all the tags

      assertChatCompletionTrace();
    }
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingAsyncChatCompletionTest(boolean json) throws Exception {
    ChatCompletionCreateParams params = chatCompletionCreateParams(json);

    AsyncStreamResponse<ChatCompletionChunk> asyncResp =
        runUnderTrace(
            "parent", () -> openAiClient.async().chat().completions().createStreaming(params));
    asyncResp.subscribe(
        chunk -> {
          // consume completions
        });
    asyncResp.onCompleteFuture().get();
    assertChatCompletionTrace(true, null, map("stream", true));
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingAsyncChatCompletionTestWithRawResponse(boolean json) throws Exception {
    ChatCompletionCreateParams params = chatCompletionCreateParams(json);

    CompletableFuture<HttpResponseFor<StreamResponse<ChatCompletionChunk>>> future =
        runUnderTrace(
            "parent",
            () ->
                openAiClient
                    .async()
                    .chat()
                    .completions()
                    .withRawResponse()
                    .createStreaming(params));
    try (HttpResponseFor<StreamResponse<ChatCompletionChunk>> resp = future.get()) {
      try (Stream<?> stream = resp.parse().stream()) {
        stream.forEach(
            chunk -> {
              // consume the stream
            });
      }
      assertEquals(200, resp.statusCode());
      assertChatCompletionTrace(true, null, map("stream", true));
    }
  }

  @Test
  void createChatCompletionTestWithToolCalls() {
    runUnderTrace(
        "parent",
        () -> openAiClient.chat().completions().create(chatCompletionCreateParamsWithTools()));

    List<LLMObs.LLMMessage> outputTag = new ArrayList<>();
    List<Map<String, Object>> toolDefinitions = new ArrayList<>();
    assertChatCompletionTrace(false, outputTag, new HashMap<>(), true, toolDefinitions);
    assertEquals(1, toolDefinitions.size());
    assertEquals("extract_student_info", toolDefinitions.get(0).get("name"));
    assertEquals(
        "Get the student information from the body of the input text",
        toolDefinitions.get(0).get("description"));
    assertEquals("object", ((Map<?, ?>) toolDefinitions.get(0).get("schema")).get("type"));
    assertTrue(
        ((Map<?, ?>) ((Map<?, ?>) toolDefinitions.get(0).get("schema")).get("properties"))
            .containsKey("name"));
    assertTrue(
        ((Map<?, ?>) ((Map<?, ?>) toolDefinitions.get(0).get("schema")).get("properties"))
            .containsKey("major"));
    assertTrue(
        ((Map<?, ?>) ((Map<?, ?>) toolDefinitions.get(0).get("schema")).get("properties"))
            .containsKey("school"));
    assertTrue(
        ((Map<?, ?>) ((Map<?, ?>) toolDefinitions.get(0).get("schema")).get("properties"))
            .containsKey("grades"));
    assertTrue(
        ((Map<?, ?>) ((Map<?, ?>) toolDefinitions.get(0).get("schema")).get("properties"))
            .containsKey("clubs"));
    assertEquals(1, outputTag.size());
    LLMObs.LLMMessage outputMsg = outputTag.get(0);
    assertEquals(1, outputMsg.getToolCalls().size());
    LLMObs.ToolCall toolcall = outputMsg.getToolCalls().get(0);
    assertEquals("extract_student_info", toolcall.getName());
    assertInstanceOf(String.class, toolcall.getToolId());
    assertEquals("function", toolcall.getType());
    assertEquals(
        map(
            "name",
            "David Nguyen",
            "major",
            "computer science",
            "school",
            "Stanford University",
            "grades",
            3.8,
            "clubs",
            Arrays.asList("Chess Club", "South Asian Student Association")),
        toolcall.getArguments());
  }

  @Test
  void createChatCompletionTestWithRawToolDefinition() {

    runUnderTrace(
        "parent",
        () -> openAiClient.chat().completions().create(chatCompletionCreateParamsWithRawTools()));

    List<Map<String, Object>> toolDefinitions = new ArrayList<>();
    assertChatCompletionTrace(false, null, new HashMap<>(), true, toolDefinitions);
    assertEquals(1, toolDefinitions.size());
    assertEquals("extract_student_info_raw", toolDefinitions.get(0).get("name"));
    assertEquals(
        "Extract student information from the input text",
        toolDefinitions.get(0).get("description"));
    assertEquals("object", ((Map<?, ?>) toolDefinitions.get(0).get("schema")).get("type"));
    assertTrue(
        ((Map<?, ?>) ((Map<?, ?>) toolDefinitions.get(0).get("schema")).get("properties"))
            .containsKey("name"));
    assertTrue(
        ((Map<?, ?>) ((Map<?, ?>) toolDefinitions.get(0).get("schema")).get("properties"))
            .containsKey("major"));
    assertEquals(
        Collections.singletonList("name"),
        ((Map<?, ?>) toolDefinitions.get(0).get("schema")).get("required"));
  }

  @Test
  void createChatCompletionTestWithToolChoice() {

    runUnderTrace(
        "parent",
        () -> openAiClient.chat().completions().create(chatCompletionCreateParamsWithToolChoice()));

    List<LLMObs.LLMMessage> outputTag = new ArrayList<>();
    List<Map<String, Object>> toolDefinitions = new ArrayList<>();
    assertChatCompletionTrace(
        false, outputTag, map("tool_choice", "function"), true, toolDefinitions);
    assertEquals(1, toolDefinitions.size());
    assertEquals("extract_student_info", toolDefinitions.get(0).get("name"));
    assertEquals(
        "Get the student information from the body of the input text",
        toolDefinitions.get(0).get("description"));
    assertEquals(1, outputTag.size());
    LLMObs.LLMMessage outputMsg = outputTag.get(0);
    assertEquals(1, outputMsg.getToolCalls().size());
    LLMObs.ToolCall toolcall = outputMsg.getToolCalls().get(0);
    assertEquals("extract_student_info", toolcall.getName());
    assertInstanceOf(String.class, toolcall.getToolId());
    assertEquals("function", toolcall.getType());
    assertEquals(
        map(
            "name",
            "David Nguyen",
            "major",
            "computer science",
            "school",
            "Stanford University",
            "grades",
            3.8,
            "clubs",
            Arrays.asList("Chess Club", "South Asian Student Association")),
        toolcall.getArguments());
  }

  @Test
  void createStreamingChatCompletionTestWithToolCalls() {

    runnableUnderTrace(
        "parent",
        () -> {
          try (StreamResponse<ChatCompletionChunk> streamCompletion =
                  openAiClient
                      .chat()
                      .completions()
                      .createStreaming(chatCompletionCreateParamsWithTools());
              Stream<?> stream = streamCompletion.stream()) {
            stream.forEach(
                chunk -> {
                  // chunks.add(chunk)
                });
          }
        });

    List<LLMObs.LLMMessage> outputTag = new ArrayList<>();
    List<Map<String, Object>> toolDefinitions = new ArrayList<>();
    assertChatCompletionTrace(true, outputTag, map("stream", true), true, toolDefinitions);
    assertEquals(1, toolDefinitions.size());
    assertEquals("extract_student_info", toolDefinitions.get(0).get("name"));
    assertEquals(
        "Get the student information from the body of the input text",
        toolDefinitions.get(0).get("description"));
    assertEquals(1, outputTag.size());
    LLMObs.LLMMessage outputMsg = outputTag.get(0);
    assertEquals(1, outputMsg.getToolCalls().size());
    LLMObs.ToolCall toolcall = outputMsg.getToolCalls().get(0);
    assertEquals("extract_student_info", toolcall.getName());
    assertInstanceOf(String.class, toolcall.getToolId());
    assertEquals("function", toolcall.getType());
    assertEquals(
        map(
            "name",
            "David Nguyen",
            "major",
            "computer science",
            "school",
            "Stanford University",
            "grades",
            3.8,
            "clubs",
            Arrays.asList("Chess Club", "South Asian Student Association")),
        toolcall.getArguments());
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createChatCompletionTestWithMultiChoiceResponse(boolean json) {
    ChatCompletionCreateParams params = chatCompletionCreateParamsMultiChoice(json);

    ChatCompletion resp =
        runUnderTrace("parent", () -> openAiClient.chat().completions().create(params));

    assertNotNull(resp);
    assertEquals(3, resp.choices().size());
    List<LLMObs.LLMMessage> outputTag = new ArrayList<>();
    assertChatCompletionTrace(false, outputTag, map("n", 3L));
    assertEquals(3, outputTag.size());
    for (LLMObs.LLMMessage message : outputTag) {
      assertEquals("assistant", message.getRole());
      assertEquals("Hello, world!", message.getContent());
    }
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createChatCompletionTestWithMultiChoiceResponseWithRawResponse(boolean json) {
    ChatCompletionCreateParams params = chatCompletionCreateParamsMultiChoice(json);

    try (HttpResponseFor<ChatCompletion> resp =
        runUnderTrace(
            "parent", () -> openAiClient.chat().withRawResponse().completions().create(params))) {

      assertEquals(200, resp.statusCode());
      assertEquals(3, resp.parse().choices().size());
      List<LLMObs.LLMMessage> outputTag = new ArrayList<>();
      assertChatCompletionTrace(false, outputTag, map("n", 3L));
      assertEquals(3, outputTag.size());
      for (LLMObs.LLMMessage message : outputTag) {
        assertEquals("assistant", message.getRole());
        assertEquals("Hello, world!", message.getContent());
      }
    }
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingChatCompletionTestWithMultiChoiceResponse(boolean json) {
    ChatCompletionCreateParams params = chatCompletionCreateParamsMultiChoice(json);

    runnableUnderTrace(
        "parent",
        () -> {
          try (StreamResponse<ChatCompletionChunk> streamCompletion =
                  openAiClient.chat().completions().createStreaming(params);
              Stream<?> stream = streamCompletion.stream()) {
            stream.forEach(
                chunk -> {
                  // consume the stream
                });
          }
        });

    List<LLMObs.LLMMessage> outputTag = new ArrayList<>();
    assertChatCompletionTrace(true, outputTag, map("stream", true, "n", 3L));
    assertEquals(3, outputTag.size());
    for (LLMObs.LLMMessage message : outputTag) {
      assertEquals("assistant", message.getRole());
      assertEquals("Hello, world!", message.getContent());
    }
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createAsyncChatCompletionTestWithMultiChoiceResponse(boolean json) throws Exception {
    ChatCompletionCreateParams params = chatCompletionCreateParamsMultiChoice(json);

    CompletableFuture<ChatCompletion> completionFuture =
        runUnderTrace("parent", () -> openAiClient.async().chat().completions().create(params));

    ChatCompletion resp = completionFuture.get();

    assertNotNull(resp);
    assertEquals(3, resp.choices().size());
    List<LLMObs.LLMMessage> outputTag = new ArrayList<>();
    assertChatCompletionTrace(false, outputTag, map("n", 3L));
    assertEquals(3, outputTag.size());
    for (LLMObs.LLMMessage message : outputTag) {
      assertEquals("assistant", message.getRole());
      assertEquals("Hello, world!", message.getContent());
    }
  }

  private void assertChatCompletionTrace() {
    assertChatCompletionTrace(false, null, Collections.emptyMap());
  }

  private void assertChatCompletionTrace(
      boolean isStreaming, List<LLMObs.LLMMessage> outputTagsOut, Map<String, Object> metadata) {
    assertChatCompletionTrace(isStreaming, outputTagsOut, metadata, false, null);
  }

  private void assertChatCompletionTrace(
      boolean isStreaming,
      List<LLMObs.LLMMessage> outputTagsOut,
      Map<String, Object> metadata,
      boolean expectToolDefinitions,
      List<Map<String, Object>> toolDefinitionsOut) {
    Map<String, Object> expectedMetadata = new HashMap<>(metadata);
    expectedMetadata.putIfAbsent("stream", isStreaming);
    Map<String, Object> tags = new HashMap<>();
    tags.put("_ml_obs_tag.span.kind", "llm");
    tags.put("_ml_obs_tag.model_provider", "openai");
    tags.put("_ml_obs_tag.model_name", String.class);
    tags.put("_ml_obs_tag.metadata", expectedMetadata);
    tags.put("_ml_obs_tag.input", List.class);
    tags.put("_ml_obs_tag.output", List.class);
    if (!isStreaming) {
      // Streamed completions are missing usage data.
      tags.put("_ml_obs_metric.input_tokens", Long.class);
      tags.put("_ml_obs_metric.output_tokens", Long.class);
      tags.put("_ml_obs_metric.total_tokens", Long.class);
      tags.put("_ml_obs_metric.cache_read_input_tokens", Long.class);
    }
    tags.put("_ml_obs_tag.parent_id", "undefined");
    tags.put("_ml_obs_tag.sampling_decision", "1");
    tags.put("_ml_obs_tag.sample_rate", "1");
    tags.put("_ml_obs_tag.ml_app", String.class);
    tags.put("gen_ai.operation.name", "llm");
    tags.put("gen_ai.request.model", String.class);
    tags.put("gen_ai.provider.name", "openai");
    tags.put("gen_ai.application.name", String.class);
    if (!isStreaming) {
      // Streamed completions are missing usage data.
      tags.put("gen_ai.usage.input_tokens", Double.class);
      tags.put("gen_ai.usage.output_tokens", Double.class);
      tags.put("gen_ai.usage.total_tokens", Double.class);
      tags.put("gen_ai.usage.cache_read_input_tokens", Double.class);
    }
    tags.put("_dd.llmobs.artificial_gen_ai_tags", "true");
    tags.put("_ml_obs_tag.service", String.class);
    tags.put(CommonTags.DDTRACE_VERSION, String.class);
    if (expectToolDefinitions) {
      tags.put(TOOL_DEFINITIONS, List.class);
    }
    tags.put(CommonTags.SOURCE, "integration");
    tags.put(CommonTags.INTEGRATION, "openai");
    tags.put(CommonTags.ERROR, 0);
    tags.put("openai.request.method", "POST");
    tags.put("openai.request.endpoint", "/v1/chat/completions");
    tags.put("openai.api_base", openAiBaseApi);
    tags.put("openai.organization.ratelimit.requests.limit", 30000);
    tags.put("openai.organization.ratelimit.requests.remaining", Integer.class);
    tags.put("openai.organization.ratelimit.tokens.limit", 150000000);
    tags.put("openai.organization.ratelimit.tokens.remaining", Integer.class);
    tags.put(CommonTags.OPENAI_REQUEST_MODEL, "gpt-4o-mini");
    tags.put(CommonTags.OPENAI_RESPONSE_MODEL, "gpt-4o-mini-2024-07-18");
    tags.put(CommonTags.OPENAI_ORGANIZATION, "datadog-staging");
    tags.put(Tags.COMPONENT, "openai");
    tags.put(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    DDSpan span = assertOpenAiTrace("createChatCompletion", "/v1/chat/completions", tags);
    if (outputTagsOut != null) {
      outputTagsOut.addAll(OpenAiTest.<List<LLMObs.LLMMessage>>spanTag(span, "_ml_obs_tag.output"));
    }
    if (toolDefinitionsOut != null) {
      toolDefinitionsOut.addAll(
          OpenAiTest.<List<Map<String, Object>>>spanTag(span, TOOL_DEFINITIONS));
    }
  }
}
