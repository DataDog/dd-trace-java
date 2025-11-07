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

  def "single request completion test"() {
    Completion resp = runUnderTrace("parent") {
      openAiClient.completions().create(completionCreateParams())
    }

    expect:
    resp != null
    and:
    assertCompletionTrace()
  }

  def "single request completion test with withRawResponse"() {
    HttpResponseFor<Completion> resp = runUnderTrace("parent") {
      openAiClient.withRawResponse().completions().create(completionCreateParams())
    }

    expect:
    resp.statusCode() == 200
    and:
    assertCompletionTrace()
  }

  def "streamed request completion test"() {
    runnableUnderTrace("parent") {
      StreamResponse<Completion> streamCompletion = openAiClient.completions().createStreaming(completionCreateParams())
      try (Stream stream = streamCompletion.stream()) { // close the stream after use
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertCompletionTrace()
  }

  def "streamed request completion test with withRawResponse"() {
    runnableUnderTrace("parent") {
      HttpResponseFor<StreamResponse<Completion>> streamCompletion = openAiClient.completions().withRawResponse().createStreaming(completionCreateParams())
      try (Stream stream = streamCompletion.parse().stream()) { // close the stream after use
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertCompletionTrace()
  }

  def "single async request completion test"() {
    CompletableFuture<Completion> completionFuture = runUnderTrace("parent") {
      openAiClient.async().completions().create(completionCreateParams())
    }

    completionFuture.get()

    expect:
    assertCompletionTrace()
  }

  def "single async request completion test with withRawResponse"() {
    CompletableFuture<HttpResponseFor<Completion>> completionFuture = runUnderTrace("parent") {
      openAiClient.async().completions().withRawResponse().create(completionCreateParams())
    }

    completionFuture.get()

    expect:
    assertCompletionTrace()
  }

  def "streamed async request completion test"() {
    AsyncStreamResponse<Completion> response = runUnderTrace("parent") {
      openAiClient.async().completions().createStreaming(completionCreateParams())
    }

    response.subscribe {
      // consume completions
      // System.err.println(">>> completion: " + it)
    }
    response.onCompleteFuture().get()

    expect:
    assertCompletionTrace()
  }

  def "streamed async request completion test with withRawResponse"() {
    CompletableFuture<HttpResponseFor<StreamResponse<Completion>>> future = runUnderTrace("parent") {
      openAiClient.async().completions().withRawResponse().createStreaming(completionCreateParams())
    }

    HttpResponseFor<StreamResponse<Completion>> response = future.get()

    try (Stream stream = response.parse().stream()) { // close the stream after use
      stream.forEach {
        // consume the stream
      }
    }

    expect:
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
          resourceName "completions.create"
          childOf span(0)
          errored false
          spanType DDSpanTypes.LLMOBS
          tags {
            "$OpenAiDecorator.REQUEST_MODEL" "gpt-3.5-turbo-instruct"
            "$Tags.COMPONENT" "openai"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span(2) {
          operationName "okhttp.request"
          childOf span(1)
          errored false
          spanType "http"
        }
      }
    }
  }
}
