import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.util.HeaderMap
import io.undertow.util.Headers
import io.undertow.util.HttpString
import io.undertow.util.StatusCodes

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class UndertowDispatcherTest extends HttpServerTest<Undertow> {
  class UndertowServer implements HttpServer {
    def port = 0
    Undertow undertowServer;

    UndertowServer() {
      undertowServer = Undertow.builder()
        .addHttpListener(port, "localhost")
        .setServerOption(UndertowOptions.DECODE_URL, true)
        .setHandler(Handlers.path()
          .addExactPath(SUCCESS.getPath()) { exchange ->
            exchange.dispatch(
              controller(SUCCESS) {
                new Runnable() {
                  public void run() {
                    exchange.getResponseSender().send(SUCCESS.body)
                    exchange.endExchange()
                  }
                }
              })
          }
          .addExactPath(FORWARDED.getPath()) { exchange ->
            exchange.dispatch(
              controller(FORWARDED) {
                new Runnable() {
                  public void run() {
                    exchange.getResponseSender().send(exchange.getRequestHeaders().get("x-forwarded-for", 0))
                    exchange.endExchange()
                  }
                }
              })
          }
          .addExactPath(QUERY_ENCODED_BOTH.getPath()) { exchange ->
            exchange.dispatch(
              controller(QUERY_ENCODED_BOTH) {
                new Runnable() {
                  public void run() {
                    exchange.getResponseHeaders().put(new HttpString(HttpServerTest.IG_RESPONSE_HEADER), HttpServerTest.IG_RESPONSE_HEADER_VALUE)
                    exchange.getResponseSender().send("some=" + exchange.getQueryParameters().get("some").peek())
                    exchange.endExchange()
                  }
                }
              })
          }
          .addExactPath(QUERY_ENCODED_QUERY.getPath()) { exchange ->
            exchange.dispatch(
              controller(QUERY_ENCODED_QUERY) {
                new Runnable() {
                  public void run() {
                    exchange.getResponseSender().send("some=" + exchange.getQueryParameters().get("some").peek())
                    exchange.endExchange()
                  }
                }
              })
          }
          .addExactPath(QUERY_PARAM.getPath()) { exchange ->
            exchange.dispatch(
              controller(QUERY_PARAM) {
                new Runnable() {
                  public void run() {
                    exchange.getResponseSender().send("some=" + exchange.getQueryParameters().get("some").peek())
                    exchange.endExchange()
                  }
                }
              })
          }
          .addExactPath(REDIRECT.getPath()) { exchange ->
            exchange.dispatch(
              controller(REDIRECT) {
                new Runnable() {
                  public void run() {
                    exchange.setStatusCode(StatusCodes.FOUND)
                    exchange.getResponseHeaders().put(Headers.LOCATION, REDIRECT.body)
                    exchange.endExchange()
                  }
                }
              })
          }
          .addExactPath(ERROR.getPath()) { exchange ->
            exchange.dispatch(
              controller(ERROR) {
                new Runnable() {
                  public void run() {
                    exchange.setStatusCode(ERROR.status)
                    exchange.getResponseSender().send(ERROR.body)
                    exchange.endExchange()
                  }
                }
              })
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
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.throwsException) {
      ["error.msg": "${endpoint.body}",
        "error.type": { it == Exception.name || it == InputMismatchException.name },
        "error.stack": String]
    } else {
      Collections.emptyMap()
    }
  }
}
