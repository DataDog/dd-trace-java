import datadog.appsec.api.blocking.Blocking
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.io.IoCallback
import io.undertow.io.Sender
import io.undertow.server.DefaultResponseListener
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import io.undertow.util.HttpString
import io.undertow.util.StatusCodes

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.*

abstract class UndertowDispatcherTest extends HttpServerTest<Undertow> {
  class UndertowServer implements HttpServer {
    def port = 0
    Undertow undertowServer

    UndertowServer() {
      undertowServer = Undertow.builder()
        .addHttpListener(port, "localhost")
        .setServerOption(UndertowOptions.DECODE_URL, true)
        .setHandler(Handlers.httpContinueRead (Handlers.path()
        .addExactPath(SUCCESS.getPath()) { exchange ->
          exchange.dispatch(
            new Runnable() {
              void run() {
                controller(SUCCESS) {
                  exchange.getResponseSender().send(SUCCESS.body, new IoCallback() {
                      @Override
                      void onComplete(HttpServerExchange xchg, Sender sender) {
                        xchg.endExchange()
                      }

                      @Override
                      void onException(HttpServerExchange xchg, Sender sender, IOException exception) {
                        exception.print(System.err)
                        xchg.endExchange()
                      }
                    })
                }
              }
            }
            )
        }
        .addExactPath(FORWARDED.getPath()) { exchange ->
          exchange.dispatch(
            new Runnable() {
              void run() {
                controller(FORWARDED) {
                  exchange.getResponseSender().send(exchange.getRequestHeaders().get("x-forwarded-for", 0))
                  exchange.endExchange()
                }
              }
            })
        }
        .addExactPath(QUERY_ENCODED_BOTH.getPath()) { exchange ->
          exchange.dispatch(
            new Runnable() {
              void run() {
                controller(QUERY_ENCODED_BOTH) {
                  exchange.getResponseHeaders().put(new HttpString(HttpServerTest.IG_RESPONSE_HEADER), HttpServerTest.IG_RESPONSE_HEADER_VALUE)
                  exchange.getResponseSender().send("some=" + exchange.getQueryParameters().get("some").peek())
                  exchange.endExchange()
                }
              }
            })
        }
        .addExactPath(QUERY_ENCODED_QUERY.getPath()) { exchange ->
          exchange.dispatch(
            new Runnable() {
              void run() {
                controller(QUERY_ENCODED_BOTH) {
                  exchange.getResponseSender().send("some=" + exchange.getQueryParameters().get("some").peek())
                  exchange.endExchange()
                }
              }
            })
        }
        .addExactPath(QUERY_PARAM.getPath()) { exchange ->
          exchange.dispatch(
            new Runnable() {
              void run() {
                controller(QUERY_PARAM) {
                  exchange.getResponseSender().send("some=" + exchange.getQueryParameters().get("some").peek())
                  exchange.endExchange()
                }
              }
            })
        }
        .addExactPath(REDIRECT.getPath()) { exchange ->
          exchange.dispatch(
            new Runnable() {
              void run() {
                controller(REDIRECT) {
                  exchange.setStatusCode(StatusCodes.FOUND)
                  exchange.getResponseHeaders().put(Headers.LOCATION, REDIRECT.body)
                  exchange.endExchange()
                }
              }
            })
        }
        .addExactPath(ERROR.getPath()) { exchange ->
          exchange.dispatch(
            new Runnable() {
              void run() {
                controller(ERROR) {
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
        .addExactPath(USER_BLOCK.path) { exchange ->
          controller(USER_BLOCK) {
            try {
              Blocking.forUser('user-to-block').blockIfMatch()
              exchange.statusCode = 200
              exchange.responseSender.send('user not blocked')
              exchange.endExchange()
            } catch (BlockingException be) {
              exchange.putAttachment(DefaultResponseListener.EXCEPTION, be)
            }
          }
        }
        )).build()
    }

    @Override
    void start() {
      undertowServer.start()
      InetSocketAddress addr = (InetSocketAddress) undertowServer.getListenerInfo().get(0).getAddress()
      port = addr.getPort()
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
    return new UndertowServer()
  }

  @Override
  String component() {
    return 'undertow-http-server'
  }

  @Override
  String expectedOperationName() {
    return operation()
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
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
  boolean testBlocking() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.throwsException) {
      ["error.message"  : "${endpoint.body}",
        "error.type" : { it == Exception.name || it == InputMismatchException.name },
        "error.stack": String]
    } else {
      Collections.emptyMap()
    }
  }
}

class UndertowDispatcherV0ForkedTest extends UndertowDispatcherTest {
  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "undertow-http.request"
  }
}

class UndertowDispatcherV1ForkedTest extends UndertowDispatcherTest implements TestingGenericHttpNamingConventions.ServerV1 {
}
