import com.google.common.base.Strings
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.ClientOptions
import com.openai.credential.BearerTokenCredential
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.completions.CompletionCreateParams
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

  static String API_VERSION = "v1"

  @AutoCleanup
  @Shared
  OpenAIClient openAiClient

  @Shared
  def openAiBaseApi

  @AutoCleanup
  @Shared
  def mockOpenAiBackend = TestHttpServer.httpServer {
    handlers {
      prefix("/$API_VERSION/chat/completions") {
        response
            .status(200)
            .addHeader("openai-organization", "datadog-staging")
            .addHeader("x-ratelimit-limit-requests", "30000")
            .addHeader("x-ratelimit-limit-tokens", "150000000")
            .addHeader("x-ratelimit-remaining-requests", "29999")
            .addHeader("x-ratelimit-remaining-tokens", "149999997")
            .send("""
{
  "id": "chatcmpl-CaZMmD0wsnDrkBEND9i5MvH6sD8BJ",
  "object": "chat.completion",
  "created": 1762831792,
  "model": "gpt-4o-mini-2024-07-18",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I assist you today?",
        "refusal": null,
        "annotations": []
      },
      "logprobs": null,
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 11,
    "completion_tokens": 9,
    "total_tokens": 20,
    "prompt_tokens_details": {
      "cached_tokens": 0,
      "audio_tokens": 0
    },
    "completion_tokens_details": {
      "reasoning_tokens": 0,
      "audio_tokens": 0,
      "accepted_prediction_tokens": 0,
      "rejected_prediction_tokens": 0
    }
  },
  "service_tier": "default",
  "system_fingerprint": "fp_51db84afab"
}
""")
        if ('{"messages":[{"content":"","role":"system"},{"content":"","role":"user"}],"model":"gpt-4o-mini"}' == request.text) {
        } else {
          response.status(500).send("Unexpected Request!")
        }
      }
      prefix("/$API_VERSION/completions") {
        if ('{"model":"gpt-3.5-turbo-instruct","prompt":"Tell me a story about building the best SDK!","stream":true}' == request.text) {
          response
              .addHeader("openai-organization", "datadog-staging")
              .addHeader("x-ratelimit-limit-requests", "3500")
              .addHeader("x-ratelimit-remaining-requests", "3499")
              .addHeader("x-ratelimit-limit-tokens", "90000")
              .addHeader("x-ratelimit-remaining-tokens", "89994")
              .status(200)
              .send("""data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":"\\n\\n","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":"Once","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":" upon","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":" a","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":" time","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":",","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":" there","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":" was a company called","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":" \\"","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":"Tech","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":" Innovations\\"","index":0,"logprobs":null,"finish_reason":"length"}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":"","index":0,"logprobs":null,"finish_reason":"length"}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

data: [DONE]

""")
        } else if ('{"model":"gpt-3.5-turbo-instruct","prompt":"Tell me a story about building the best SDK!"}' == request.text) {
          response
              .addHeader("openai-organization", "datadog-staging")
              .addHeader("x-ratelimit-limit-requests", "3500")
              .addHeader("x-ratelimit-remaining-requests", "3499")
              .addHeader("x-ratelimit-limit-tokens", "90000")
              .addHeader("x-ratelimit-remaining-tokens", "89994")
              .status(200)
              .send("""{
  "id": "cmpl-CYhd78PVSfxem8cdTSGsgZnSU9e2U",
  "object": "text_completion",
  "created": 1762386901,
  "model": "gpt-3.5-turbo-instruct:20230824-v2",
  "choices": [
    {
      "text": "\\n\\nOnce upon a time, in a busy and bustling tech industry, there was",
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
        } else {
          response.status(500).send("Unexpected Request!")
        }
      }
    }
  }

  def setupSpec() {
    OpenAIOkHttpClient.Builder b = OpenAIOkHttpClient.builder()
    if (Strings.isNullOrEmpty(openAiToken())) {
      // mock backend
      openAiBaseApi = "${mockOpenAiBackend.address.toURL()}/$API_VERSION"
      b.baseUrl(openAiBaseApi)
      b.credential(BearerTokenCredential.create(""))
    } else {
      // real openai backend
      b.credential(BearerTokenCredential.create(openAiToken()))
      openAiBaseApi = ClientOptions.PRODUCTION_URL
    }
    openAiClient = b.build()
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
        // .prompt("Tell me a story about building the best SDK!")
        .build()
  }
}

