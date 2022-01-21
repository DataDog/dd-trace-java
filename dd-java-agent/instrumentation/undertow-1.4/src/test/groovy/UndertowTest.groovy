import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.util.Headers
import io.undertow.util.StatusCodes

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class UndertowTest extends HttpServerTest<Undertow> {
  class UndertowServer implements HttpServer {
    def port = 0
    Undertow undertowServer;

    UndertowServer() {
      undertowServer = Undertow.builder()
        .addHttpListener(port, "localhost")
        .setHandler(Handlers.path()
          .addExactPath(SUCCESS.getRawPath()) { exchange ->
            controller(SUCCESS) {
              exchange.getResponseSender().send(SUCCESS.body)
            }
          }
          .addExactPath(FORWARDED.getRawPath()) { exchange ->
            controller(FORWARDED) {
              exchange.getResponseSender().send(exchange.getRequestHeaders().get("x-forwarded-for", 0))
            }
          }
          .addExactPath(QUERY_ENCODED_BOTH.getPath()) { exchange ->
            controller(QUERY_ENCODED_BOTH) {
              exchange.getResponseSender().send("some=" + exchange.getQueryParameters().get("some").peek())
            }
          }
          .addExactPath(QUERY_ENCODED_QUERY.getRawPath()) { exchange ->
            controller(QUERY_ENCODED_QUERY) {
              exchange.getResponseSender().send("some=" + exchange.getQueryParameters().get("some").peek())
            }
          }
          .addExactPath(QUERY_PARAM.getRawPath()) { exchange ->
            controller(QUERY_PARAM) {
              exchange.getResponseSender().send(exchange.getQueryString())
            }
          }
          .addExactPath(REDIRECT.getRawPath()) { exchange ->
            controller(REDIRECT) {
              exchange.setStatusCode(StatusCodes.FOUND)
              exchange.getResponseHeaders().put(Headers.LOCATION, REDIRECT.body)
              exchange.endExchange()
            }
          }
          .addExactPath(ERROR.getRawPath()) { exchange ->
            controller(ERROR) {
              exchange.setStatusCode(ERROR.status)
              exchange.getResponseSender().send(ERROR.body)
            }
          }
          .addExactPath(EXCEPTION.getRawPath()) { exchange ->
            controller(EXCEPTION) {
              throw new Exception(EXCEPTION.body)
            }
          }

        ).build();
    }

    @Override
    void start() {
      undertowServer.start();
      InetSocketAddress addr = (InetSocketAddress) undertowServer.getListenerInfo().get(0).getAddress();
      port = addr.getPort();
    }

    @Override
    void stop() {
      undertowServer.stop()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }

  }

  @Override
  UndertowServer server() {
    return new UndertowServer();
  }

  @Override
  String component() {
    return 'undertow-http-server';
  }

  @Override
  String expectedOperationName() {
    return 'undertow-http.request';
  }

  @Override
  boolean testExceptionBody() {
    false
  }


//  def "test send response"() {
//    setup:
//    def uri = address.resolve("sendResponse")
//    HttpResponse response = client.get(uri.toString()).aggregate().join()
//
//    expect:
//    response.status().code() == 200
//    response.contentUtf8().trim() == "sendResponse"
//
//    and:
//    assertTraces(1) {
//      trace(0, 2) {
//        it.span(0) {
//          hasNoParent()
//          name "HTTP GET"
//          kind SpanKind.SERVER
//
//          event(0) {
//            eventName "before-event"
//          }
//          event(1) {
//            eventName "after-event"
//          }
//
//          attributes {
//            "$SemanticAttributes.NET_PEER_PORT" { it instanceof Long }
//            "$SemanticAttributes.NET_PEER_IP" "127.0.0.1"
//            "$SemanticAttributes.HTTP_CLIENT_IP" TEST_CLIENT_IP
//            "$SemanticAttributes.HTTP_SCHEME" uri.getScheme()
//            "$SemanticAttributes.HTTP_HOST" uri.getHost() + ":" + uri.getPort()
//            "$SemanticAttributes.HTTP_TARGET" uri.getPath()
//            "$SemanticAttributes.HTTP_METHOD" "GET"
//            "$SemanticAttributes.HTTP_STATUS_CODE" 200
//            "$SemanticAttributes.HTTP_FLAVOR" "1.1"
//            "$SemanticAttributes.HTTP_USER_AGENT" TEST_USER_AGENT
//            "$SemanticAttributes.HTTP_HOST" "localhost:${port}"
//            "$SemanticAttributes.HTTP_RESPONSE_CONTENT_LENGTH" Long
//            "$SemanticAttributes.HTTP_SCHEME" "http"
//            "$SemanticAttributes.HTTP_TARGET" "/sendResponse"
//            // net.peer.name resolves to "127.0.0.1" on windows which is same as net.peer.ip so then not captured
//            "$SemanticAttributes.NET_PEER_NAME" { it == "localhost" || it == null }
//            "$SemanticAttributes.NET_TRANSPORT" SemanticAttributes.NetTransportValues.IP_TCP
//          }
//        }
//        span(1) {
//          name "sendResponse"
//          kind SpanKind.INTERNAL
//          childOf span(0)
//        }
//      }
//    }
//  }
}
