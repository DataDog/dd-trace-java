import com.google.common.base.Strings
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.credential.BearerTokenCredential
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.llmobs.LlmObsSpecification
import spock.lang.AutoCleanup
import spock.lang.Shared


abstract class OpenAiTest extends LlmObsSpecification {

  // openai token - will use real openai backend
  // null - will use mockOpenAiBackend
  String openAiToken() {
    return null
  }

  @AutoCleanup
  @Shared
  OpenAIClient openAiClient

  @AutoCleanup
  @Shared
  def mockOpenAiBackend = TestHttpServer.httpServer {
    handlers {
      prefix("/completions") {
        response.status(200).send(
            """
                  {
                    "id": "cmpl-CUJTd66qbuEe2vu9cSEUWoFKFKm6O",
                    "object": "text_completion",
                    "created": 1761340745,
                    "model": "gpt-3.5-turbo-instruct:20230824-v2",
                    "choices": [
                      {
                        "text": "\\n\\nOnce upon a time in a tech company called Innovix, a team of",
                        "index": 0,
                        "logprobs": null,
                        "finish_reason": "length"
                      }
                    ],
                    "usage": {
                      "prompt_tokens": 10,
                      "completion_tokens": 16,
                      "total_tokens": 26
                    }
                  }
                  """
        )
      }
    }
  }

  def setupSpec() {
    OpenAIOkHttpClient.Builder b = OpenAIOkHttpClient.builder()
    if (Strings.isNullOrEmpty(openAiToken())) {
      // mock backend
      b.baseUrl(mockOpenAiBackend.address.toURL().toString())
      b.credential(BearerTokenCredential.create(""))
    } else {
      // real openai backend
      b.credential(BearerTokenCredential.create(openAiToken()))
    }
    openAiClient = b.build()
  }
}

