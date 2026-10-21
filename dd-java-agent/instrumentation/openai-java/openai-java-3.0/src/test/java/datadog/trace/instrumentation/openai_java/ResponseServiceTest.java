package datadog.trace.instrumentation.openai_java;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace;
import static datadog.trace.instrumentation.openai_java.CommonTags.TOOL_DEFINITIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.credential.BearerTokenCredential;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.sun.net.httpserver.HttpServer;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ResponseServiceTest extends OpenAiTest {
  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createResponseTest(boolean json) {
    ResponseCreateParams params = responseCreateParams(json);

    Response resp = runUnderTrace("parent", () -> openAiClient.responses().create(params));

    assertNotNull(resp);
    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, metadata);
    assertEquals(false, metadata.get("stream"));
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createResponseTestWithRawResponse(boolean json) {
    ResponseCreateParams params = responseCreateParams(json);

    try (HttpResponseFor<Response> resp =
        runUnderTrace("parent", () -> openAiClient.responses().withRawResponse().create(params))) {

      assertEquals(200, resp.statusCode());
      assertTrue(resp.parse().isValid()); // force response parsing, so it sets all the tags
      Map<String, Object> metadata = new HashMap<>();
      assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, metadata);
      assertEquals(false, metadata.get("stream"));
    }
  }

  @TableTest({
    "scenario         | json  | incomplete",
    "complete typed   | false | false     ",
    "complete JSON    | true  | false     ",
    "incomplete typed | false | true      ",
    "incomplete JSON  | true  | true      "
  })
  void createStreamingResponseTestScenario(boolean json, boolean incomplete) {
    ResponseCreateParams params =
        incomplete ? responseCreateParamsWithMaxOutputTokens(json) : responseCreateParams(json);

    runnableUnderTrace(
        "parent",
        () -> {
          try (StreamResponse<ResponseStreamEvent> streamResponse =
                  openAiClient.responses().createStreaming(params);
              Stream<?> stream = streamResponse.stream()) {
            stream.forEach(
                chunk -> {
                  // consume the stream
                });
          }
        });

    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, metadata);
    assertEquals(true, metadata.get("stream"));
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingResponseTestReasoning(boolean json) {
    ResponseCreateParams responseCreateParams = responseCreateParamsWithReasoning(json);

    runnableUnderTrace(
        "parent",
        () -> {
          try (StreamResponse<ResponseStreamEvent> streamResponse =
                  openAiClient.responses().createStreaming(responseCreateParams);
              Stream<?> stream = streamResponse.stream()) {
            stream.forEach(
                chunk -> {
                  // consume the stream
                });
          }
        });

    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(
        true,
        "o4-mini",
        "o4-mini-2025-04-16",
        map("effort", "medium", "summary", "detailed"),
        null,
        metadata);
    assertEquals(true, metadata.get("stream"));
    assertEquals(map("effort", "medium", "summary", "detailed"), metadata.get("reasoning"));
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingResponseTestWithRawResponse(boolean json) {
    ResponseCreateParams params = responseCreateParams(json);

    runnableUnderTrace(
        "parent",
        () -> {
          try (HttpResponseFor<StreamResponse<ResponseStreamEvent>> streamResponse =
                  openAiClient.responses().withRawResponse().createStreaming(params);
              Stream<?> stream = streamResponse.parse().stream()) {
            stream.forEach(
                chunk -> {
                  // consume the stream
                });
          }
        });

    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, metadata);
    assertEquals(true, metadata.get("stream"));
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createAsyncResponseTest(boolean json) throws Exception {
    ResponseCreateParams params = responseCreateParams(json);

    CompletableFuture<Response> responseFuture =
        runUnderTrace("parent", () -> openAiClient.async().responses().create(params));

    responseFuture.get();

    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, metadata);
    assertEquals(false, metadata.get("stream"));
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createAsyncResponseTestWithRawResponse(boolean json) throws Exception {
    ResponseCreateParams params = responseCreateParams(json);

    CompletableFuture<HttpResponseFor<Response>> responseFuture =
        runUnderTrace(
            "parent", () -> openAiClient.async().responses().withRawResponse().create(params));

    try (HttpResponseFor<Response> resp = responseFuture.get()) {
      resp.parse().isValid(); // force response parsing, so it sets all the tags

      Map<String, Object> metadata = new HashMap<>();
      assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, metadata);
      assertEquals(false, metadata.get("stream"));
    }
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingAsyncResponseTest(boolean json) throws Exception {
    ResponseCreateParams params = responseCreateParams(json);

    AsyncStreamResponse<ResponseStreamEvent> asyncResp =
        runUnderTrace("parent", () -> openAiClient.async().responses().createStreaming(params));
    asyncResp.subscribe(
        chunk -> {
          // consume responses
        });
    asyncResp.onCompleteFuture().get();
    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, metadata);
    assertEquals(true, metadata.get("stream"));
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingAsyncResponseTestWithRawResponse(boolean json) throws Exception {
    ResponseCreateParams params = responseCreateParams(json);

    CompletableFuture<HttpResponseFor<StreamResponse<ResponseStreamEvent>>> future =
        runUnderTrace(
            "parent",
            () -> openAiClient.async().responses().withRawResponse().createStreaming(params));
    try (HttpResponseFor<StreamResponse<ResponseStreamEvent>> resp = future.get()) {
      try (Stream<?> stream = resp.parse().stream()) {
        stream.forEach(
            chunk -> {
              // consume the stream
            });
      }
      assertEquals(200, resp.statusCode());
      Map<String, Object> metadata = new HashMap<>();
      assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, metadata);
      assertEquals(true, metadata.get("stream"));
    }
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingResponseWithToolInputTest(boolean json) {
    ResponseCreateParams responseCreateParams = responseCreateParamsWithToolInput(json);

    runnableUnderTrace(
        "parent",
        () -> {
          try (StreamResponse<ResponseStreamEvent> streamResponse =
                  openAiClient.responses().createStreaming(responseCreateParams);
              Stream<?> stream = streamResponse.stream()) {
            stream.forEach(
                chunk -> {
                  // consume the stream
                });
          }
        });

    List<LLMObs.LLMMessage> inputTags = new ArrayList<>();
    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(
        true, "gpt-4.1", "gpt-4.1-2025-04-14", null, inputTags, null, metadata, false);
    assertEquals(true, metadata.get("stream"));
    assertEquals(3, inputTags.size());
    assertEquals(1, inputTags.get(1).getToolCalls().size());
    assertEquals("get_weather", inputTags.get(1).getToolCalls().get(0).getName());
    assertEquals("function_call", inputTags.get(1).getToolCalls().get(0).getType());
    assertEquals(
        map("location", "San Francisco, CA"),
        inputTags.get(1).getToolCalls().get(0).getArguments());
    assertEquals(1, inputTags.get(2).getToolResults().size());
    assertEquals("function_call_output", inputTags.get(2).getToolResults().get(0).getType());
    assertEquals("call_123", inputTags.get(2).getToolResults().get(0).getToolId());
    assertEquals(
        "{\"temperature\": \"72°F\", \"conditions\": \"sunny\", \"humidity\": \"65%\"}",
        inputTags.get(2).getToolResults().get(0).getResult());
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createResponseWithPromptTracking(boolean json) {
    ResponseCreateParams params = responseCreateParamsWithPromptTracking(json);

    Response resp = runUnderTrace("parent", () -> openAiClient.responses().create(params));

    assertNotNull(resp);
    Map<String, Object> metadata = new HashMap<>();
    Map<String, Object> input = new HashMap<>();
    assertResponseTrace(false, null, String.class, null, input, null, metadata, true);
    assertEquals(false, metadata.get("stream"));
    assertInstanceOf(Map.class, input.get("prompt"));
    assertInstanceOf(List.class, input.get("messages"));
    Map<?, ?> prompt = (Map<?, ?>) input.get("prompt");
    assertInstanceOf(List.class, prompt.get("chat_template"));
    assertInstanceOf(Map.class, prompt.get("variables"));
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createResponseWithCustomToolCall(boolean json) {
    ResponseCreateParams params = responseCreateParamsWithCustomToolCall(json);

    Response resp = runUnderTrace("parent", () -> openAiClient.responses().create(params));

    assertNotNull(resp);
    List<LLMObs.LLMMessage> outputTags = new ArrayList<>();
    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(false, "gpt-5", String.class, null, outputTags, metadata);
    assertFalse(outputTags.isEmpty());
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createResponseWithFunctionToolDefinition(boolean json) {
    ResponseCreateParams params = responseCreateParamsWithFunctionTool(json);

    Response resp = runUnderTrace("parent", () -> openAiClient.responses().create(params));

    assertNotNull(resp);
    List<Map<String, Object>> toolDefinitions = new ArrayList<>();
    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(
        false, "gpt-4.1", String.class, null, null, null, metadata, false, toolDefinitions);
    assertEquals(false, metadata.get("stream"));
    assertEquals(1, toolDefinitions.size());
    assertEquals("extract_student_info", toolDefinitions.get(0).get("name"));
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
  void createResponseWithRawFunctionToolDefinition() throws Exception {

    ResponseCreateParams params = responseCreateParamsWithRawFunctionTool();

    assertTrue(params._tools().asUnknown().isPresent());

    Response resp = runUnderTrace("parent", () -> openAiClient.responses().create(params));

    assertNotNull(resp);
    List<Map<String, Object>> toolDefinitions = new ArrayList<>();
    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(
        false, "gpt-4.1", String.class, null, null, null, metadata, false, toolDefinitions);
    assertEquals(false, metadata.get("stream"));
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

  private void assertResponseTrace(
      boolean isStreaming,
      String reqModel,
      Object respModel,
      Map<String, Object> reasoning,
      List<LLMObs.LLMMessage> outputTagsOut,
      Map<String, Object> metadataOut) {
    assertResponseTrace(
        isStreaming, reqModel, respModel, reasoning, null, outputTagsOut, metadataOut, false);
  }

  private void assertResponseTrace(
      boolean isStreaming,
      String reqModel,
      Object respModel,
      Map<String, Object> reasoning,
      Object inputTagsOut,
      List<LLMObs.LLMMessage> outputTagsOut,
      Map<String, Object> metadataOut,
      boolean expectPromptTag) {
    assertResponseTrace(
        isStreaming,
        reqModel,
        respModel,
        reasoning,
        inputTagsOut,
        outputTagsOut,
        metadataOut,
        expectPromptTag,
        null);
  }

  @SuppressWarnings("unchecked")
  private void assertResponseTrace(
      boolean isStreaming,
      String reqModel,
      Object respModel,
      Map<String, Object> reasoning,
      Object inputTagsOut,
      List<LLMObs.LLMMessage> outputTagsOut,
      Map<String, Object> metadataOut,
      boolean expectPromptTag,
      List<Map<String, Object>> toolDefinitionsOut) {
    Map<String, Object> tags = new HashMap<>();
    tags.put("_ml_obs_tag.span.kind", "llm");
    tags.put("_ml_obs_tag.model_provider", "openai");
    tags.put("_ml_obs_tag.model_name", String.class);
    tags.put("_ml_obs_tag.metadata", Map.class);
    if (expectPromptTag) {
      tags.put("_ml_obs_request.prompt", Map.class);
    }
    tags.put("_ml_obs_tag.output", List.class);
    if (toolDefinitionsOut != null) {
      tags.put(TOOL_DEFINITIONS, List.class);
    }
    tags.put("_ml_obs_metric.input_tokens", Long.class);
    tags.put("_ml_obs_metric.output_tokens", Long.class);
    tags.put("_ml_obs_metric.total_tokens", Long.class);
    tags.put("_ml_obs_metric.reasoning_output_tokens", Long.class);
    tags.put("_ml_obs_metric.cache_read_input_tokens", Long.class);
    tags.put("_ml_obs_tag.parent_id", "undefined");
    tags.put("_ml_obs_tag.sampling_decision", "1");
    tags.put("_ml_obs_tag.sample_rate", "1");
    tags.put("_ml_obs_tag.ml_app", String.class);
    tags.put("gen_ai.operation.name", "llm");
    tags.put("gen_ai.request.model", String.class);
    tags.put("gen_ai.provider.name", "openai");
    tags.put("gen_ai.application.name", String.class);
    tags.put("gen_ai.usage.input_tokens", Double.class);
    tags.put("gen_ai.usage.output_tokens", Double.class);
    tags.put("gen_ai.usage.total_tokens", Double.class);
    tags.put("gen_ai.usage.reasoning_output_tokens", Double.class);
    tags.put("gen_ai.usage.cache_read_input_tokens", Double.class);
    tags.put("_dd.llmobs.artificial_gen_ai_tags", "true");
    tags.put(CommonTags.INTEGRATION, "openai");
    tags.put("_ml_obs_tag.service", String.class);
    tags.put(CommonTags.DDTRACE_VERSION, String.class);
    tags.put(CommonTags.SOURCE, "integration");
    tags.put(CommonTags.ERROR, 0);
    if (reasoning != null) {
      tags.put("_ml_obs_request.reasoning", reasoning);
    }
    tags.put("openai.request.method", "POST");
    tags.put("openai.request.endpoint", "/v1/responses");
    tags.put("openai.api_base", openAiBaseApi);
    tags.put(CommonTags.OPENAI_RESPONSE_MODEL, respModel);
    tags.put(
        "openai.organization.ratelimit.requests.limit",
        (TagCondition) value -> value instanceof Integer || (isStreaming && value == null));
    tags.put(
        "openai.organization.ratelimit.requests.remaining",
        (TagCondition) value -> value instanceof Integer || (isStreaming && value == null));
    tags.put(
        "openai.organization.ratelimit.tokens.limit",
        (TagCondition) value -> value instanceof Integer || (isStreaming && value == null));
    tags.put(
        "openai.organization.ratelimit.tokens.remaining",
        (TagCondition) value -> value instanceof Integer || (isStreaming && value == null));
    tags.put(CommonTags.OPENAI_ORGANIZATION, "datadog-staging");
    if (reqModel != null) {
      tags.put(CommonTags.OPENAI_REQUEST_MODEL, reqModel);
    }
    tags.put(Tags.COMPONENT, "openai");
    tags.put(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    tags.put("_ml_obs_tag.input", inputTagsOut instanceof Map ? Map.class : List.class);
    DDSpan span = assertOpenAiTrace("createResponse", "/v1/responses", tags);
    if (metadataOut != null) {
      metadataOut.putAll(OpenAiTest.<Map<String, Object>>spanTag(span, "_ml_obs_tag.metadata"));
    }
    if (inputTagsOut instanceof List) {
      ((List<Object>) inputTagsOut)
          .addAll(OpenAiTest.<List<Object>>spanTag(span, "_ml_obs_tag.input"));
    } else if (inputTagsOut instanceof Map) {
      ((Map<String, Object>) inputTagsOut)
          .putAll(OpenAiTest.<Map<String, Object>>spanTag(span, "_ml_obs_tag.input"));
    }
    if (outputTagsOut != null) {
      outputTagsOut.addAll(OpenAiTest.<List<LLMObs.LLMMessage>>spanTag(span, "_ml_obs_tag.output"));
    }
    if (toolDefinitionsOut != null) {
      toolDefinitionsOut.addAll(
          OpenAiTest.<List<Map<String, Object>>>spanTag(span, TOOL_DEFINITIONS));
    }
  }

  @Test
  void createResponseWithMcpToolCall() {
    Response response =
        runUnderTrace(
            "parent", () -> openAiClient.responses().create(responseCreateParamsWithMcpToolCall()));
    assertNotNull(response);
    List<LLMObs.LLMMessage> outputTags = new ArrayList<>();
    Map<String, Object> metadata = new HashMap<>();
    assertResponseTrace(false, "gpt-5", String.class, null, outputTags, metadata);
    assertEquals(false, metadata.get("stream"));
    boolean foundMcpCall = false;
    mcpCalls:
    for (LLMObs.LLMMessage message : outputTags) {
      if (message.getToolCalls() != null) {
        for (LLMObs.ToolCall item : message.getToolCalls()) {
          if ("mcp_call".equals(item.getType())
              && item.getName() != null
              && !item.getName().isEmpty()) {
            foundMcpCall = true;
            break mcpCalls;
          }
        }
      }
    }
    assertTrue(foundMcpCall);
    boolean foundMcpResult = false;
    mcpResults:
    for (LLMObs.LLMMessage message : outputTags) {
      if (message.getToolResults() != null) {
        for (LLMObs.ToolResult item : message.getToolResults()) {
          if ("mcp_tool_result".equals(item.getType())
              && item.getName() != null
              && !item.getName().isEmpty()) {
            foundMcpResult = true;
            break mcpResults;
          }
        }
      }
    }
    assertTrue(foundMcpResult);
  }

  @Test
  void createResponseErrorSetsModelNameAndPlaceholderOutput() throws Exception {
    HttpServer errorBackend = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    errorBackend.createContext(
        "/v1/",
        exchange -> {
          byte[] body =
              "{\"error\":{\"message\":\"Internal server error\",\"type\":\"server_error\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(500, body.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    errorBackend.start();
    OpenAIClient errorClient =
        OpenAIOkHttpClient.builder()
            .baseUrl("http://localhost:" + errorBackend.getAddress().getPort() + "/v1")
            .credential(BearerTokenCredential.create(MOCK_OPENAI_TOKEN))
            .maxRetries(0)
            .build();
    try {
      runUnderTrace(
          "parent",
          () -> {
            try {
              errorClient.responses().create(responseCreateParams(false));
            } catch (Exception ignored) {
            }
            return null;
          });
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName(Pattern.compile(Pattern.quote("parent"))),
              span()
                  .childOfIndex(0)
                  .operationName(Pattern.compile(Pattern.quote("openai.request")))
                  .resourceName(Pattern.compile(Pattern.quote("createResponse")))
                  .error()
                  .type(DDSpanTypes.LLMOBS),
              span()
                  .childOfIndex(1)
                  .operationName(Pattern.compile(Pattern.quote("okhttp.request")))
                  .resourceName(Pattern.compile(Pattern.quote("POST /v1/responses")))
                  .type("http")));
      DDSpan openAiSpan =
          writer.firstTrace().stream()
              .filter(value -> "openai.request".contentEquals(value.getOperationName()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("Expected an OpenAI span"));
      assertEquals("gpt-3.5-turbo", openAiSpan.getTag("_ml_obs_tag.model_name"));
      List<LLMObs.LLMMessage> output = spanTag(openAiSpan, "_ml_obs_tag.output");
      assertEquals(1, output.size());
      assertEquals("", output.get(0).getRole());
      assertEquals("", output.get(0).getContent());
    } finally {
      try {
        errorClient.close();
      } finally {
        errorBackend.stop(0);
      }
    }
  }
}
