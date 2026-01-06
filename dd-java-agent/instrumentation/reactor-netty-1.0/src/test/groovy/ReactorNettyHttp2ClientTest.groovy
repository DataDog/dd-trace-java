import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import reactor.netty.DisposableServer
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClient
import reactor.netty.http.server.HttpServer
import spock.lang.IgnoreIf
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@IgnoreIf(reason = "TLS issues with OpenJ9", value = {
  JavaVirtualMachine.isJ9()
})
class ReactorNettyHttp2ClientTest extends InstrumentationSpecification {
  @Shared
  DisposableServer server = HttpServer.create()
  .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
  .host("localhost")
  .port(0)
  .handle { req, res -> res.status(200).send() }
  .bindNow()

  @Override
  boolean useStrictTraceWrites() {
    false
  }

  @Override
  def cleanupSpec() {
    server?.disposeNow()
  }

  def "test http2 client/server propagation"() {
    setup:
    HttpClient httpClient = HttpClient.create()
      .disableRetry(true)
      .protocol(protocols as HttpProtocol[])
    when:
    def status = runUnderTrace("parent", {
      httpClient.baseUrl("http://localhost:${server.port()}")
        .get()
        .uri("/firstUrl") //we expect the h2 upgrade only here once
        .response()
        .block()
      httpClient.baseUrl("http://localhost:${server.port()}")
        .get()
        .uri("/")
        .response()
        .block().status().code()
    })
    then:
    status == 200
    assertTraces(3) {
      def firstClientCall
      def secondClientCall
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        span {
          firstClientCall = it.span
          operationName "netty.client.request"
          resourceName "GET /firstUrl"
          spanType DDSpanTypes.HTTP_CLIENT
          childOfPrevious()
          tags {
            "$Tags.COMPONENT" "netty-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
            "$Tags.PEER_PORT" { it == null || it == server.port() }
            "$Tags.HTTP_URL" "http://localhost:${server.port()}/firstUrl"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            defaultTags()
          }
        }
        span {
          secondClientCall = it.span
          operationName "netty.client.request"
          resourceName "GET /"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "netty-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
            "$Tags.PEER_PORT" { it == null || it == server.port() }
            "$Tags.HTTP_URL" "http://localhost:${server.port()}/"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          operationName "netty.request"
          resourceName "GET /firstUrl"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          childOf firstClientCall
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_PORT" Integer
            "$Tags.PEER_HOST_IPV4" { String }
            "$Tags.HTTP_CLIENT_IP" { String }

            "$Tags.HTTP_HOSTNAME" { String }
            "$Tags.HTTP_URL" "http://localhost:${server.port()}/firstUrl"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_USER_AGENT" String
            defaultTags(true)
          }
        }
        trace(1) {
          span {
            operationName "netty.request"
            resourceName "GET /"
            spanType DDSpanTypes.HTTP_SERVER
            errored false
            childOf secondClientCall
            tags {
              "$Tags.COMPONENT" "netty"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
              "$Tags.PEER_PORT" Integer
              "$Tags.PEER_HOST_IPV4" { String }
              "$Tags.HTTP_CLIENT_IP" { String }

              "$Tags.HTTP_HOSTNAME" { String }
              "$Tags.HTTP_URL" "http://localhost:${server.port()}/"
              "$Tags.HTTP_METHOD" "GET"
              "$Tags.HTTP_STATUS" 200
              "$Tags.HTTP_USER_AGENT" String
              defaultTags(true)
            }
          }
        }
      }
    }
    where:
    protocols                               | _
    [HttpProtocol.HTTP11, HttpProtocol.H2C] | _ // http2 upgrade through 1.1
    [HttpProtocol.H2C]                      | _ // http2 prior knowledge
  }
}
