import com.twitter.finagle.Http
import com.twitter.finagle.ListeningServer
import com.twitter.finagle.Service
import com.twitter.finagle.http.Request
import com.twitter.finagle.http.Response
import com.twitter.util.Await
import com.twitter.util.Duration
import com.twitter.util.Future
import com.twitter.util.FutureTransformer
import datadog.opentracing.DDSpan
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.Trace
import datadog.trace.instrumentation.api.Tags
import spock.lang.Shared

class FinagleServiceInstrumentationTest extends AgentTestRunner {
  private static final Duration TIMEOUT = Duration.fromSeconds(5)
  @Shared
  int port = PortUtils.randomOpenPort()

  @Shared
  ListeningServer server

  Service<Request, Response> client

  def setupSpec() {
    server = Http.server().serve("localhost:" + port, new ReverseEchoService())
  }

  def cleanupSpec() {
    if (server != null) {
      Await.ready(server.close(), TIMEOUT)
    }
  }

  def setup() {
    client = Http.client().newService("localhost:" + port)
  }

  def "simple request-response [#content]"() {
    given:
    def request = Request.apply("/")
    request.setContentString(content)

    when:
    Future<Response> responseFuture = client.apply(request)
    Response response = Await.result(responseFuture, TIMEOUT)

    then:
    response.getContentString() == content.reverse()
    assertTraces(2) {
      trace(0, 2) {
        nettyServerSpan(it, 0, trace(1).get(0))
        finagleSpan(it, 1, span(0))
      }

      trace(1, 1) {
        nettyClientSpan(it, 0)
      }
    }

    where:
    content = "test string"
  }

  def "transform callback in response"() {
    given:
    def request = Request.apply("/")
    request.setContentString(content)
    def transformer = new Transformer()

    when:
    Future<Response> responseFuture = client.apply(request)
    Future<String> stringFuture = responseFuture.transformedBy(transformer)
    String value = Await.result(stringFuture, TIMEOUT)

    then:
    value == content.reverse() + "something"
    assertTraces(2) {
      trace(0, 2) {
        nettyServerSpan(it, 0, trace(1).get(0))
        finagleSpan(it, 1, span(0))
      }

      trace(1, 2) {
        nettyClientSpan(it, 0)
        span(1) {
          childOf((DDSpan) span(0))
          serviceName "unnamed-java-app"
          operationName "trace.annotation"
          resourceName "AnnotatedClass.doSomething"
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }

    where:
    content = "test string"
  }

  // parent span must be cast otherwise it breaks debugging classloading (junit loads it early)
  void finagleSpan(TraceAssert trace, int index, Object parentSpan = null) {
    trace.span(index) {
      serviceName "unnamed-java-app"
      operationName "finagle.service"
      resourceName "ReverseEchoService.apply"
      spanType DDSpanTypes.HTTP_SERVER

      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }

      tags {
        "$Tags.COMPONENT" "finagle"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER

        defaultTags()
      }
    }
  }


  void nettyClientSpan(TraceAssert trace, int index, Object parentSpan = null) {
    trace.span(index) {
      serviceName "unnamed-java-app"
      operationName "netty.client.request"
      resourceName "GET /"
      spanType DDSpanTypes.HTTP_CLIENT
      errored false

      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }

      tags {
        "$Tags.COMPONENT" "netty-client"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.PEER_PORT" Number
        "$Tags.HTTP_URL" "/"
        "$Tags.HTTP_METHOD" "GET"
        "$Tags.HTTP_STATUS" 200

        defaultTags()
      }
    }
  }

  void nettyServerSpan(TraceAssert trace, int index, Object parentSpan = null) {
    trace.span(index) {
      serviceName "unnamed-java-app"
      operationName "netty.request"
      resourceName "GET /"
      spanType DDSpanTypes.HTTP_SERVER

      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }

      tags {
        "$Tags.COMPONENT" "netty"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOSTNAME" { it == "localhost" || it == "127.0.0.1" }
        "$Tags.PEER_PORT" Integer
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.HTTP_URL" "/"
        "$Tags.HTTP_METHOD" "GET"
        "$Tags.HTTP_STATUS" 200

        defaultTags(true)
      }
    }
  }


  class Transformer extends FutureTransformer<Response, String> {
    @Override
    Future<String> flatMap(Response value) {
      return Future.value(doSomething(value.getContentString()))
    }

    @Override
    String map(Response value) {
      return doSomething(value.getContentString())
    }

    @Trace
    String doSomething(String original) {
      return original + "something"
    }
  }

  static class ReverseEchoService extends Service<Request, Response> {
    @Override
    Future<Response> apply(Request request) {
      Response response = Response.apply()
      response.setContentString(request.getContentString().reverse())
      return Future.value(response)
    }
  }
}
