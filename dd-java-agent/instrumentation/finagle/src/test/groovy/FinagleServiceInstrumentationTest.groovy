import com.twitter.finagle.Http
import com.twitter.finagle.ListeningServer
import com.twitter.finagle.Service
import com.twitter.finagle.http.Request
import com.twitter.finagle.http.Response
import com.twitter.util.Await
import com.twitter.util.Closable
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
import spock.lang.Ignore
import spock.lang.Shared

class FinagleServiceInstrumentationTest extends AgentTestRunner {
  private static final Duration TIMEOUT = Duration.fromSeconds(5)
  @Shared
  int reversingServerPort = PortUtils.randomOpenPort()

  @Shared
  int forwardingServerPort = PortUtils.randomOpenPort()

  @Shared
  ListeningServer reversingServer

  @Shared
  ListeningServer forwardingServer

  Service<Request, Response> reversingClient
  Service<Request, Response> forwardingClient

  def setupSpec() {
    reversingServer = Http.server().serve("localhost:" + reversingServerPort, new ReverseEchoService())
    forwardingServer = Http.server().serve("localhost:" + forwardingServerPort, new ForwardingService(reversingServerPort))
  }

  def cleanupSpec() {
    closeAndWait(reversingServer)
    closeAndWait(forwardingServer)
  }

  def setup() {
    reversingClient = Http.client().newService("localhost:" + reversingServerPort)
    forwardingClient = Http.client().newService("localhost:" + forwardingServerPort)
  }

  def cleanup() {
    closeAndWait(reversingClient)
    closeAndWait(forwardingClient)
  }

  def static closeAndWait(Closable closable) {
    if (closable != null) {
      Await.ready(closable.close(), TIMEOUT)
    }
  }

  def "simple request-response"() {
    given:
    def content = "test string"
    def request = Request.apply("/")
    request.setContentString(content)

    when:
    Future<Response> responseFuture = reversingClient.apply(request)
    Response response = Await.result(responseFuture, TIMEOUT)

    then:
    response.getContentString() == content.reverse()
    assertTraces(2) {
      // FIXME the ordering here is a bit inconsitent
      trace(0, 1) {
        nettyClientSpan(it, 0)
      }

      trace(1, 2) {
        nettyServerSpan(it, 0, trace(0).get(0))
        finagleSpan(it, 1, "ReverseEchoService.apply", span(0))
      }
    }
  }

  def "request to forwarding service"() {
    given:
    def content = "test string"
    def request = Request.apply("/")
    request.setContentString(content)

    when:
    Future<Response> responseFuture = forwardingClient.apply(request)
    Response response = Await.result(responseFuture, TIMEOUT)

    then:
    response.getContentString() == (content + "forwarded").reverse()
    assertTraces(3) {
      // FIXME the ordering here is a bit inconsitent.  This one is reversed compare to simple request
      trace(2, 1) {
        nettyClientSpan(it, 0)
      }

      trace(1, 3) {
        nettyServerSpan(it, 0, trace(2).get(0))
        // both spans are under the server span because of how the callback is done
        // TODO make sure this is correct
        finagleSpan(it, 1, "ForwardingService.apply", span(0))
        nettyClientSpan(it, 2, span(0))
      }

      trace(0, 2) {
        nettyServerSpan(it, 0, trace(1).get(2))
        finagleSpan(it, 1, "ReverseEchoService.apply", span(0))
      }
    }
  }

  // FIXME span ordering issues
  @Ignore
  def "transform callback in response"() {
    given:
    def content = "test string"
    def request = Request.apply("/")
    request.setContentString(content)
    def transformer = new Transformer()

    when:
    Future<String> stringFuture = reversingClient.apply(request).transformedBy(transformer)
    String value = Await.result(stringFuture, TIMEOUT)

    then:
    value == content.reverse() + "something"
    assertTraces(3) {
      // FIXME the ordering here is a bit inconsitent
      trace(0, 1) {
        nettyClientSpan(it, 0)
      }

      trace(1, 2) {
        nettyServerSpan(it, 0, trace(0).get(0))
        finagleSpan(it, 1, "ReverseEchoService.apply", span(0))
      }

      trace(2, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "trace.annotation"
          resourceName "Transformer.doSomething"
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  // parent span must be cast otherwise it breaks debugging classloading (junit loads it early)
  void finagleSpan(TraceAssert trace, int index, String name, Object parentSpan = null) {
    trace.span(index) {
      serviceName "unnamed-java-app"
      operationName "finagle.service"
      resourceName name
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

  static class ForwardingService extends Service<Request, Response> {
    final Service<Request, Response> internalClient

    ForwardingService(int port) {
      internalClient = Http.client().newService("localhost:" + port)
    }

    @Override
    Future<Response> apply(Request request) {
      Request forwardedRequest = Request.apply("/")
      forwardedRequest.setContentString(request.getContentString() + "forwarded")
      return internalClient.apply(forwardedRequest)
    }
  }
}
