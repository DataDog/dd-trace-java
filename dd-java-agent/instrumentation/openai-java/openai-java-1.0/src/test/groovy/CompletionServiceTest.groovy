import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import com.openai.core.http.AsyncStreamResponse
import com.openai.core.http.HttpResponseFor
import com.openai.core.http.StreamResponse
import com.openai.models.completions.Completion
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import spock.lang.Ignore

class CompletionServiceTest extends OpenAiTest {

  def "single request completion test"() {
    runnableUnderTrace("parent") {
      openAiClient.completions().create(completionCreateParams())
    }

    expect:
    assertCompletionTrace()
  }

  def "single request completion test with withRawResponse"() {
    runnableUnderTrace("parent") {
      openAiClient.withRawResponse().completions().create(completionCreateParams())
    }

    expect:
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

  def "single async request completion test"() {
    CompletableFuture<Completion> completionFuture = runUnderTrace("parent", true) {
      openAiClient.async().completions().create(completionCreateParams())
    }

    completionFuture.get()

    expect:
    assertCompletionTrace()
  }

  def "single async request completion test with withRawResponse"() {
    CompletableFuture<HttpResponseFor<Completion>> completionFuture = runUnderTrace("parent", true) {
      openAiClient.async().completions().withRawResponse().create(completionCreateParams())
    }

    completionFuture.get()

    expect:
    assertCompletionTrace()
  }

  @Ignore
  def "streamed async request completion test"() {
    AsyncStreamResponse<Completion> response = runnableUnderTrace("parent") {
      openAiClient.async().completions().createStreaming(completionCreateParams())
    }

    response.onCompleteFuture().get()

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
          spanType "llm"
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
