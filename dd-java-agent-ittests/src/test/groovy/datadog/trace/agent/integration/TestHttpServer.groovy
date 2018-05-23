package datadog.trace.agent.integration

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.opentracing.Scope
import io.opentracing.SpanContext
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMap
import io.opentracing.util.GlobalTracer

import java.util.concurrent.Executors

/**
 * A simple http server used for testing.<br>
 * Binds locally to {@link #port}.
 *
 * <p>To start: {@link #startServer()}<br>
 * to stop: {@link #stopServer()}
 */
class TestHttpServer {
  /**
   * By default the test server will mock a datadog traced server. Set this header to a value of
   * false to disable.
   */
  public static final String IS_DD_SERVER = "is-dd-server"

  private static HttpServer server = null
  private static int port = 0

  static int getPort() {
    if (port == 0) {
      throw new RuntimeException("Server not started")
    }
    return port
  }

  /**
   * Start the server. Has no effect if already started.
   *
   * @throws IOException
   */
  static synchronized startServer() {
    if (null == server) {
      InetSocketAddress address = new InetSocketAddress("localhost", 0)// bind to any port on local
      HttpServer httpServer =  HttpServer.create(address, 0)
      httpServer.setExecutor(Executors.newCachedThreadPool())
      httpServer.createContext('/') { HttpExchange httpExchange ->
        boolean isDDServer = true
        if (httpExchange.requestHeaders.containsKey(IS_DD_SERVER)) {
          isDDServer = Boolean.parseBoolean(httpExchange.requestHeaders.getFirst(IS_DD_SERVER))
        }
        if (isDDServer) {
          final SpanContext extractedContext =
            GlobalTracer.get()
              .extract(Format.Builtin.HTTP_HEADERS, new HttpExchangeAdapter(httpExchange.requestHeaders))
          Scope scope =
            GlobalTracer.get()
              .buildSpan("test-http-server")
              .asChildOf(extractedContext)
              .startActive(true)
          scope.close()
        }

        def out = "<html><body><h1>Hello test.</h1>\n".getBytes('UTF-8')
        httpExchange.sendResponseHeaders(200, out.length)
        httpExchange.responseBody.withCloseable { it.write(out) }
      }
      httpServer.start()
      server = httpServer

      port = httpServer.address.port
    }
  }

  /** Stop the server. Has no effect if already stopped. */
  static synchronized void stopServer() {
    if (null != server) {
      server.stop(0)
      server = null
      port = 0
    }
  }

  private static class HttpExchangeAdapter implements TextMap {
    final Headers context

    HttpExchangeAdapter(Headers context) {
      this.context = context
    }

    @Override
    void put(String key, String value) {
      context.add(key, value)
    }

    @Override
    Iterator<Map.Entry<String, String>> iterator() {
      Map<String, String> firstHeaderValues = context.collectEntries { String k, List<String> v ->
        [(k): v.get(0)]
      }
      return firstHeaderValues.entrySet().iterator()
    }
  }
}
