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
          .addExactPath(SUCCESS.getPath()) { exchange ->
            controller(SUCCESS) {
              exchange.getResponseSender().send(SUCCESS.body)
            }
          }
          .addExactPath(FORWARDED.getPath()) { exchange ->
            controller(FORWARDED) {
              exchange.getResponseSender().send(exchange.getRequestHeaders().get("x-forwarded-for", 0))
            }
          }
          .addExactPath(QUERY_ENCODED_BOTH.getPath()) { exchange ->
            controller(QUERY_ENCODED_BOTH) {
              exchange.getResponseSender().send("some=" + exchange.getQueryParameters().get("some").peek())
            }
          }
          .addExactPath(QUERY_ENCODED_QUERY.getPath()) { exchange ->
            controller(QUERY_ENCODED_QUERY) {
              exchange.getResponseSender().send("some=" + exchange.getQueryParameters().get("some").peek())
            }
          }
          .addExactPath(QUERY_PARAM.getPath()) { exchange ->
            controller(QUERY_PARAM) {
              exchange.getResponseSender().send(exchange.getQueryString())
            }
          }
          .addExactPath(REDIRECT.getPath()) { exchange ->
            controller(REDIRECT) {
              exchange.setStatusCode(StatusCodes.FOUND)
              exchange.getResponseHeaders().put(Headers.LOCATION, REDIRECT.body)
              exchange.endExchange()
            }
          }
          .addExactPath(ERROR.getPath()) { exchange ->
            controller(ERROR) {
              exchange.setStatusCode(ERROR.status)
              exchange.getResponseSender().send(ERROR.body)
            }
          }
          .addExactPath(EXCEPTION.getPath()) { exchange ->
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

  @Override
  boolean testEncodedPath() {
    // Don't know why Undertow is unable to match the encoded path
    false
  }
}
