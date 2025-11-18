import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import com.openai.core.http.HttpResponseFor
import com.openai.models.embeddings.CreateEmbeddingResponse
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.openai_java.OpenAiDecorator

class EmbeddingServiceTest extends OpenAiTest {

  def "create embedding test"() {
    CreateEmbeddingResponse resp = runUnderTrace("parent") {
      openAiClient.embeddings().create(embeddingCreateParams())
    }

    expect:
    resp != null
    and:
    assertEmbeddingTrace()
  }

  def "create embedding test withRawResponse"() {
    HttpResponseFor<CreateEmbeddingResponse> resp = runUnderTrace("parent") {
      openAiClient.embeddings().withRawResponse().create(embeddingCreateParams())
    }

    expect:
    resp != null
    and:
    resp.parse().valid // force response parsing, so it sets all the tags
    and:
    assertEmbeddingTrace()
  }

  private void assertEmbeddingTrace() {
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
          resourceName "createEmbedding"
          childOf span(0)
          errored false
          spanType DDSpanTypes.LLMOBS
          tags {
            "_ml_obs_tag.parent_id" "undefined"
            "openai.request.method" "POST"
            "openai.request.endpoint" "v1/embeddings"
            "openai.api_base" openAiBaseApi
            "openai.organization.ratelimit.requests.limit" 10000
            "openai.organization.ratelimit.requests.remaining" Integer
            "openai.organization.ratelimit.tokens.limit" 10000000
            "openai.organization.ratelimit.tokens.remaining" Integer
            "$OpenAiDecorator.REQUEST_MODEL" "text-embedding-ada-002"
            "$OpenAiDecorator.RESPONSE_MODEL" "text-embedding-ada-002-v2"
            "$OpenAiDecorator.OPENAI_ORGANIZATION_NAME" "datadog-staging"
            "$Tags.COMPONENT" "openai"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span(2) {
          operationName "okhttp.request"
          resourceName "POST /v1/embeddings"
          childOf span(1)
          errored false
          spanType "http"
        }
      }
    }
  }
}
