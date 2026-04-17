import static datadog.environment.OperatingSystem.isArm64

import com.google.common.base.Charsets
import com.google.common.base.Strings
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.ClientOptions
import com.openai.core.ObjectMappers
import com.openai.credential.BearerTokenCredential
import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice
import com.openai.models.chat.completions.ChatCompletionStreamOptions
import com.openai.models.completions.CompletionCreateParams
import com.openai.models.embeddings.EmbeddingCreateParams
import com.openai.models.embeddings.EmbeddingModel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseIncludable
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponsePrompt
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.CustomTool
import com.openai.models.responses.Tool
import com.openai.models.responses.ToolChoiceCustom
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.config.LlmObsConfig
import datadog.trace.core.util.LRUCache
import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.AutoCleanup
import spock.lang.Shared

abstract class OpenAiTest extends InstrumentationSpecification {

  // openai token - will use real openai backend and record request/responses to use later in the mock mode
  // empty or null - will use mockOpenAiBackend and read recorded request/responses
  static final String OPENAI_TOKEN = ""

  private static final Path RECORDS_DIR = Paths.get("src/test/resources/http-records")
  private static final String API_VERSION = "v1"

  @AutoCleanup
  @Shared
  OpenAIClient openAiClient

  @Shared
  def openAiBaseApi

  @AutoCleanup
  @Shared
  def mockOpenAiBackend = TestHttpServer.httpServer {
    LRUCache<String, RequestResponseRecord> cache = new LRUCache(8)
    handlers {
      prefix("/$API_VERSION/") {
        def requestBody = request.text
        def recFile = RequestResponseRecord.requestToFileName("POST", requestBody.getBytes(Charsets.UTF_8))
        def rec = cache.get(recFile)
        if (rec == null) {
          String path = request.path
          def subpath = path.substring(API_VERSION.length() + 2)
          def recsDir = RECORDS_DIR.resolve(subpath)
          def recPath = recsDir.resolve(recFile)

          // TODO: Codex generated patch to pass under arm64, revisit later.
          if (!recPath.toFile().exists()) {
            recPath = RequestResponseRecord.findEquivalentRequestRecord(
            RECORDS_DIR,
            "POST",
            subpath,
            requestBody.getBytes(Charsets.UTF_8))
          }
          if (!recPath.toFile().exists()) {
            throw new RuntimeException("The record file: '" + recFile + "' is NOT found at " + RECORDS_DIR + ". Set OpenAiTest.OPENAI_TOKEN to make a real request and store the record.")
          } else {
            rec = RequestResponseRecord.read(recPath)
            cache.put(recFile, rec)
          }
        }
        def resp = response
        resp.status(rec.status)
        rec.headers.forEach(resp::addHeader)
        resp.send(rec.body)
      }
    }
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(LlmObsConfig.LLMOBS_ENABLED, "true")
  }

  def setupSpec() {
    if (Strings.isNullOrEmpty(OPENAI_TOKEN)) {
      // mock backend uses request/response records
      OpenAIOkHttpClient.Builder b = OpenAIOkHttpClient.builder()
      openAiBaseApi = "${mockOpenAiBackend.address.toURL()}/$API_VERSION"
      b.baseUrl(openAiBaseApi)
      b.credential(BearerTokenCredential.create(""))
      openAiClient = b.build()
    } else {
      // real openai backend, with custom httpClient to capture and save request/response records
      ClientOptions.Builder clientOptions = ClientOptions.builder()
      OkHttpClient.Builder httpClient = OkHttpClient.builder()
      openAiBaseApi = ClientOptions.PRODUCTION_URL
      httpClientUrlIfExists(httpClient, openAiBaseApi)
      clientOptions.baseUrl(openAiBaseApi)
      clientOptions.credential(BearerTokenCredential.create(OPENAI_TOKEN))
      clientOptions.httpClient(new OpenAiHttpClientForTests(httpClient.build(), RECORDS_DIR))
      openAiClient = createOpenAiClient(clientOptions.build())
    }
  }

  void httpClientUrlIfExists(OkHttpClient.Builder httpClient, String url) {
    try {
      def method = httpClient.getClass().getMethod("baseUrl", String)
      method.invoke(httpClient, url)
    } catch (NoSuchMethodException e) {
      // method exists and mandatory only prior to v3.0.0
    }
  }

  OpenAIClient createOpenAiClient(ClientOptions clientOptions) {
    // use reflection to set private httpClient via clientOptions
    def clazz = Class.forName("com.openai.client.OpenAIClientImpl")
    def constructor = clazz.constructors[0]
    constructor.accessible = true
    constructor.newInstance(clientOptions) as OpenAIClient
  }

  CompletionCreateParams completionCreateParams(boolean json) {
    if (json) {
      CompletionCreateParams.builder()
      .model("gpt-3.5-turbo-instruct")
      .prompt("Tell me a story about building the best SDK!")
      .build()
    } else {
      CompletionCreateParams.builder()
      .model(CompletionCreateParams.Model.GPT_3_5_TURBO_INSTRUCT)
      .prompt("Tell me a story about building the best SDK!")
      .build()
    }
  }

  CompletionCreateParams completionCreateStreamedParams(boolean json) {
    if (json) {
      CompletionCreateParams.builder()
      .model("gpt-3.5-turbo-instruct")
      .prompt("Tell me a story about building the best SDK!")
      .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
      .build()
    } else {
      CompletionCreateParams.builder()
      .model(CompletionCreateParams.Model.GPT_3_5_TURBO_INSTRUCT)
      .prompt("Tell me a story about building the best SDK!")
      .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
      .build()
    }
  }

  ChatCompletionCreateParams chatCompletionCreateParams(boolean json) {
    if (json) {
      ChatCompletionCreateParams.builder()
      .model("gpt-4o-mini")
      .addSystemMessage("")
      .addUserMessage("")
      .build()
    } else {
      ChatCompletionCreateParams.builder()
      .model(ChatModel.GPT_4O_MINI)
      .addSystemMessage("")
      .addUserMessage("")
      .build()
    }
  }

  EmbeddingCreateParams embeddingCreateParams(boolean json) {
    if (json) {
      EmbeddingCreateParams.builder()
      .model("text-embedding-ada-002")
      .input("hello world")
      .build()
    } else {
      EmbeddingCreateParams.builder()
      .model(EmbeddingModel.TEXT_EMBEDDING_ADA_002)
      .input("hello world")
      .build()
    }
  }

  ResponseCreateParams responseCreateParams(boolean json) {
    if (json) {
      ResponseCreateParams.builder()
      .model("gpt-3.5-turbo")
      .input("Do not continue the Evan Li slander!")
      .build()
    } else {
      ResponseCreateParams.builder()
      .model(ChatModel.GPT_3_5_TURBO)
      .input("Do not continue the Evan Li slander!")
      .build()
    }
  }

  ResponseCreateParams responseCreateParamsWithMaxOutputTokens(boolean json) {
    if (json) {
      ResponseCreateParams.builder()
      .model("gpt-3.5-turbo")
      .input("Do not continue the Evan Li slander!")
      .maxOutputTokens(30)
      .build()
    }    else {
      ResponseCreateParams.builder()
      .model(ChatModel.GPT_3_5_TURBO)
      .input("Do not continue the Evan Li slander!")
      .maxOutputTokens(30)
      .build()
    }
  }

  ResponseCreateParams responseCreateParamsWithReasoning(boolean json) {
    if (json) {
      ResponseCreateParams.builder()
      .model("o4-mini")
      .input("If one plus a number is 10, what is the number?")
      .include(Collections.singletonList(ResponseIncludable.of("reasoning.encrypted_content")))
      .reasoning(JsonValue.from([effort: "medium", summary: "detailed"]))
      .build()
    } else {
      ResponseCreateParams.builder()
      .model(ChatModel.O4_MINI)
      .input("If one plus a number is 10, what is the number?")
      .include(Collections.singletonList(ResponseIncludable.REASONING_ENCRYPTED_CONTENT))
      .reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).summary(Reasoning.Summary.DETAILED).build())
      .build()
    }
  }

  ChatCompletionCreateParams chatCompletionCreateParamsWithTools() {
    ChatCompletionCreateParams.builder()
    .model(ChatModel.GPT_4O_MINI)
    .addUserMessage("""David Nguyen is a sophomore majoring in computer science at Stanford University and has a GPA of 3.8.
David is an active member of the university's Chess Club and the South Asian Student Association.
He hopes to pursue a career in software engineering after graduating.""")
    .addTool(ChatCompletionFunctionTool.builder()
    .function(FunctionDefinition.builder()
    .name("extract_student_info")
    .description("Get the student information from the body of the input text")
    .parameters(FunctionParameters.builder()
    .putAdditionalProperty("type", JsonValue.from("object"))
    .putAdditionalProperty("properties", JsonValue.from([
      name: [type: "string", description: "Name of the person"],
      major: [type: "string", description: "Major subject."],
      school: [type: "string", description: "The university name."],
      grades: [type: "integer", description: "GPA of the student."],
      clubs: [
        type: "array",
        description: "School clubs for extracurricular activities. ",
        items: [type: "string", description: "Name of School Club"]
      ]
    ]))
    .build())
    .build())
    .build())
    .build()
  }

  ChatCompletionCreateParams chatCompletionCreateParamsWithToolChoice() {
    chatCompletionCreateParamsWithTools().toBuilder()
    .toolChoice(ChatCompletionNamedToolChoice.builder()
    .type(JsonValue.from("function"))
    .function(ChatCompletionNamedToolChoice.Function.builder()
    .name("extract_student_info")
    .build())
    .build())
    .build()
  }

  ChatCompletionCreateParams chatCompletionCreateParamsWithRawTools() {
    def functionMap = [
      name: "extract_student_info_raw",
      description: "Extract student information from the input text",
      parameters: [
        type: "object",
        properties: [
          name: [type: "string", description: "Name of the student"],
          major: [type: "string", description: "Major subject"],
        ],
        required: ["name"],
      ]
    ]

    ChatCompletionCreateParams.builder()
    .model("gpt-4o-mini")
    .addUserMessage("""Extract the student's name and major.
Alice Johnson majors in mathematics at UCLA.""")
    .addTool(ChatCompletionFunctionTool.builder()
    .type(JsonValue.from("function"))
    .function(JsonValue.from(functionMap))
    .build())
    .build()
  }

  ResponseCreateParams responseCreateParamsWithToolInput(boolean json) {
    if (json) {
      def rawInputJson = [
        [
          role: "user",
          content: "What's the weather like in San Francisco?"
        ],
        [
          type: "function_call",
          call_id: "call_123",
          name: "get_weather",
          arguments: '{"location": "San Francisco, CA"}'
        ],
        [
          type: "function_call_output",
          call_id: "call_123",
          output: '{"temperature": "72°F", "conditions": "sunny", "humidity": "65%"}'
        ]
      ]

      ResponseCreateParams.builder()
      .model("gpt-4.1")
      .input(JsonValue.from(rawInputJson))
      .temperature(0.1d)
      .build()
    } else {
      def functionCall = ResponseFunctionToolCall.builder()
      .callId("call_123")
      .name("get_weather")
      .arguments('{"location": "San Francisco, CA"}')
      .id("fc_123")
      .status(ResponseFunctionToolCall.Status.COMPLETED)
      .build()

      def inputItems = [
        ResponseInputItem.ofMessage(ResponseInputItem.Message.builder()
        .role(ResponseInputItem.Message.Role.USER)
        .addInputTextContent("What's the weather like in San Francisco?")
        .build()),
        ResponseInputItem.ofFunctionCall(functionCall),
        ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
        .callId("call_123")
        .output('{"temperature": "72°F", "conditions": "sunny", "humidity": "65%"}')
        .build())
      ]

      ResponseCreateParams.builder()
      .model(ChatModel.GPT_4_1)
      .input(ResponseCreateParams.Input.ofResponse(inputItems))
      .temperature(0.1d)
      .build()
    }
  }

  ResponseCreateParams responseCreateParamsWithPromptTracking(boolean json) {
    if (json) {
      def rawPrompt = JsonValue.from([
        id: "pmpt_69201db75c4c81959c01ea6987ab023c070192cd2843dec0",
        variables: [
          user_message: [type: "input_text", text: "Analyze these images and document"],
          user_image_1: [type: "input_image", image_url: "https://raw.githubusercontent.com/github/explore/main/topics/python/python.png"],
          user_file: [type: "input_file", file_url: "https://www.berkshirehathaway.com/letters/2024ltr.pdf"],
          user_image_2: [type: "input_image", file_id: "file-BCuhT1HQ24kmtsuuzF1mh2"],
        ],
        version: "2",
      ])

      ResponseCreateParams.builder()
      .prompt(rawPrompt)
      .build()
    } else {
      def variables = ResponsePrompt.Variables.builder()
      .putAdditionalProperty("user_message", JsonValue.from([type: "input_text", text: "Analyze these images and document"]))
      .putAdditionalProperty("user_image_1", JsonValue.from([type: "input_image", image_url: "https://raw.githubusercontent.com/github/explore/main/topics/python/python.png"]))
      .putAdditionalProperty("user_file", JsonValue.from([type: "input_file", file_url: "https://www.berkshirehathaway.com/letters/2024ltr.pdf"]))
      .putAdditionalProperty("user_image_2", JsonValue.from([type: "input_image", file_id: "file-BCuhT1HQ24kmtsuuzF1mh2"]))
      .build()

      def prompt = ResponsePrompt.builder()
      .id("pmpt_69201db75c4c81959c01ea6987ab023c070192cd2843dec0")
      .version("2")
      .variables(variables)
      .build()

      ResponseCreateParams.builder()
      .prompt(prompt)
      .build()
    }
  }

  ResponseCreateParams responseCreateParamsWithCustomToolCall(boolean json) {
    def customTool = CustomTool.builder()
    .name("custom_weather")
    .description("Return weather for a location")
    .formatText()
    .build()

    def toolChoice = ToolChoiceCustom.builder()
    .name("custom_weather")
    .type(JsonValue.from("custom"))
    .build()

    if (json) {
      ResponseCreateParams.builder()
      .model("gpt-5")
      .input("Use the custom_weather tool to answer: What's the weather in Boston?")
      .addTool(customTool)
      .toolChoice(toolChoice)
      .build()
    } else {
      ResponseCreateParams.builder()
      .model(ChatModel.GPT_5)
      .input("Use the custom_weather tool to answer: What's the weather in Boston?")
      .addTool(customTool)
      .toolChoice(toolChoice)
      .build()
    }
  }

  ResponseCreateParams responseCreateParamsWithFunctionTool(boolean json) {
    def functionTool = FunctionTool.builder()
    .name("extract_student_info")
    .description("Extract student information from the input text")
    .strict(false)
    .parameters(FunctionTool.Parameters.builder()
    .putAdditionalProperty("type", JsonValue.from("object"))
    .putAdditionalProperty("properties", JsonValue.from([
      name: [type: "string", description: "Name of the student"],
      major: [type: "string", description: "Major subject"],
    ]))
    .putAdditionalProperty("required", JsonValue.from(["name"]))
    .build())
    .build()

    if (json) {
      ResponseCreateParams.builder()
      .model("gpt-4.1")
      .input("Extract the student's name and major from: Alice Johnson majors in mathematics at UCLA.")
      .addTool(functionTool)
      .build()
    } else {
      ResponseCreateParams.builder()
      .model(ChatModel.GPT_4_1)
      .input("Extract the student's name and major from: Alice Johnson majors in mathematics at UCLA.")
      .addTool(functionTool)
      .build()
    }
  }

  ResponseCreateParams responseCreateParamsWithRawFunctionTool() {
    def rawTools = ObjectMappers.jsonMapper().readValue(
    """[
        {
          "type": "function",
          "name": "extract_student_info_raw",
          "description": "Extract student information from the input text",
          "parameters": {
            "type": "object",
            "properties": {
              "name": {"type": "string", "description": "Name of the student"},
              "major": {"type": "string", "description": "Major subject"}
            },
            "required": ["name"]
          }
        }
      ]""",
    com.openai.core.JsonField
    )

    ResponseCreateParams params = ResponseCreateParams.builder()
    .model(ChatModel.GPT_4_1)
    .input("Extract the student's name and major from: Alice Johnson majors in mathematics at UCLA.")
    .build()

    def toolsField = params._body().class.getDeclaredField("tools")
    toolsField.accessible = true
    toolsField.set(params._body(), rawTools)

    params
  }

  ResponseCreateParams responseCreateParamsWithMcpToolCall() {
    def mcpTool = Tool.Mcp.builder()
    .serverLabel("openai_docs")
    .serverDescription("OpenAI documentation MCP server.")
    .serverUrl("https://developers.openai.com/mcp")
    .requireApproval(Tool.Mcp.RequireApproval.McpToolApprovalSetting.NEVER)
    .build()

    ResponseCreateParams.builder()
    .model(ChatModel.GPT_5)
    .input("Use the OpenAI docs MCP server to find the Responses API MCP tool documentation and summarize the require_approval option.")
    .addTool(mcpTool)
    .build()
  }

  ChatCompletionCreateParams chatCompletionCreateParamsMultiChoice(boolean json) {
    if (json) {
      ChatCompletionCreateParams.builder()
      .model("gpt-4o-mini")
      .addSystemMessage("You are a helpful assistant.")
      .addUserMessage("Say 'Hello, world!'")
      .n(3)
      .build()
    } else {
      ChatCompletionCreateParams.builder()
      .model(ChatModel.GPT_4O_MINI)
      .addSystemMessage("You are a helpful assistant.")
      .addUserMessage("Say 'Hello, world!'")
      .n(3)
      .build()
    }
  }
}
