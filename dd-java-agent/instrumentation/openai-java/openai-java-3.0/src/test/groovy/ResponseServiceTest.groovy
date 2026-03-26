import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.http.AsyncStreamResponse
import com.openai.core.http.HttpResponseFor
import com.openai.core.http.StreamResponse
import com.openai.credential.BearerTokenCredential
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseStreamEvent
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.llmobs.LLMObs
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.openai_java.CommonTags
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

class ResponseServiceTest extends OpenAiTest {

  def "create response test"() {
    Response resp = runUnderTrace("parent") {
      openAiClient.responses().create(params)
    }

    expect:
    resp != null
    and:
    Map<String, Object> metadata = [:]
    assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, null, metadata)
    and:
    metadata.stream == false

    where:
    params << [responseCreateParams(false), responseCreateParams(true)]
  }

  def "create response test withRawResponse"() {
    HttpResponseFor<Response> resp = runUnderTrace("parent") {
      openAiClient.responses().withRawResponse().create(params)
    }

    expect:
    resp.statusCode() == 200
    resp.parse().valid // force response parsing, so it sets all the tags
    and:
    Map<String, Object> metadata = [:]
    assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, null, metadata)
    and:
    metadata.stream == false

    where:
    params << [responseCreateParams(false), responseCreateParams(true)]
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
    Map<String, Object> metadata = [:]
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, null, metadata)
    and:
    metadata.stream == true

    where:
    scenario     | params
    "complete"   | responseCreateParams(false)
    "complete"   | responseCreateParams(true)
    "incomplete" | responseCreateParamsWithMaxOutputTokens(false)
    "incomplete" | responseCreateParamsWithMaxOutputTokens(true)
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
    Map<String, Object> metadata = [:]
    assertResponseTrace(true, "o4-mini", "o4-mini-2025-04-16", [effort: "medium",  summary: "detailed"], null, null, metadata)
    and:
    metadata.stream == true
    metadata.reasoning == [effort: "medium",  summary: "detailed"]

    where:
    responseCreateParams << [responseCreateParamsWithReasoning(false), responseCreateParamsWithReasoning(true)]
  }

  def "create streaming response test withRawResponse"() {
    runnableUnderTrace("parent") {
      HttpResponseFor<StreamResponse<ResponseStreamEvent>> streamResponse = openAiClient.responses().withRawResponse().createStreaming(params)
      try (Stream stream = streamResponse.parse().stream()) {
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    Map<String, Object> metadata = [:]
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, null, metadata)
    and:
    metadata.stream == true

    where:
    params << [responseCreateParams(false), responseCreateParams(true)]
  }

  def "create async response test"() {
    CompletableFuture<Response> responseFuture = runUnderTrace("parent") {
      openAiClient.async().responses().create(params)
    }

    responseFuture.get()

    expect:
    Map<String, Object> metadata = [:]
    assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, null, metadata)
    and:
    metadata.stream == false

    where:
    params << [responseCreateParams(false), responseCreateParams(true)]
  }

  def "create async response test withRawResponse"() {
    CompletableFuture<HttpResponseFor<Response>> responseFuture = runUnderTrace("parent") {
      openAiClient.async().responses().withRawResponse().create(params)
    }

    def resp = responseFuture.get()
    resp.parse().valid // force response parsing, so it sets all the tags

    expect:
    Map<String, Object> metadata = [:]
    assertResponseTrace(false, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, null, metadata)
    and:
    metadata.stream == false

    where:
    params << [responseCreateParams(false), responseCreateParams(true)]
  }

  def "create streaming async response test"() {
    AsyncStreamResponse<ResponseStreamEvent> asyncResp = runUnderTrace("parent") {
      openAiClient.async().responses().createStreaming(params)
    }
    asyncResp.subscribe {
      // consume responses
    }
    asyncResp.onCompleteFuture().get()
    expect:
    Map<String, Object> metadata = [:]
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, null, metadata)
    and:
    metadata.stream == true

    where:
    params << [responseCreateParams(false), responseCreateParams(true)]
  }

  def "create streaming async response test withRawResponse"() {
    CompletableFuture<HttpResponseFor<StreamResponse<ResponseStreamEvent>>> future = runUnderTrace("parent") {
      openAiClient.async().responses().withRawResponse().createStreaming(params)
    }
    HttpResponseFor<StreamResponse<ResponseStreamEvent>> resp = future.get()
    try (Stream stream = resp.parse().stream()) {
      stream.forEach {
        // consume the stream
      }
    }
    expect:
    resp.statusCode() == 200
    Map<String, Object> metadata = [:]
    assertResponseTrace(true, "gpt-3.5-turbo", "gpt-3.5-turbo-0125", null, null, null, metadata)
    and:
    metadata.stream == true

    where:
    params << [responseCreateParams(false), responseCreateParams(true)]
  }

  def "create streaming response with tool input test"() {
    runnableUnderTrace("parent") {
      StreamResponse<ResponseStreamEvent> streamResponse = openAiClient.responses().createStreaming(responseCreateParams)
      try (Stream stream = streamResponse.stream()) {
        stream.forEach {
          // consume the stream
        }
      }
    }

    expect:
    List<LLMObs.LLMMessage> inputTags = []
    Map<String, Object> metadata = [:]
    assertResponseTrace(true, "gpt-4.1", "gpt-4.1-2025-04-14", null, inputTags, null, metadata, false)
    and:
    metadata.stream == true
    inputTags.size() == 3
    inputTags[1].toolCalls.size() == 1
    inputTags[1].toolCalls[0].name == "get_weather"
    inputTags[1].toolCalls[0].type == "function_call"
    inputTags[1].toolCalls[0].arguments == [location: "San Francisco, CA"]
    inputTags[2].toolResults.size() == 1
    inputTags[2].toolResults[0].type == "function_call_output"
    !inputTags.isEmpty()
    inputTags[2].toolResults[0].result == '{"temperature": "72°F", "conditions": "sunny", "humidity": "65%"}'

    where:
    responseCreateParams << [responseCreateParamsWithToolInput(false), responseCreateParamsWithToolInput(true)]
  }

  def "create response with prompt tracking"() {
    Response resp = runUnderTrace("parent") {
      openAiClient.responses().create(params)
    }

    expect:
    resp != null
    and:
    Map<String, Object> metadata = [:]
    Map<String, Object> input = [:]
    assertResponseTrace(false, null, String, null, input, null, metadata, true)
    and:
    metadata.stream == false
    input.prompt instanceof Map
    input.messages instanceof List
    def prompt = input.prompt as Map<String, Object>
    prompt.chat_template instanceof List
    prompt.variables instanceof Map

    where:
    params << [responseCreateParamsWithPromptTracking(false), responseCreateParamsWithPromptTracking(true)]
  }

  def "create response with custom tool call"() {
    Response resp = runUnderTrace("parent") {
      openAiClient.responses().create(params)
    }

    expect:
    resp != null
    and:
    List<LLMObs.LLMMessage> outputTags = []
    Map<String, Object> metadata = [:]
    assertResponseTrace(false, "gpt-5", String, null, null, outputTags, metadata)
    and:
    !outputTags.isEmpty()

    where:
    params << [responseCreateParamsWithCustomToolCall(false), responseCreateParamsWithCustomToolCall(true)]
  }

  def "create response error sets model_name and placeholder output"() {
    setup:
    def errorBackend = TestHttpServer.httpServer {
      handlers {
        prefix("/v1/") {
          response.status(500).send('{"error":{"message":"Internal server error","type":"server_error"}}')
        }
      }
    }
    def errorClient = OpenAIOkHttpClient.builder()
    .baseUrl("${errorBackend.address.toURL()}/v1")
    .credential(BearerTokenCredential.create(""))
    .maxRetries(0)
    .build()

    when:
    runUnderTrace("parent") {
      try {
        errorClient.responses().create(responseCreateParams(false))
      } catch (Exception ignored) {}
    }

    then:
    List<LLMObs.LLMMessage> outputMessages = []
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
          errored true
          spanType DDSpanTypes.LLMOBS
          tags(false) {
            "_ml_obs_tag.model_name" "gpt-3.5-turbo"
            "_ml_obs_tag.output" List
            def out = tag("_ml_obs_tag.output")
            if (out instanceof List) {
              outputMessages.addAll(out)
            }
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
    and:
    outputMessages.size() == 1
    outputMessages[0].role == ""
    outputMessages[0].content == ""

    cleanup:
    errorBackend.close()
  }


  private void assertResponseTrace(
  boolean isStreaming,
  String reqModel,
  Object respModel,
  Map reasoning,
  Object inputTagsOut,
  List outputTagsOut,
  Map<String, Object> metadataOut,
  boolean expectPromptTag = false) {
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
            if (expectPromptTag) {
              "_ml_obs_request.prompt" Map
            }
            def metadata = tag("_ml_obs_tag.metadata")
            if (metadataOut != null && metadata != null) {
              metadataOut.putAll(metadata)
            }
            if (inputTagsOut instanceof Map) {
              "_ml_obs_tag.input" Map
            } else {
              "_ml_obs_tag.input" List
            }
            def inputTags = tag("_ml_obs_tag.input")
            if (inputTagsOut != null && inputTags != null) {
              if (inputTagsOut instanceof List) {
                inputTagsOut.addAll(inputTags as List)
              } else if (inputTagsOut instanceof Map) {
                inputTagsOut.putAll(inputTags as Map)
              }
            }
            "_ml_obs_tag.output" List
            def outputTags = tag("_ml_obs_tag.output")
            if (outputTagsOut != null && outputTags != null) {
              outputTagsOut.addAll(outputTags)
            }
            "_ml_obs_metric.input_tokens" Long
            "_ml_obs_metric.output_tokens" Long
            "_ml_obs_metric.total_tokens" Long
            "_ml_obs_metric.reasoning_output_tokens" Long
            "_ml_obs_metric.cache_read_input_tokens" Long
            "_ml_obs_tag.parent_id" "undefined"
            "_ml_obs_tag.ml_app" String
            "$CommonTags.INTEGRATION" "openai"
            "_ml_obs_tag.service" String
            "$CommonTags.DDTRACE_VERSION" String
            "$CommonTags.SOURCE" "integration"
            "$CommonTags.ERROR" 0
            if (reasoning != null) {
              "_ml_obs_request.reasoning" reasoning
            }
            "openai.request.method" "POST"
            "openai.request.endpoint" "/v1/responses"
            "openai.api_base" openAiBaseApi
            "$CommonTags.OPENAI_RESPONSE_MODEL" respModel
            if (!isStreaming) {
              "openai.organization.ratelimit.requests.limit" Integer
              "openai.organization.ratelimit.requests.remaining" Integer
              "openai.organization.ratelimit.tokens.limit" Integer
              "openai.organization.ratelimit.tokens.remaining" Integer
            }
            "$CommonTags.OPENAI_ORGANIZATION" "datadog-staging"
            if (reqModel != null) {
              "$CommonTags.OPENAI_REQUEST_MODEL" reqModel
            }
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
