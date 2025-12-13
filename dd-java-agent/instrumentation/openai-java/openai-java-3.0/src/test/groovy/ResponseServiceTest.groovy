import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

import com.openai.core.http.AsyncStreamResponse
import com.openai.core.http.HttpResponseFor
import com.openai.core.http.StreamResponse
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseStreamEvent
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.openai_java.OpenAiDecorator
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

class ResponseServiceTest extends OpenAiTest {

  def "create response test"() {
    Response resp = runUnderTrace("parent") {
      openAiClient.responses().create(responseCreateParams())
    }

    expect:
    resp != null
    and:
    assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null)
  }

  def "create response test withRawResponse"() {
    HttpResponseFor<Response> resp = runUnderTrace("parent") {
      openAiClient.responses().withRawResponse().create(responseCreateParams())
    }

    expect:
    resp.statusCode() == 200
    resp.parse().valid // force response parsing, so it sets all the tags
    and:
    assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null)
  }

  def "create streaming response test (#scenario)"() {
    runnableUnderTrace("parent") {
      StreamResponse<ResponseStreamEvent> streamResponse = openAiClient.responses().createStreaming(params)
      try (Stream stream = streamResponse.stream()) {
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null)

    where:
    scenario     | params
    "complete"   | responseCreateParams()
    "incomplete" | responseCreateParamsWithMaxOutputTokens()
  }

  def "create streaming response test (reasoning)"() {
    runnableUnderTrace("parent") {
      StreamResponse<ResponseStreamEvent> streamResponse = openAiClient.responses().createStreaming(responseCreateParams)
      try (Stream stream = streamResponse.stream()) {
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertResponseTrace(true, "o4-mini", "o4-mini-2025-04-16", [effort: "medium",  summary: "detailed"])

    where:
    responseCreateParams << [responseCreateParamsWithReasoning(false), responseCreateParamsWithReasoning(true)]
  }

  def "create streaming response test withRawResponse"() {
    runnableUnderTrace("parent") {
      HttpResponseFor<StreamResponse<ResponseStreamEvent>> streamResponse = openAiClient.responses().withRawResponse().createStreaming(responseCreateParams())
      try (Stream stream = streamResponse.parse().stream()) {
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null)
  }

  def "create async response test"() {
    CompletableFuture<Response> responseFuture = runUnderTrace("parent") {
      openAiClient.async().responses().create(responseCreateParams())
    }

    responseFuture.get()

    expect:
    assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null)
  }

  def "create async response test withRawResponse"() {
    CompletableFuture<HttpResponseFor<Response>> responseFuture = runUnderTrace("parent") {
      openAiClient.async().responses().withRawResponse().create(responseCreateParams())
    }

    def resp = responseFuture.get()
    resp.parse().valid // force response parsing, so it sets all the tags

    expect:
    assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null)
  }

  def "create streaming async response test"() {
    AsyncStreamResponse<ResponseStreamEvent> asyncResp = runUnderTrace("parent") {
      openAiClient.async().responses().createStreaming(responseCreateParams())
    }
    asyncResp.subscribe {
      // consume responses
    }
    asyncResp.onCompleteFuture().get()
    expect:
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null)
  }

  def "create streaming async response test withRawResponse"() {
    CompletableFuture<HttpResponseFor<StreamResponse<ResponseStreamEvent>>> future = runUnderTrace("parent") {
      openAiClient.async().responses().withRawResponse().createStreaming(responseCreateParams())
    }
    HttpResponseFor<StreamResponse<ResponseStreamEvent>> resp = future.get()
    try (Stream stream = resp.parse().stream()) {
      stream.forEach {
        // consume the stream
      }
    }
    expect:
    resp.statusCode() == 200
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null)
  }

  private void assertResponseTrace(boolean isStreaming, String reqModel, String respModel, Map reasoning) {
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
          resourceName "createResponse"
          childOf span(0)
          errored false
          spanType DDSpanTypes.LLMOBS
          tags {
            "_ml_obs_tag.span.kind" "llm"
            "_ml_obs_tag.model_provider" "openai"
            "_ml_obs_tag.model_name" String
            "_ml_obs_tag.metadata" Map
            "_ml_obs_tag.output" List // TODO capture to validate tool calls
            "_ml_obs_metric.input_tokens" Long
            "_ml_obs_metric.output_tokens" Long
            "_ml_obs_metric.total_tokens" Long
            "_ml_obs_metric.cache_read_input_tokens" Long
            "_ml_obs_tag.parent_id" "undefined"
            if (reasoning != null) {
              "_ml_obs_request.reasoning" reasoning
            }
            "openai.request.method" "POST"
            "openai.request.endpoint" "v1/responses"
            "openai.api_base" openAiBaseApi
            "$OpenAiDecorator.RESPONSE_MODEL" respModel
            if (!isStreaming) {
              "openai.organization.ratelimit.requests.limit" 10000
              "openai.organization.ratelimit.requests.remaining" Integer
              "openai.organization.ratelimit.tokens.limit" 50000000
              "openai.organization.ratelimit.tokens.remaining" Integer
            }
            "$OpenAiDecorator.OPENAI_ORGANIZATION_NAME" "datadog-staging"
            "$OpenAiDecorator.REQUEST_MODEL" reqModel
            "$Tags.COMPONENT" "openai"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span(2) {
          operationName "okhttp.request"
          resourceName "POST /v1/responses"
          childOf span(1)
          errored false
          spanType "http"
        }
      }
    }
  }
}
