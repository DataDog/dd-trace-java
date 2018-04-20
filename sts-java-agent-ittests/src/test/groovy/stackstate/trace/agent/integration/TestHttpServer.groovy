package stackstate.trace.agent.integration

import io.opentracing.Scope
import io.opentracing.SpanContext
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMap
import io.opentracing.util.GlobalTracer
import ratpack.groovy.test.embed.GroovyEmbeddedApp
import ratpack.handling.Context

import static ratpack.groovy.test.embed.GroovyEmbeddedApp.ratpack

/**
 * A simple http server used for testing.<br>
 * Binds locally to {@link #port}.
 *
 * <p>To start: {@link #startServer()}<br>
 * to stop: {@link #stopServer()}
 */
class TestHttpServer {
  /**
   * By default the test server will mock a stackstate traced server. Set this header to a value of
   * false to disable.
   */
  public static final String IS_STS_SERVER = "is-sts-server"

  private static GroovyEmbeddedApp server = null
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
      server = ratpack {
        handlers {
          get {
            String msg = "<html><body><h1>Hello test.</h1>\n"
            boolean isSTSServer = true
            if (context.request.getHeaders().contains(IS_STS_SERVER)) {
              isSTSServer = Boolean.parseBoolean(context.request.getHeaders().get(IS_STS_SERVER))
            }
            if (isSTSServer) {
              final SpanContext extractedContext =
                GlobalTracer.get()
                  .extract(Format.Builtin.HTTP_HEADERS, new RatpackResponseAdapter(context))
              Scope scope =
                GlobalTracer.get()
                  .buildSpan("test-http-server")
                  .asChildOf(extractedContext)
                  .startActive(true)
              scope.close()
            }

            response.status(200).send(msg)
          }
        }
      }
      port = server.address.port
    }
  }

  /** Stop the server. Has no effect if already stopped. */
  static synchronized void stopServer() {
    if (null != server) {
      server.close()
      server = null
      port = 0
    }
  }

  private static class RatpackResponseAdapter implements TextMap {
    final Context context

    RatpackResponseAdapter(Context context) {
      this.context = context
    }

    @Override
    void put(String key, String value) {
      context.response.set(key, value)
    }

    @Override
    Iterator<Map.Entry<String, String>> iterator() {
      return context.request.getHeaders().asMultiValueMap().entrySet().iterator()
    }
  }
}
