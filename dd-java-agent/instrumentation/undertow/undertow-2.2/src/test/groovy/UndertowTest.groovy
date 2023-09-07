import datadog.appsec.api.blocking.Blocking
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.server.DefaultResponseListener
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.form.FormData
import io.undertow.server.handlers.form.FormDataParser
import io.undertow.server.handlers.form.FormParserFactory
import io.undertow.util.Headers
import io.undertow.util.HttpString
import io.undertow.util.StatusCodes

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.*

class UndertowTest extends HttpServerTest<Undertow> {
  class UndertowServer implements HttpServer {
    def port = 0
    Undertow undertowServer

    UndertowServer() {
      undertowServer = Undertow.builder()
        .addHttpListener(port, "localhost")
        .setServerOption(UndertowOptions.DECODE_URL, true)
        .setHandler(Handlers.httpContinueRead(Handlers.path()
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
        .addExactPath(CREATED_IS.path) { exc ->
          def handler = { exchange ->
            controller(CREATED_IS) {
              exchange.responseSender.send(
                "${CREATED_IS.body}: ${exchange.inputStream.getText('ISO-8859-1')}")
            }
          } as HttpHandler

          exc.startBlocking()
          if (exc.inIoThread) {
            exc.dispatch(handler)
          } else {
            handler.handleRequest(exc)
          }
        }
        .addExactPath(BODY_URLENCODED.path) { HttpServerExchange exc ->
          def handler = { exchange ->
            controller(BODY_URLENCODED) {
              FormDataParser parser = FormParserFactory.builder().build().createParser(exchange)
              FormData formData = parser.parseBlocking()
              def params = [:]
              for (String key : formData) {
                if (key == 'ignore') {
                  continue
                }
                params[key] = formData.get(key).collect { it.value }
              }
              exchange.responseSender.send(params as String)
            }
          } as HttpHandler

          exc.startBlocking()
          if (exc.inIoThread) {
            exc.dispatch(handler)
          } else {
            handler.handleRequest(exc)
          }
        }
        .addExactPath(QUERY_ENCODED_BOTH.getPath()) { exchange ->
          controller(QUERY_ENCODED_BOTH) {
            exchange.getResponseHeaders().put(new HttpString(HttpServerTest.IG_RESPONSE_HEADER), HttpServerTest.IG_RESPONSE_HEADER_VALUE)
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
        .addExactPath(USER_BLOCK.getPath()) { exchange ->
          controller(USER_BLOCK) {
            try {
              Blocking.forUser('user-to-block').blockIfMatch()
              exchange.getResponseSender().send('user not blocked')
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
    return 'undertow-http.request'
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
  boolean testRequestBody() {
    // no low-level method to get Reader
    // see io.undertow.servlet.spec.HttpServletRequestImpl
    // getReader is implemented in terms of exchange.getInputStream()
    false
  }

  @Override
  boolean testRequestBodyISVariant() {
    false // interception of the exchange InputStream not implemented
  }

  @Override
  boolean testBodyUrlencoded() {
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
      ["error.message": "${endpoint.body}",
        "error.type": { it == Exception.name || it == InputMismatchException.name },
        "error.stack": String]
    } else {
      Collections.emptyMap()
    }
  }
}
