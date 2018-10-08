import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.Request
import spock.lang.Shared

import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces
import static datadog.trace.instrumentation.spray.SprayHttpServerDecorator.SPRAY_HTTP_REQUEST
import static datadog.trace.instrumentation.spray.SprayHttpServerDecorator.SPRAY_HTTP_SERVER

class SprayServerInstrumentationTest extends AgentTestRunner {

  def log = org.slf4j.LoggerFactory.getLogger(SprayServerInstrumentationTest.class)

  String expectedServiceName() {
    CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
  }

  @Shared
  int port

  @Shared
  def client = OkHttpUtils.client()

  @Shared
  def server

  def setupSpec() {
    server = new SprayHttpTestWebServer()
    server.start()
    port = server.port
  }

  def cleanupSpec() {
    server.stop()
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
      trace(2, false) {
        span(0) {
          traceId new BigInteger("123")
          parentId new BigInteger("456")
          serviceName expectedServiceName()
          operationName "spray-http.request"
          resourceName "GET /test"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          tags {
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_URL" "http://localhost:$port/test"
            //"$Tags.PEER_PORT" Integer
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.COMPONENT" "$SPRAY_HTTP_SERVER"
            defaultTags(true)
          }
        }
        span(1) {
          childOf span(0)
          assert span(1).resourceName.toString().endsWith('.tracedMethod')
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
      trace(1) {
        span(0) {
          serviceName expectedServiceName()
          operationName "spray-http.request"
          resourceName "GET /$endpoint"
          spanType DDSpanTypes.HTTP_SERVER
          errored true
          tags {
            defaultTags()
            "$Tags.HTTP_STATUS" 500
            //"$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/$endpoint"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.COMPONENT" "$SPRAY_HTTP_SERVER"
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
      trace( 1) {
        span(0) {
          serviceName expectedServiceName()
          operationName "spray-http.request"
          resourceName "GET /server-error"
          spanType DDSpanTypes.HTTP_SERVER
          errored true
          tags {
            defaultTags()
            "$Tags.HTTP_STATUS" 500
            "$Tags.HTTP_URL" "http://localhost:$port/server-error"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.COMPONENT" "$SPRAY_HTTP_SERVER"
            //"$Tags.ERROR" true
          }
        }
      }
    }
  }

  def "4xx trace"() {
    setup:
    def url = "http://localhost:$port/not-found"
    def request = new Request.Builder()
      .url("http://localhost:$port/not-found")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == 404

    assertTraces(TEST_WRITER, 1) {
      trace(1) {
        span(0) {
          serviceName expectedServiceName()
          operationName "$SPRAY_HTTP_REQUEST"
          resourceName '404'
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          tags {
            defaultTags()
            "$Tags.HTTP_STATUS" 404
            //"$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/not-found"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.COMPONENT" "$SPRAY_HTTP_SERVER"
          }
        }
      }
    }
  }

  //  def "timeout trace"() {
  //    setup:
  //    def request = new Request.Builder()
  //      .url("http://localhost:$port/timeout")
  //      .get()
  //      .build()
  //    def response = client.newCall(request).execute()
  //
  //    expect:
  //    log.error("!!!! " + response.body().string())
  //    response.code() == 200
  //
  //    assertTraces(TEST_WRITER, 1) {
  //      trace(1) {
  //        span(0) {
  //          serviceName expectedServiceName()
  //          operationName "spray-http.request"
  //          resourceName "GET /timeout"
  //          spanType DDSpanTypes.HTTP_SERVER
  //          errored false
  //          tags {
  //            defaultTags()
  //            "$Tags.HTTP_STATUS" 500
  //            "$Tags.HTTP_URL" "http://localhost:$port/timeout"
  //            "$Tags.HTTP_METHOD" "GET"
  //            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
  //            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
  //            "$Tags.COMPONENT" "$SPRAY_HTTP_SERVER"
  //          }
  //        }
  //      }
  //    }
  //  }
}
