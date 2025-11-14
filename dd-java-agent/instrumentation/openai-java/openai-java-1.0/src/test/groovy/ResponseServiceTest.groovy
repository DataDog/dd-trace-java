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
    assertResponseTrace(false)
  }

  def "create response test withRawResponse"() {
    HttpResponseFor<Response> resp = runUnderTrace("parent") {
      openAiClient.responses().withRawResponse().create(responseCreateParams())
    }

    expect:
    resp.statusCode() == 200
    resp.parse().valid // force response parsing, so it sets all the tags
    and:
    assertResponseTrace(false)
  }

  def "create streaming response test"() {
    runnableUnderTrace("parent") {
      StreamResponse<ResponseStreamEvent> streamResponse = openAiClient.responses().createStreaming(responseCreateParams())
      try (Stream stream = streamResponse.stream()) {
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertResponseTrace(true)
  }

  def "create streaming response test withRawResponse"() {
    runnableUnderTrace("parent") {
      HttpResponseFor<StreamResponse<ResponseStreamEvent>> streamResponse = openAiClient.responses().withRawResponse().createStreaming(responseCreateParams())
      try (Stream stream = streamResponse.parse().stream()) { // close the stream after use
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    assertResponseTrace(true)
  }

  def "create async response test"() {
    CompletableFuture<Response> responseFuture = runUnderTrace("parent") {
      openAiClient.async().responses().create(responseCreateParams())
    }

    responseFuture.get()

    expect:
    assertResponseTrace(false)
  }

  def "create async response test withRawResponse"() {
    CompletableFuture<HttpResponseFor<Response>> responseFuture = runUnderTrace("parent") {
      openAiClient.async().responses().withRawResponse().create(responseCreateParams())
    }

    def resp = responseFuture.get()
    resp.parse().valid // force response parsing, so it sets all the tags

    expect:
    assertResponseTrace(false)
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
    assertResponseTrace(true)
  }

  def "create streaming async response test withRawResponse"() {
    CompletableFuture<HttpResponseFor<StreamResponse<ResponseStreamEvent>>> future = runUnderTrace("parent") {
      openAiClient.async().responses().withRawResponse().createStreaming(responseCreateParams())
    }
    HttpResponseFor<StreamResponse<ResponseStreamEvent>> resp = future.get()
    try (Stream stream = resp.parse().stream()) { // close the stream after use
      stream.forEach {
        // consume the stream
      }
    }
    expect:
    resp.statusCode() == 200
    assertResponseTrace(true)
  }

  private void assertResponseTrace(boolean isStreaming) {
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
            "openai.request.method" "POST"
            "openai.request.endpoint" "v1/responses"
            "openai.api_base" openAiBaseApi
            if (!isStreaming) {
              // TODO no limit headers when streaming
              "openai.organization.ratelimit.requests.limit" 10000
              "openai.organization.ratelimit.requests.remaining" Integer
              "openai.organization.ratelimit.tokens.limit" 50000000
              "openai.organization.ratelimit.tokens.remaining" Integer
              // TODO no response model
              "$OpenAiDecorator.RESPONSE_MODEL" "gpt-3.5-turbo-0125"
            }
            "$OpenAiDecorator.OPENAI_ORGANIZATION_NAME" "datadog-staging"
            "$OpenAiDecorator.REQUEST_MODEL" "gpt-3.5-turbo"
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
