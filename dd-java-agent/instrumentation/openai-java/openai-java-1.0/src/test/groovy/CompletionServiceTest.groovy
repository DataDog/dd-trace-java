import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import com.openai.core.http.AsyncStreamResponse
import com.openai.core.http.HttpResponseFor
import com.openai.core.http.StreamResponse
import com.openai.models.completions.Completion
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.openai_java.OpenAiDecorator
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

class CompletionServiceTest extends OpenAiTest {

  def "create completion test"() {
    Completion resp = runUnderTrace("parent") {
      openAiClient.completions().create(completionCreateParams())
    }

    expect:
    resp != null
    and:
    assertCompletionTrace()
  }

  def "create completion test withRawResponse"() {
    HttpResponseFor<Completion> resp = runUnderTrace("parent") {
      openAiClient.withRawResponse().completions().create(completionCreateParams())
    }

    expect:
    resp.statusCode() == 200
    resp.parse().valid // force response parsing, so it sets all the tags
    and:
    assertCompletionTrace()
  }

  def "create streaming completion test"() {
    runnableUnderTrace("parent") {
      StreamResponse<Completion> streamCompletion = openAiClient.completions().createStreaming(completionCreateParams())
      try (Stream stream = streamCompletion.stream()) {
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertCompletionTrace()
  }

  def "create streaming completion test withRawResponse"() {
    runnableUnderTrace("parent") {
      HttpResponseFor<StreamResponse<Completion>> streamCompletion = openAiClient.completions().withRawResponse().createStreaming(completionCreateParams())
      try (Stream stream = streamCompletion.parse().stream()) {
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertCompletionTrace()
  }

  def "create async completion test"() {
    CompletableFuture<Completion> completionFuture = runUnderTrace("parent") {
      openAiClient.async().completions().create(completionCreateParams())
    }

    completionFuture.get()

    expect:
    assertCompletionTrace()
  }

  def "create async completion test withRawResponse"() {
    CompletableFuture<HttpResponseFor<Completion>> completionFuture = runUnderTrace("parent") {
      openAiClient.async().completions().withRawResponse().create(completionCreateParams())
    }

    def resp = completionFuture.get()
    resp.parse().valid // force response parsing, so it sets all the tags

    expect:
    assertCompletionTrace()
  }

  def "create streaming async completion test"() {
    AsyncStreamResponse<Completion> asyncResp = runUnderTrace("parent") {
      openAiClient.async().completions().createStreaming(completionCreateParams())
    }
    asyncResp.subscribe {
      // consume completions
    }
    asyncResp.onCompleteFuture().get()
    expect:
    assertCompletionTrace()
  }

  def "create streaming async completion test withRawResponse"() {
    CompletableFuture<HttpResponseFor<StreamResponse<Completion>>> future = runUnderTrace("parent") {
      openAiClient.async().completions().withRawResponse().createStreaming(completionCreateParams())
    }
    HttpResponseFor<StreamResponse<Completion>> resp = future.get()
    try (Stream stream = resp.parse().stream()) {
      stream.forEach {
        // consume the stream
      }
    }
    expect:
    resp.statusCode() == 200
    assertCompletionTrace()
  }

  private void assertCompletionTrace() {
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
          resourceName "createCompletion"
          childOf span(0)
          errored false
          spanType DDSpanTypes.LLMOBS
          tags {
            "_ml_obs_tag.parent_id" "undefined"
            "openai.request.method" "POST"
            "openai.request.endpoint" "v1/completions"
            "openai.api_base" openAiBaseApi
            "openai.organization.ratelimit.requests.limit" 3500
            "openai.organization.ratelimit.requests.remaining" Integer
            "openai.organization.ratelimit.tokens.limit" 90000
            "openai.organization.ratelimit.tokens.remaining" Integer
            "$OpenAiDecorator.REQUEST_MODEL" "gpt-3.5-turbo-instruct"
            "$OpenAiDecorator.RESPONSE_MODEL" "gpt-3.5-turbo-instruct:20230824-v2"
            "$OpenAiDecorator.OPENAI_ORGANIZATION_NAME" "datadog-staging"
            "$Tags.COMPONENT" "openai"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span(2) {
          operationName "okhttp.request"
          resourceName "POST /v1/completions"
          childOf span(1)
          errored false
          spanType "http"
        }
      }
    }
  }
}
