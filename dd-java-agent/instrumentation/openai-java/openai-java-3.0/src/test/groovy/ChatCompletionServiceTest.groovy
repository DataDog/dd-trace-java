import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

import com.openai.core.http.AsyncStreamResponse
import com.openai.core.http.HttpResponseFor
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.completions.Completion
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.openai_java.OpenAiDecorator
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

class ChatCompletionServiceTest extends OpenAiTest {

  // TODO add a multi-choice response tests

  def "create chat/completion test"() {
    ChatCompletion resp = runUnderTrace("parent") {
      openAiClient.chat().completions().create(chatCompletionCreateParams())
    }

    expect:
    resp != null
    and:
    assertChatCompletionTrace(false)
  }

  def "create chat/completion test withRawResponse"() {
    HttpResponseFor<ChatCompletion> resp = runUnderTrace("parent") {
      openAiClient.chat().withRawResponse().completions().create(chatCompletionCreateParams())
    }

    expect:
    resp.statusCode() == 200
    resp.parse().valid // force response parsing, so it sets all the tags
    and:
    assertChatCompletionTrace(false)
  }

  def "create streaming chat/completion test"() {
    runnableUnderTrace("parent") {
      StreamResponse<ChatCompletionChunk> streamCompletion = openAiClient.chat().completions().createStreaming(chatCompletionCreateParams())
      try (Stream stream = streamCompletion.stream()) {
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertChatCompletionTrace(true)
  }

  def "create streaming chat/completion test withRawResponse"() {
    runnableUnderTrace("parent") {
      HttpResponseFor<StreamResponse<ChatCompletionChunk>> streamCompletion = openAiClient.chat().completions().withRawResponse().createStreaming(chatCompletionCreateParams())
      try (Stream stream = streamCompletion.parse().stream()) {
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertChatCompletionTrace(true)
  }

  def "create async chat/completion test"() {
    CompletableFuture<ChatCompletion> completionFuture = runUnderTrace("parent") {
      openAiClient.async().chat().completions().create(chatCompletionCreateParams())
    }

    completionFuture.get()

    expect:
    assertChatCompletionTrace(false)
  }

  def "create async chat/completion test withRawResponse"() {
    CompletableFuture<HttpResponseFor<Completion>> completionFuture = runUnderTrace("parent") {
      openAiClient.async().chat().completions().withRawResponse().create(chatCompletionCreateParams())
    }

    def resp = completionFuture.get()
    resp.parse().valid // force response parsing, so it sets all the tags

    expect:
    assertChatCompletionTrace(false)
  }

  def "create streaming async chat/completion test"() {
    AsyncStreamResponse<ChatCompletionChunk> asyncResp = runUnderTrace("parent") {
      openAiClient.async().chat().completions().createStreaming(chatCompletionCreateParams())
    }
    asyncResp.subscribe {
      // consume completions
    }
    asyncResp.onCompleteFuture().get()
    expect:
    assertChatCompletionTrace(true)
  }

  def "create streaming async chat/completion test withRawResponse"() {
    CompletableFuture<HttpResponseFor<StreamResponse<ChatCompletionChunk>>> future = runUnderTrace("parent") {
      openAiClient.async().chat().completions().withRawResponse().createStreaming(chatCompletionCreateParams())
    }
    HttpResponseFor<StreamResponse<ChatCompletionChunk>> resp = future.get()
    try (Stream stream = resp.parse().stream()) {
      stream.forEach {
        // consume the stream
      }
    }
    expect:
    resp.statusCode() == 200
    assertChatCompletionTrace(true)
  }

  def "create chat/completion test with tool calls"() {
    ChatCompletion resp = runUnderTrace("parent") {
      openAiClient.chat().completions().create(chatCompletionCreateParamsWithTools())
    }

    expect:
    resp != null
    resp.choices().size() == 1
    resp.choices().get(0).message().toolCalls().isPresent()
    resp.choices().get(0).message().toolCalls().get().size() == 1
    resp.choices().get(0).message().toolCalls().get().get(0).function().get().function().name() == "extract_student_info"
    and:
    assertChatCompletionTrace(false)
  }

  private void assertChatCompletionTrace(boolean isStreaming) {
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          parent()
          errored false
        }
        span(1) {
          operationName "openai.request"
          resourceName "createChatCompletion"
          childOf span(0)
          errored false
          spanType DDSpanTypes.LLMOBS
          tags {
            "_ml_obs_tag.span.kind" "llm"
            "_ml_obs_tag.model_provider" "openai"
            "_ml_obs_tag.model_name" String
            "_ml_obs_tag.metadata" Map
            "_ml_obs_tag.input" List
            "_ml_obs_tag.output" List
            if (!isStreaming) {
              // streamed completions missing usage data
              "_ml_obs_metric.input_tokens" Long
              "_ml_obs_metric.output_tokens" Long
              "_ml_obs_metric.total_tokens" Long
            }
            "_ml_obs_tag.parent_id" "undefined"
            "openai.request.method" "POST"
            "openai.request.endpoint" "v1/chat/completions"
            "openai.api_base" openAiBaseApi
            "openai.organization.ratelimit.requests.limit" 30000
            "openai.organization.ratelimit.requests.remaining" Integer
            "openai.organization.ratelimit.tokens.limit" 150000000
            "openai.organization.ratelimit.tokens.remaining" Integer
            "$OpenAiDecorator.REQUEST_MODEL" "gpt-4o-mini"
            "$OpenAiDecorator.RESPONSE_MODEL" "gpt-4o-mini-2024-07-18"
            "$OpenAiDecorator.OPENAI_ORGANIZATION_NAME" "datadog-staging"
            "$Tags.COMPONENT" "openai"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span(2) {
          operationName "okhttp.request"
          resourceName "POST /v1/chat/completions"
          childOf span(1)
          errored false
          spanType "http"
        }
      }
    }
  }
}
