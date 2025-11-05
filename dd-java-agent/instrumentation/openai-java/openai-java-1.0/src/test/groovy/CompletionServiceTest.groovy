import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

import com.openai.core.http.StreamResponse
import com.openai.models.completions.Completion
import com.openai.models.completions.CompletionCreateParams
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import spock.lang.Ignore

class CompletionServiceTest extends OpenAiTest {

  @Ignore
  def "single request completion test"() {
    CompletionCreateParams createParams = CompletionCreateParams.builder()
    .model(CompletionCreateParams.Model.GPT_3_5_TURBO_INSTRUCT)
    .prompt("Tell me a story about building the best SDK!")
    .build()

    runnableUnderTrace("parent") {
      openAiClient.completions().create(createParams)
    }

    expect:
    assertCompletionTrace()
  }

  @Ignore
  def "single request completion test with withRawResponse"() {
    CompletionCreateParams createParams = CompletionCreateParams.builder()
    .model(CompletionCreateParams.Model.GPT_3_5_TURBO_INSTRUCT)
    .prompt("Tell me a story about building the best SDK!")
    .build()

    runnableUnderTrace("parent") {
      openAiClient.withRawResponse().completions().create(createParams)
    }

    expect:
    assertCompletionTrace()
  }

  def "streamed request completion test"() {
    CompletionCreateParams createParams = CompletionCreateParams.builder()
        .model(CompletionCreateParams.Model.GPT_3_5_TURBO_INSTRUCT)
        .prompt("Tell me a story about building the best SDK!")
        .build()

    runnableUnderTrace("parent") {
      StreamResponse<Completion> streamCompletion = openAiClient.completions().createStreaming(createParams)
      try (Stream stream = streamCompletion.stream()) { // close the stream after use
        stream.forEach {
          // consume the stream
          System.err.println(">>> completion: " + it)
        }
      }
    }

    expect:
    assertCompletionTrace()
  }

  @Ignore
  def "single async request completion test"() {
    CompletionCreateParams createParams = CompletionCreateParams.builder()
        .model(CompletionCreateParams.Model.GPT_3_5_TURBO_INSTRUCT)
        .prompt("Tell me a story about building the best SDK!")
        .build()

    CompletableFuture<Completion> completionFuture = runnableUnderTrace("parent") {
      openAiClient.async().completions().create(createParams)
    }
    completionFuture.get()

    expect:
    assertCompletionTrace()
  }

  private void assertCompletionTrace() {
    assertTraces(1) {
      trace(3) {
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

  // TODO simplify to be less verbose testing all the combinations, sync/async, withRawResponse, single/streamed.

}
