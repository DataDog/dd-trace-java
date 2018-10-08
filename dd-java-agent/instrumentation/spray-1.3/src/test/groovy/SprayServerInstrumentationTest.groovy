import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import io.opentracing.tag.Tags
import okhttp3.Request
import spock.lang.Shared

import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces

class SprayServerInstrumentationTest extends AgentTestRunner {

  def log = org.slf4j.LoggerFactory.getLogger(SprayServerInstrumentationTest.class);

  @Shared
  int port

  @Shared
  def client = OkHttpUtils.client()

  def setupSpec() {
    SprayHttpTestWebServer.start()
    port = SprayHttpTestWebServer.port()
  }

  def cleanupSpec() {
    SprayHttpTestWebServer.stop()
  }

  def "200 request trace"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/test")
      .header("x-datadog-trace-id", "123")
      .header("x-datadog-parent-id", "456")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().string() == "Hello unit test."
    response.code() == 200

    assertTraces(TEST_WRITER, 1) {
      trace(0, 2) {
        span(0) {
          traceId "123"
          parentId "456"
          serviceName "unnamed-java-app"
          operationName "spray-http.request"
          resourceName "GET /test"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          tags {
            defaultTags()
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.HTTP_URL.key" "http://localhost:$port/test"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.COMPONENT.key" "spray-http-server"
          }
        }
        span(1) {
          childOf span(0)
          assert span(1).operationName.endsWith('.tracedMethod')
        }
      }
    }
  }

  def "exceptions trace for #endpoint"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/$endpoint")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == 500

    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "spray-http.request"
          resourceName "GET /$endpoint"
          spanType DDSpanTypes.HTTP_SERVER
          errored true
          tags {
            defaultTags()
            "$Tags.HTTP_STATUS.key" 500
            "$Tags.HTTP_URL.key" "http://localhost:$port/$endpoint"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.COMPONENT.key" "spray-http-server"
            errorTags RuntimeException, errorMessage
          }
        }
      }
    }

    where:
    endpoint         | errorMessage
    "throw-handler"  | "Oh no handler"
  }

  def "5xx trace"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/server-error")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == 500

    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "spray-http.request"
          resourceName "GET /server-error"
          spanType DDSpanTypes.HTTP_SERVER
          errored true
          tags {
            defaultTags()
            "$Tags.HTTP_STATUS.key" 500
            "$Tags.HTTP_URL.key" "http://localhost:$port/server-error"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.COMPONENT.key" "spray-http-server"
            "$Tags.ERROR.key" true
          }
        }
      }
    }
  }

  def "4xx trace"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/not-found")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == 404

    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "spray-http.request"
          resourceName "404"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          tags {
            defaultTags()
            "$Tags.HTTP_STATUS.key" 404
            "$Tags.HTTP_URL.key" "http://localhost:$port/not-found"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.COMPONENT.key" "spray-http-server"
          }
        }
      }
    }
  }

  def "timeout trace"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/timeout")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    log.error("!!!! " + response.body().string())
    response.code() == 200

    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "spray-http.request"
          resourceName "GET /timeout"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          tags {
            defaultTags()
            "$Tags.HTTP_STATUS.key" 500
            "$Tags.HTTP_URL.key" "http://localhost:$port/timeout"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.COMPONENT.key" "spray-http-server"
          }
        }
      }
    }
  }
}
