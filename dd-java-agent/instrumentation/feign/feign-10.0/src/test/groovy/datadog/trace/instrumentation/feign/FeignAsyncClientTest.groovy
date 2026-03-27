package datadog.trace.instrumentation.feign

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import feign.AsyncClient
import feign.AsyncFeign
import feign.RequestLine
import feign.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class FeignAsyncClientTest extends AgentTestRunner {

  @Shared
  @AutoCleanup
  MockWebServer server = new MockWebServer()

  @Shared
  int port

  @Shared
  TestAsyncApi api

  interface TestAsyncApi {
    @RequestLine("GET /test")
    CompletableFuture<Response> get()
  }

  def setupSpec() {
    port = PortUtils.randomOpenPort()
    server.start(port)

    api = AsyncFeign.builder()
      .target(TestAsyncApi, "http://localhost:${port}")
  }

  def "test feign async client"() {
    setup:
    server.enqueue(new MockResponse().setResponseCode(200).setBody("Success"))

    when:
    runUnderTrace("parent") {
      def future = api.get()
      future.get(10, TimeUnit.SECONDS)
    }

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName "http.request"
          resourceName "GET /test"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "feign-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${port}/test"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" port
            defaultTags()
          }
        }
      }
    }
  }

  def "test feign async client with error"() {
    setup:
    server.enqueue(new MockResponse().setResponseCode(500).setBody("Error"))

    when:
    runUnderTrace("parent") {
      def future = api.get()
      try {
        future.get(10, TimeUnit.SECONDS)
      } catch (Exception e) {
        // Expected for async errors
      }
    }

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName "http.request"
          resourceName "GET /test"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "feign-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${port}/test"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 500
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" port
            defaultTags()
          }
        }
      }
    }
  }

  def "test feign async client without parent"() {
    setup:
    server.enqueue(new MockResponse().setResponseCode(200).setBody("Success"))

    when:
    def future = api.get()
    future.get(10, TimeUnit.SECONDS)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "http.request"
          resourceName "GET /test"
          spanType DDSpanTypes.HTTP_CLIENT
          parent()
          tags {
            "$Tags.COMPONENT" "feign-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${port}/test"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" port
            defaultTags()
          }
        }
      }
    }
  }
}
