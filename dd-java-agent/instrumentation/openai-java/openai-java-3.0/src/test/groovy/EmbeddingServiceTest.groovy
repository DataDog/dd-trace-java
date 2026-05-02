import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import com.openai.core.http.HttpResponseFor
import com.openai.models.embeddings.CreateEmbeddingResponse
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.llmobs.LLMObs
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.openai_java.CommonTags

class EmbeddingServiceTest extends OpenAiTest {

  def "create embedding test"() {
    CreateEmbeddingResponse resp = runUnderTrace("parent") {
      openAiClient.embeddings().create(params)
    }

    expect:
    resp != null
    and:
    assertEmbeddingTrace()

    where:
    params << [embeddingCreateParams(false), embeddingCreateParams(true)]
  }

  def "create embedding test withRawResponse"() {
    HttpResponseFor<CreateEmbeddingResponse> resp = runUnderTrace("parent") {
      openAiClient.embeddings().withRawResponse().create(params)
    }

    expect:
    resp != null
    and:
    resp.parse().valid // force response parsing, so it sets all the tags
    and:
    assertEmbeddingTrace()

    where:
    params << [embeddingCreateParams(false), embeddingCreateParams(true)]
  }

  private void assertEmbeddingTrace() {
    List<LLMObs.Document> inputTagsOut = []
    Map<String, Object> metadataOut = [:]

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
            "_ml_obs_tag.span.kind" "embedding"
            "_ml_obs_tag.model_provider" "openai"
            "_ml_obs_tag.model_name" "text-embedding-ada-002-v2"
            "_ml_obs_tag.input" List<LLMObs.Document>
            def inputTags = tag("_ml_obs_tag.input")
            if (inputTags != null) {
              inputTagsOut.addAll(inputTags)
            }
            "_ml_obs_tag.metadata" Map
            def metadata = tag("_ml_obs_tag.metadata")
            if (metadata != null) {
              metadataOut.putAll(metadata)
            }
            "_ml_obs_tag.output" "[1 embedding(s) returned with size 1536]"
            "_ml_obs_tag.parent_id" "undefined"
            "_ml_obs_tag.ml_app" String
            "_ml_obs_tag.service" String
            "$CommonTags.DDTRACE_VERSION" String
            "$CommonTags.SOURCE" "integration"
            "$CommonTags.INTEGRATION" "openai"
            "$CommonTags.ERROR" 0
            "_ml_obs_metric.input_tokens" Long
            "_ml_obs_metric.total_tokens" Long
            "openai.request.method" "POST"
            "openai.request.endpoint" "/v1/embeddings"
            "openai.api_base" openAiBaseApi
            "openai.organization.ratelimit.requests.limit" 10000
            "openai.organization.ratelimit.requests.remaining" Integer
            "openai.organization.ratelimit.tokens.limit" 10000000
            "openai.organization.ratelimit.tokens.remaining" Integer
            "$CommonTags.OPENAI_REQUEST_MODEL" "text-embedding-ada-002"
            "$CommonTags.OPENAI_RESPONSE_MODEL" "text-embedding-ada-002-v2"
            "$CommonTags.OPENAI_ORGANIZATION" "datadog-staging"
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

    assert inputTagsOut.size() == 1
    assert inputTagsOut[0].text == "hello world"
    assert metadataOut == [encoding_format: "base64"]
  }
}
