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
        if ('{"model":"gpt-3.5-turbo-instruct","prompt":"Tell me a story about building the best SDK!","stream":true}' == request.text) {
          System.err.println(">>> streamed")
          response.status(200).send(
              """data: {"id":"cmpl-CYhd8HZjl8iY5SA1poPy3TqMdspV0","object":"text_completion","created":1762386902,"choices":[{"text":"\\n\\n","index":0,"logprobs":null,"finish_reason":null}],"model":"gpt-3.5-turbo-instruct:20230824-v2"}

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
          response.status(200).send(
              """{
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
        }
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

