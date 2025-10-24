import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

import com.openai.models.completions.Completion
import com.openai.models.completions.CompletionCreateParams

class CompletionServiceTest extends OpenAiTest {

  def "test"() {
    CompletionCreateParams createParams = CompletionCreateParams.builder()
    .model(CompletionCreateParams.Model.GPT_3_5_TURBO_INSTRUCT)
    .prompt("Tell me a story about building the best SDK!")
    .build()

    Completion completion = runnableUnderTrace("parent") {
      openAiClient.completions().create(createParams)
    }

    expect:
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
          spanType "http"
        }
        span(2) {
          operationName "okhttp.request"
          // resourceName "POST /v1/completions"
          childOf span(1)
          errored false
          spanType "http"
        }
      }
    }
  }
}
