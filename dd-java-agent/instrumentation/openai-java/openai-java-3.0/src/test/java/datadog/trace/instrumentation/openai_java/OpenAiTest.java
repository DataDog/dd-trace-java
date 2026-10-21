package datadog.trace.instrumentation.openai_java;

import static com.openai.models.ChatModel.GPT_3_5_TURBO;
import static com.openai.models.ChatModel.GPT_4O_MINI;
import static com.openai.models.ChatModel.GPT_4_1;
import static com.openai.models.ChatModel.GPT_5;
import static com.openai.models.completions.CompletionCreateParams.Model.GPT_3_5_TURBO_INSTRUCT;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_ENABLED;
import static datadog.trace.test.junit.utils.assertions.Matchers.any;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.Strings;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.credential.BearerTokenCredential;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.completions.CompletionCreateParams;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingModel;
import com.openai.models.responses.CustomTool;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponsePrompt;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceCustom;
import com.sun.net.httpserver.HttpServer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.assertions.Matcher;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

@WithConfig(key = LLMOBS_ENABLED, value = "true")
abstract class OpenAiTest extends AbstractInstrumentationTest {
  // A token uses the real OpenAI backend and records requests/responses for later mock runs.
  // An empty token uses recorded responses from the local mock backend.
  static final String OPENAI_TOKEN = "";
  static final String MOCK_OPENAI_TOKEN = "mock-openai-token";
  private static final Path RECORDS_DIR = Paths.get("src/test/resources/http-records");
  private static final String API_VERSION = "v1";
  protected static OpenAIClient openAiClient;
  protected static String openAiBaseApi;
  private static HttpServer mockOpenAiBackend;

  @FunctionalInterface
  protected interface TagCondition {
    boolean test(Object value);
  }

  @BeforeAll
  static void startOpenAi() throws Exception {
    mockOpenAiBackend = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    Map<String, RequestResponseRecord> cache =
        new LinkedHashMap<String, RequestResponseRecord>(8, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, RequestResponseRecord> entry) {
            return size() > 8;
          }
        };
    mockOpenAiBackend.createContext(
        "/" + API_VERSION + "/",
        exchange -> {
          byte[] requestBody = readBytes(exchange.getRequestBody());
          String recordFile = RequestResponseRecord.requestToFileName("POST", requestBody);
          String subpath = exchange.getRequestURI().getPath().substring(API_VERSION.length() + 2);
          RequestResponseRecord record = cache.get(recordFile);
          if (record == null) {
            Path recordPath = RECORDS_DIR.resolve(subpath).resolve(recordFile);
            if (!recordPath.toFile().exists()) {
              throw new IllegalStateException(
                  "The record file: '"
                      + recordFile
                      + "' is NOT found at "
                      + RECORDS_DIR
                      + ". Set OpenAiTest.OPENAI_TOKEN to make a real request and store the record.");
            }
            record = RequestResponseRecord.read(recordPath);
            cache.put(recordFile, record);
          }
          record.headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
          exchange.sendResponseHeaders(record.status, record.body.length);
          try (OutputStream response = exchange.getResponseBody()) {
            response.write(record.body);
          }
        });
    mockOpenAiBackend.start();
    openAiBaseApi =
        "http://localhost:" + mockOpenAiBackend.getAddress().getPort() + "/" + API_VERSION;
    // Intentional switch for manually recording fixtures.
    //noinspection ConstantValue
    if (Strings.isNullOrEmpty(OPENAI_TOKEN)) {
      // The mock backend uses request/response records.
      openAiClient =
          OpenAIOkHttpClient.builder()
              .baseUrl(openAiBaseApi)
              .credential(BearerTokenCredential.create(MOCK_OPENAI_TOKEN))
              .build();
    } else {
      // The real backend uses a custom HTTP client to capture request/response records.
      ClientOptions.Builder clientOptions = ClientOptions.builder();
      OkHttpClient.Builder httpClient = OkHttpClient.builder();
      openAiBaseApi = ClientOptions.PRODUCTION_URL;
      clientOptions.baseUrl(openAiBaseApi).credential(BearerTokenCredential.create(OPENAI_TOKEN));
      clientOptions.httpClient(new OpenAiHttpClientForTests(httpClient.build(), RECORDS_DIR));
      // Use reflection to set the private HTTP client via clientOptions.
      Class<?> clientClass = Class.forName("com.openai.client.OpenAIClientImpl");
      Constructor<?> constructor = clientClass.getConstructors()[0];
      constructor.setAccessible(true);
      openAiClient = (OpenAIClient) constructor.newInstance(clientOptions.build());
    }
  }

  @AfterAll
  static void stopOpenAi() {
    try {
      if (openAiClient != null) {
        openAiClient.close();
      }
    } finally {
      if (mockOpenAiBackend != null) {
        mockOpenAiBackend.stop(0);
      }
    }
  }

  private static byte[] readBytes(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    for (int length; (length = input.read(buffer)) != -1; ) {
      output.write(buffer, 0, length);
    }
    return output.toByteArray();
  }

  // Preserve fixture property order: recorded requests are selected by a hash of their exact bytes.
  protected static Map<String, Object> map(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    return result;
  }

  protected DDSpan assertOpenAiTrace(
      String resource, String endpoint, Map<String, Object> expectedTags) {
    List<TagsMatcher> tags = new ArrayList<>();
    tags.add(defaultTags());
    expectedTags.forEach((name, expected) -> tags.add(tag(name, tagValueMatcher(name, expected))));
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName(Pattern.compile(Pattern.quote("parent"))),
            span()
                .childOfIndex(0)
                .operationName(Pattern.compile(Pattern.quote("openai.request")))
                .resourceName(Pattern.compile(Pattern.quote(resource)))
                .type(DDSpanTypes.LLMOBS)
                .tags(tags.toArray(new TagsMatcher[0])),
            span()
                .childOfIndex(1)
                .operationName(Pattern.compile(Pattern.quote("okhttp.request")))
                .resourceName(Pattern.compile(Pattern.quote("POST " + endpoint)))
                .type("http")));
    DDSpan openAiSpan =
        writer.firstTrace().stream()
            .filter(value -> "openai.request".contentEquals(value.getOperationName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected an OpenAI span"));
    expectedTags.forEach(
        (name, expected) -> {
          if (expected instanceof TagCondition) {
            assertTrue(((TagCondition) expected).test(normalizeTag(openAiSpan.getTag(name))), name);
          }
        });
    return openAiSpan;
  }

  private static Matcher<?> tagValueMatcher(String name, Object expected) {
    if (expected instanceof Class<?>) {
      return validates(
          value -> {
            assertInstanceOf((Class<?>) expected, normalizeTag(value), name);
            return true;
          });
    }
    if (expected instanceof TagCondition) {
      // Streaming responses may omit rate-limit tags. Validate their values after matching the
      // trace,
      // so the predicate sees null for an absent tag as it did in the Spock asserter.
      return any();
    }
    return validates(value -> Objects.equals(expected, normalizeTag(value)));
  }

  @SuppressWarnings("unchecked")
  protected static <T> T spanTag(DDSpan span, String name) {
    return (T) span.getTag(name);
  }

  private static Object normalizeTag(Object value) {
    // The Spock tag asserter compares CharSequence values as strings.
    return value instanceof CharSequence ? value.toString() : value;
  }

  protected static CompletionCreateParams completionCreateParams(boolean json) {
    if (json) {
      return CompletionCreateParams.builder()
          .model("gpt-3.5-turbo-instruct")
          .prompt("Tell me a story about building the best SDK!")
          .build();
    }

    return CompletionCreateParams.builder()
        .model(GPT_3_5_TURBO_INSTRUCT)
        .prompt("Tell me a story about building the best SDK!")
        .build();
  }

  protected static CompletionCreateParams completionCreateStreamedParams(boolean json) {
    if (json) {
      return CompletionCreateParams.builder()
          .model("gpt-3.5-turbo-instruct")
          .prompt("Tell me a story about building the best SDK!")
          .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
          .build();
    }

    return CompletionCreateParams.builder()
        .model(GPT_3_5_TURBO_INSTRUCT)
        .prompt("Tell me a story about building the best SDK!")
        .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
        .build();
  }

  protected static ChatCompletionCreateParams chatCompletionCreateParams(boolean json) {
    if (json) {
      return ChatCompletionCreateParams.builder()
          .model("gpt-4o-mini")
          .addSystemMessage("")
          .addUserMessage("")
          .build();
    }

    return ChatCompletionCreateParams.builder()
        .model(GPT_4O_MINI)
        .addSystemMessage("")
        .addUserMessage("")
        .build();
  }

  protected static EmbeddingCreateParams embeddingCreateParams(boolean json) {
    if (json) {
      return EmbeddingCreateParams.builder()
          .model("text-embedding-ada-002")
          .input("hello world")
          .build();
    }

    return EmbeddingCreateParams.builder()
        .model(EmbeddingModel.TEXT_EMBEDDING_ADA_002)
        .input("hello world")
        .build();
  }

  protected static ResponseCreateParams responseCreateParams(boolean json) {
    if (json) {
      return ResponseCreateParams.builder()
          .model("gpt-3.5-turbo")
          .input("Do not continue the Evan Li slander!")
          .build();
    }

    return ResponseCreateParams.builder()
        .model(GPT_3_5_TURBO)
        .input("Do not continue the Evan Li slander!")
        .build();
  }

  protected static ResponseCreateParams responseCreateParamsWithMaxOutputTokens(boolean json) {
    if (json) {
      return ResponseCreateParams.builder()
          .model("gpt-3.5-turbo")
          .input("Do not continue the Evan Li slander!")
          .maxOutputTokens(30)
          .build();
    }

    return ResponseCreateParams.builder()
        .model(GPT_3_5_TURBO)
        .input("Do not continue the Evan Li slander!")
        .maxOutputTokens(30)
        .build();
  }

  // Exercise raw JSON fields through the SDK's unparameterized JsonValue API.
  @SuppressWarnings("unchecked")
  protected static ResponseCreateParams responseCreateParamsWithReasoning(boolean json) {
    if (json) {
      return ResponseCreateParams.builder()
          .model("o4-mini")
          .input("If one plus a number is 10, what is the number?")
          .include(Collections.singletonList(ResponseIncludable.of("reasoning.encrypted_content")))
          .reasoning(JsonValue.from(map("effort", "medium", "summary", "detailed")))
          .build();
    }

    return ResponseCreateParams.builder()
        .model(ChatModel.O4_MINI)
        .input("If one plus a number is 10, what is the number?")
        .include(Collections.singletonList(ResponseIncludable.REASONING_ENCRYPTED_CONTENT))
        .reasoning(
            Reasoning.builder()
                .effort(ReasoningEffort.MEDIUM)
                .summary(Reasoning.Summary.DETAILED)
                .build())
        .build();
  }

  protected static ChatCompletionCreateParams chatCompletionCreateParamsWithTools() {
    return ChatCompletionCreateParams.builder()
        .model(GPT_4O_MINI)
        .addUserMessage(
            "David Nguyen is a sophomore majoring in computer science at Stanford University and has a GPA of 3.8.\nDavid is an active member of the university's Chess Club and the South Asian Student Association.\nHe hopes to pursue a career in software engineering after graduating.")
        .addTool(
            ChatCompletionFunctionTool.builder()
                .function(
                    FunctionDefinition.builder()
                        .name("extract_student_info")
                        .description("Get the student information from the body of the input text")
                        .parameters(
                            FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty(
                                    "properties",
                                    JsonValue.from(
                                        map(
                                            "name",
                                            map(
                                                "type",
                                                "string",
                                                "description",
                                                "Name of the person"),
                                            "major",
                                            map("type", "string", "description", "Major subject."),
                                            "school",
                                            map(
                                                "type",
                                                "string",
                                                "description",
                                                "The university name."),
                                            "grades",
                                            map(
                                                "type",
                                                "integer",
                                                "description",
                                                "GPA of the student."),
                                            "clubs",
                                            map(
                                                "type",
                                                "array",
                                                "description",
                                                "School clubs for extracurricular activities. ",
                                                "items",
                                                map(
                                                    "type",
                                                    "string",
                                                    "description",
                                                    "Name of School Club")))))
                                .build())
                        .build())
                .build())
        .build();
  }

  protected static ChatCompletionCreateParams chatCompletionCreateParamsWithToolChoice() {
    return chatCompletionCreateParamsWithTools().toBuilder()
        .toolChoice(
            ChatCompletionNamedToolChoice.builder()
                .type(JsonValue.from("function"))
                .function(
                    ChatCompletionNamedToolChoice.Function.builder()
                        .name("extract_student_info")
                        .build())
                .build())
        .build();
  }

  // Exercise raw JSON fields through the SDK's unparameterized JsonValue API.
  @SuppressWarnings("unchecked")
  protected static ChatCompletionCreateParams chatCompletionCreateParamsWithRawTools() {
    Map<String, Object> functionMap =
        map(
            "name",
            "extract_student_info_raw",
            "description",
            "Extract student information from the input text",
            "parameters",
            map(
                "type",
                "object",
                "properties",
                map(
                    "name",
                    map("type", "string", "description", "Name of the student"),
                    "major",
                    map("type", "string", "description", "Major subject")),
                "required",
                Collections.singletonList("name")));

    return ChatCompletionCreateParams.builder()
        .model("gpt-4o-mini")
        .addUserMessage(
            "Extract the student's name and major.\nAlice Johnson majors in mathematics at UCLA.")
        .addTool(
            ChatCompletionFunctionTool.builder()
                .type(JsonValue.from("function"))
                .function(JsonValue.from(functionMap))
                .build())
        .build();
  }

  // Exercise raw JSON fields through the SDK's unparameterized JsonValue API.
  @SuppressWarnings("unchecked")
  protected static ResponseCreateParams responseCreateParamsWithToolInput(boolean json) {
    if (json) {
      List<Map<String, Object>> rawInputJson =
          Arrays.asList(
              map("role", "user", "content", "What's the weather like in San Francisco?"),
              map(
                  "type",
                  "function_call",
                  "call_id",
                  "call_123",
                  "name",
                  "get_weather",
                  "arguments",
                  "{\"location\": \"San Francisco, CA\"}"),
              map(
                  "type",
                  "function_call_output",
                  "call_id",
                  "call_123",
                  "output",
                  "{\"temperature\": \"72°F\", \"conditions\": \"sunny\", \"humidity\": \"65%\"}"));

      return ResponseCreateParams.builder()
          .model("gpt-4.1")
          .input(JsonValue.from(rawInputJson))
          .temperature(0.1d)
          .build();
    } else {
      ResponseFunctionToolCall functionCall =
          ResponseFunctionToolCall.builder()
              .callId("call_123")
              .name("get_weather")
              .arguments("{\"location\": \"San Francisco, CA\"}")
              .id("fc_123")
              .status(ResponseFunctionToolCall.Status.COMPLETED)
              .build();

      List<ResponseInputItem> inputItems =
          Arrays.asList(
              ResponseInputItem.ofMessage(
                  ResponseInputItem.Message.builder()
                      .role(ResponseInputItem.Message.Role.USER)
                      .addInputTextContent("What's the weather like in San Francisco?")
                      .build()),
              ResponseInputItem.ofFunctionCall(functionCall),
              ResponseInputItem.ofFunctionCallOutput(
                  ResponseInputItem.FunctionCallOutput.builder()
                      .callId("call_123")
                      .output(
                          "{\"temperature\": \"72°F\", \"conditions\": \"sunny\", \"humidity\": \"65%\"}")
                      .build()));

      return ResponseCreateParams.builder()
          .model(GPT_4_1)
          .input(ResponseCreateParams.Input.ofResponse(inputItems))
          .temperature(0.1d)
          .build();
    }
  }

  // Exercise raw JSON fields through the SDK's unparameterized JsonValue API.
  @SuppressWarnings("unchecked")
  protected static ResponseCreateParams responseCreateParamsWithPromptTracking(boolean json) {
    if (json) {
      JsonValue rawPrompt =
          JsonValue.from(
              map(
                  "id",
                  "pmpt_69201db75c4c81959c01ea6987ab023c070192cd2843dec0",
                  "variables",
                  map(
                      "user_message",
                      map("type", "input_text", "text", "Analyze these images and document"),
                      "user_image_1",
                      map(
                          "type",
                          "input_image",
                          "image_url",
                          "https://raw.githubusercontent.com/github/explore/main/topics/python/python.png"),
                      "user_file",
                      map(
                          "type",
                          "input_file",
                          "file_url",
                          "https://www.berkshirehathaway.com/letters/2024ltr.pdf"),
                      "user_image_2",
                      map("type", "input_image", "file_id", "file-BCuhT1HQ24kmtsuuzF1mh2")),
                  "version",
                  "2"));

      return ResponseCreateParams.builder().prompt(rawPrompt).build();
    } else {
      ResponsePrompt.Variables variables =
          ResponsePrompt.Variables.builder()
              .putAdditionalProperty(
                  "user_message",
                  JsonValue.from(
                      map("type", "input_text", "text", "Analyze these images and document")))
              .putAdditionalProperty(
                  "user_image_1",
                  JsonValue.from(
                      map(
                          "type",
                          "input_image",
                          "image_url",
                          "https://raw.githubusercontent.com/github/explore/main/topics/python/python.png")))
              .putAdditionalProperty(
                  "user_file",
                  JsonValue.from(
                      map(
                          "type",
                          "input_file",
                          "file_url",
                          "https://www.berkshirehathaway.com/letters/2024ltr.pdf")))
              .putAdditionalProperty(
                  "user_image_2",
                  JsonValue.from(
                      map("type", "input_image", "file_id", "file-BCuhT1HQ24kmtsuuzF1mh2")))
              .build();

      ResponsePrompt prompt =
          ResponsePrompt.builder()
              .id("pmpt_69201db75c4c81959c01ea6987ab023c070192cd2843dec0")
              .version("2")
              .variables(variables)
              .build();

      return ResponseCreateParams.builder().prompt(prompt).build();
    }
  }

  protected static ResponseCreateParams responseCreateParamsWithCustomToolCall(boolean json) {
    CustomTool customTool =
        CustomTool.builder()
            .name("custom_weather")
            .description("Return weather for a location")
            .formatText()
            .build();

    ToolChoiceCustom toolChoice =
        ToolChoiceCustom.builder().name("custom_weather").type(JsonValue.from("custom")).build();

    if (json) {
      return ResponseCreateParams.builder()
          .model("gpt-5")
          .input("Use the custom_weather tool to answer: What's the weather in Boston?")
          .addTool(customTool)
          .toolChoice(toolChoice)
          .build();
    } else {
      return ResponseCreateParams.builder()
          .model(GPT_5)
          .input("Use the custom_weather tool to answer: What's the weather in Boston?")
          .addTool(customTool)
          .toolChoice(toolChoice)
          .build();
    }
  }

  protected static ResponseCreateParams responseCreateParamsWithFunctionTool(boolean json) {
    FunctionTool functionTool =
        FunctionTool.builder()
            .name("extract_student_info")
            .description("Extract student information from the input text")
            .strict(false)
            .parameters(
                FunctionTool.Parameters.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty(
                        "properties",
                        JsonValue.from(
                            map(
                                "name",
                                map("type", "string", "description", "Name of the student"),
                                "major",
                                map("type", "string", "description", "Major subject"))))
                    .putAdditionalProperty(
                        "required", JsonValue.from(Collections.singletonList("name")))
                    .build())
            .build();

    if (json) {
      return ResponseCreateParams.builder()
          .model("gpt-4.1")
          .input(
              "Extract the student's name and major from: Alice Johnson majors in mathematics at UCLA.")
          .addTool(functionTool)
          .build();
    } else {
      return ResponseCreateParams.builder()
          .model(GPT_4_1)
          .input(
              "Extract the student's name and major from: Alice Johnson majors in mathematics at UCLA.")
          .addTool(functionTool)
          .build();
    }
  }

  protected static ResponseCreateParams responseCreateParamsWithRawFunctionTool() throws Exception {
    JsonField<?> rawTools =
        ObjectMappers.jsonMapper()
            .readValue(
                "[\n        {\n          \"type\": \"function\",\n          \"name\": \"extract_student_info_raw\",\n          \"description\": \"Extract student information from the input text\",\n          \"parameters\": {\n            \"type\": \"object\",\n            \"properties\": {\n              \"name\": {\"type\": \"string\", \"description\": \"Name of the student\"},\n              \"major\": {\"type\": \"string\", \"description\": \"Major subject\"}\n            },\n            \"required\": [\"name\"]\n          }\n        }\n      ]",
                JsonField.class);

    ResponseCreateParams params =
        ResponseCreateParams.builder()
            .model(GPT_4_1)
            .input(
                "Extract the student's name and major from: Alice Johnson majors in mathematics at UCLA.")
            .build();

    Field toolsField = params._body().getClass().getDeclaredField("tools");
    toolsField.setAccessible(true);
    toolsField.set(params._body(), rawTools); // TODO: JEP 500 - avoid mutating final fields

    return params;
  }

  protected static ResponseCreateParams responseCreateParamsWithMcpToolCall() {
    Tool.Mcp mcpTool =
        Tool.Mcp.builder()
            .serverLabel("openai_docs")
            .serverDescription("OpenAI documentation MCP server.")
            .serverUrl("https://developers.openai.com/mcp")
            .requireApproval(Tool.Mcp.RequireApproval.McpToolApprovalSetting.NEVER)
            .build();

    return ResponseCreateParams.builder()
        .model(GPT_5)
        .input(
            "Use the OpenAI docs MCP server to find the Responses API MCP tool documentation and summarize the require_approval option.")
        .addTool(mcpTool)
        .build();
  }

  protected static ChatCompletionCreateParams chatCompletionCreateParamsMultiChoice(boolean json) {
    if (json) {
      return ChatCompletionCreateParams.builder()
          .model("gpt-4o-mini")
          .addSystemMessage("You are a helpful assistant.")
          .addUserMessage("Say 'Hello, world!'")
          .n(3)
          .build();
    } else {
      return ChatCompletionCreateParams.builder()
          .model(GPT_4O_MINI)
          .addSystemMessage("You are a helpful assistant.")
          .addUserMessage("Say 'Hello, world!'")
          .n(3)
          .build();
    }
  }
}
