import com.google.common.base.Charsets
import com.google.common.base.Strings
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.ClientOptions
import com.openai.credential.BearerTokenCredential
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.completions.CompletionCreateParams
import com.openai.models.embeddings.EmbeddingCreateParams
import com.openai.models.embeddings.EmbeddingModel
import com.openai.models.responses.ResponseCreateParams
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.core.util.LRUCache
import datadog.trace.llmobs.LlmObsSpecification
import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.AutoCleanup
import spock.lang.Shared

abstract class OpenAiTest extends LlmObsSpecification {

  // openai token - will use real openai backend and record request/responses to use later in the mock mode
  // null - will use mockOpenAiBackend and read recorded request/responses
  String openAiToken() {
    return null
  }

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
        def recFile = RequestResponseRecord.requestToFileName(request.method, requestBody.getBytes(Charsets.UTF_8))
        def rec = cache.get(recFile)
        if (rec == null) {
          String path = request.path
          def subpath = path.substring(API_VERSION.length() + 2)
          def recsDir = RECORDS_DIR.resolve(subpath)
          def recPath = recsDir.resolve(recFile)
          if (!recPath.toFile().exists()) {
            throw new RuntimeException("The record file: '" + recFile + "' is NOT found at " + RECORDS_DIR)
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

  def setupSpec() {
    if (Strings.isNullOrEmpty(openAiToken())) {
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
      clientOptions.credential(BearerTokenCredential.create(openAiToken()))
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

  CompletionCreateParams completionCreateParams() {
    CompletionCreateParams.builder()
        .model(CompletionCreateParams.Model.GPT_3_5_TURBO_INSTRUCT)
        .prompt("Tell me a story about building the best SDK!")
        .build()
  }

  ChatCompletionCreateParams chatCompletionCreateParams() {
    ChatCompletionCreateParams.builder()
        .model(ChatModel.GPT_4O_MINI)
        .addSystemMessage("")
        .addUserMessage("")
        .build()
  }

  EmbeddingCreateParams embeddingCreateParams() {
    EmbeddingCreateParams.builder()
      .model(EmbeddingModel.TEXT_EMBEDDING_ADA_002)
      .input("hello world")
      .build()
  }

  ResponseCreateParams responseCreateParams() {
    ResponseCreateParams.builder()
      .model(ChatModel.GPT_3_5_TURBO)
      .input("Do not continue the Evan Li slander!")
      .build()
  }
}

