package datadog.trace.instrumentation.feign

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import feign.Client
import feign.Feign
import feign.Request
import feign.RequestLine
import feign.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.nio.charset.StandardCharsets

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class FeignClientTest extends AgentTestRunner {

  @Shared
  @AutoCleanup
  MockWebServer server = new MockWebServer()

  @Shared
  int port

  @Shared
  TestApi api

  interface TestApi {
    @RequestLine("GET /test")
    String get()
  }

  def setupSpec() {
    port = PortUtils.randomOpenPort()
    server.start(port)

    api = Feign.builder()
      .client(new Client.Default(null, null))
      .target(TestApi, "http://localhost:${port}")
  }

  def "test feign sync client"() {
    setup:
    server.enqueue(new MockResponse().setResponseCode(200).setBody("Success"))

    when:
    runUnderTrace("parent") {
      def result = api.get()
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

  def "test feign sync client with error"() {
    setup:
    server.enqueue(new MockResponse().setResponseCode(500).setBody("Error"))

    when:
    runUnderTrace("parent") {
      try {
        api.get()
      } catch (Exception e) {
        // Expected for 500 response
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

  def "test feign sync client without parent"() {
    setup:
    server.enqueue(new MockResponse().setResponseCode(200).setBody("Success"))

    when:
    def result = api.get()

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
