import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.OkHttpUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.instrumentation.netty41.server.SpringWebFluxTestApplication
import io.opentracing.tag.Tags
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Shared
import spock.lang.Unroll

import static datadog.trace.agent.test.ListWriterAssert.assertTraces

class SpringWebfluxTest extends AgentTestRunner {

  static {
    System.setProperty("dd.integration.netty.enabled", "true")
  }

  static final okhttp3.MediaType PLAIN_TYPE = okhttp3.MediaType.parse("text/plain; charset=utf-8")

  @Shared
  SpringApplication app

  @Shared
  ConfigurableApplicationContext context

  @Shared
  String port

  OkHttpClient client = OkHttpUtils.client()

  def setupSpec() {
    app = new SpringApplication(SpringWebFluxTestApplication)
    app.setDefaultProperties([
      "server.port" : 0
    ])
    app.setWebApplicationType(WebApplicationType.REACTIVE)
    context = app.run("")
    port = context.getEnvironment().getProperty("local.server.port")
    println("Server running at " + "http://localhost:$port")
  }

  @Unroll
  def "Basic GET test #testName"() {
    setup:
    String url = "http://localhost:$port/greet$urlSuffix"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code == 200
    response.body().string() == expectedResponseBody
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          resourceName "GET /greet$urlSuffix"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" "localhost"
            "$Tags.PEER_PORT.key" Integer
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.HTTP_URL.key" url
            defaultTags()
          }
        }
      }
    }

    where:
    testName | urlSuffix | expectedResponseBody
    "without paramaters" | "" | SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE
    "with parameter" | "/WORLD" | SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE + " WORLD"
  }

  def "404 GET test"() {
    setup:
    String url = "http://localhost:$port/notfoundgreet"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code == 404
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          resourceName "404"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" "localhost"
            "$Tags.PEER_PORT.key" Integer
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 404
            "$Tags.HTTP_URL.key" url
            defaultTags()
          }
        }
      }
    }
  }

  def "2 params GET test"() {
    setup:
    String url = "http://localhost:$port/greet/a/a"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code == 200
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          resourceName "GET /greet/a/a"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" "localhost"
            "$Tags.PEER_PORT.key" Integer
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.HTTP_URL.key" url
            defaultTags()
          }
        }
      }
    }
  }

  def "Basic POST test"() {
    setup:
    String echoString = "TEST"
    String url = "http://localhost:$port/echo"
    RequestBody body = RequestBody.create(PLAIN_TYPE, echoString)
    def request = new Request.Builder().url(url).post(body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 202
    response.body().string() == echoString
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          resourceName "POST /echo"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" "localhost"
            "$Tags.PEER_PORT.key" Integer
            "$Tags.HTTP_METHOD.key" "POST"
            "$Tags.HTTP_STATUS.key" 202
            "$Tags.HTTP_URL.key" url
            defaultTags()
          }
        }
      }
    }
  }

  def "POST to bad endpoint"() {
    setup:
    String echoString = "TEST"
    RequestBody body = RequestBody.create(PLAIN_TYPE, echoString)
    String url = "http://localhost:$port/fail-echo"
    def request = new Request.Builder().url(url).post(body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 500
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          resourceName "POST /fail-echo"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          errored true
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" "localhost"
            "$Tags.PEER_PORT.key" Integer
            "$Tags.HTTP_METHOD.key" "POST"
            "$Tags.HTTP_STATUS.key" 500
            "$Tags.HTTP_URL.key" url
            "error" true
            // errorTags(NullPointerException, String)
            // ideally this should be here with the stacktrace, but for now this doesn't seem
            // like a low hanging fruit/requires further instrumentation at spring webflux level
            defaultTags()
          }
        }
      }
    }
  }

  def "Redirect test"() {
    setup:
    String url = "http://localhost:$port/double-greet-redirect"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code == 200
    assertTraces(TEST_WRITER, 2) {
      trace(0, 1) {
        span(0) {
          resourceName "GET /double-greet-redirect"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" "localhost"
            "$Tags.PEER_PORT.key" Integer
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 307
            "$Tags.HTTP_URL.key" url
            defaultTags()
          }
        }
      }
      trace(1, 1) {
        span(0) {
          resourceName "GET /double-greet"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" "localhost"
            "$Tags.PEER_PORT.key" Integer
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.HTTP_URL.key" "http://localhost:$port/double-greet"
            defaultTags()
          }
        }
      }
    }
  }

  @Unroll
  def "Flux x#count GET test"() {
    setup:
    String[] expectedJsonStringArr = SpringWebFluxTestApplication.GreetingHandler.createGreetJsonStringArr(count)
    String expectedResponseBodyStr = ""
    for (int i = 0; i < count; ++i) {
      expectedResponseBodyStr += expectedJsonStringArr[i]
    }
    String url = "http://localhost:$port/greet-counter/$count"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    expectedResponseBodyStr == response.body().string()
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT.key" "netty"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_SERVER
            "$DDTags.SPAN_TYPE" DDSpanTypes.HTTP_SERVER
            "$Tags.PEER_HOSTNAME.key" "localhost"
            "$Tags.PEER_PORT.key" Integer
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.HTTP_STATUS.key" 200
            "$Tags.HTTP_URL.key" url
            defaultTags()
          }
        }
      }
    }

    where:
    count | _
    0 | _
    1 | _
    10 | _

  }
}
