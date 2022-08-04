import com.google.common.io.Files
import datadog.trace.agent.test.base.HttpServer
import jakarta.servlet.Servlet
import jakarta.servlet.ServletException
import org.apache.catalina.Context
import org.apache.catalina.Wrapper
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.valves.ErrorReportValve
import org.apache.catalina.valves.RemoteIpValve
import org.apache.tomcat.JarScanFilter
import org.apache.tomcat.JarScanType
import spock.lang.Unroll

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static org.junit.Assume.assumeTrue

@Unroll
class TomcatServletTest extends AbstractServletTest<Tomcat, Context> {

  class TomcatServer implements HttpServer {
    def port = 0
    final Tomcat server

    TomcatServer() {
      server = new Tomcat()

      def baseDir = Files.createTempDir()
      baseDir.deleteOnExit()
      server.setBaseDir(baseDir.getAbsolutePath())

      server.setPort(0) // select random open port
      server.getConnector().enableLookups = true // get localhost instead of 127.0.0.1

      final File applicationDir = new File(baseDir, "/webapps/ROOT")
      if (!applicationDir.exists()) {
        applicationDir.mkdirs()
        applicationDir.deleteOnExit()
      }
      Context servletContext = server.addWebapp("/$context", applicationDir.getAbsolutePath())
      // Speed up startup by disabling jar scanning:
      servletContext.getJarScanner().setJarScanFilter(new JarScanFilter() {
          @Override
          boolean check(JarScanType jarScanType, String jarName) {
            return false
          }
        })

      setupServlets(servletContext)

      (server.host as StandardHost).errorReportValveClass = ErrorHandlerValve.name
      server.host.pipeline.addValve(new RemoteIpValve())
    }

    @Override
    void start() {
      server.start()
      port = server.service.findConnectors()[0].localPort
      assert port > 0
    }

    @Override
    void stop() {
      server.stop()
      server.destroy()
    }

    @Override
    URI address() {
      if (dispatch) {
        return new URI("http://localhost:$port/$context/dispatch/")
      }
      return new URI("http://localhost:$port/$context/")
    }

    @Override
    String toString() {
      return this.class.name
    }
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
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
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.throwsException) {
      // Exception classes get wrapped in ServletException
      ["error.msg": { endpoint == EXCEPTION ? "Servlet execution threw an exception" : it == endpoint.body },
        "error.type": { it == ServletException.name || it == InputMismatchException.name },
        "error.stack": String]
    } else {
      Collections.emptyMap()
    }
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    Map<String, Serializable> map = ["servlet.path": dispatch ? "/dispatch$endpoint.path" : endpoint.path]
    if (context) {
      map.put("servlet.context", "/$context")
    }
    map
  }

  @Override
  boolean expectedErrored(ServerEndpoint endpoint) {
    (endpoint.errored && bubblesResponse()) || [EXCEPTION, CUSTOM_EXCEPTION, TIMEOUT_ERROR].contains(endpoint)
  }

  @Override
  Serializable expectedStatus(ServerEndpoint endpoint) {
    return { !bubblesResponse() || it == endpoint.status }
  }

  @Override
  HttpServer server() {
    return new TomcatServer()
  }

  @Override
  String getContext() {
    return "tomcat-context"
  }

  @Override
  void addServlet(Context servletContext, String path, Class<Servlet> servlet) {
    Wrapper wrapper = servletContext.createWrapper()
    wrapper.name = UUID.randomUUID()
    wrapper.servletClass = servlet.name
    servletContext.addChild(wrapper)
    servletContext.addServletMappingDecoded(path, wrapper.name)
  }

  @Override
  Class<Servlet> servlet() {
    TestServlet
  }

  def "test exception with custom status"() {
    setup:
    assumeTrue(testException())
    def request = request(CUSTOM_EXCEPTION, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == CUSTOM_EXCEPTION.status
    if (testExceptionBody()) {
      assert response.body().string() == CUSTOM_EXCEPTION.body
    }

    and:
    assertTraces(1) {
      trace(spanCount(CUSTOM_EXCEPTION)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, CUSTOM_EXCEPTION)
        if (hasHandlerSpan()) {
          handlerSpan(it, CUSTOM_EXCEPTION)
        }
        controllerSpan(it, CUSTOM_EXCEPTION)
        if (hasResponseSpan(CUSTOM_EXCEPTION)) {
          responseSpan(it, CUSTOM_EXCEPTION)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  static class ErrorHandlerValve extends ErrorReportValve {
    @Override
    protected void report(Request request, Response response, Throwable t) {
      if (!response.error) {
        return
      }
      try {
        if (t) {
          if (t instanceof ServletException) {
            t = t.rootCause
          }
          if (t instanceof InputMismatchException) {
            response.status = CUSTOM_EXCEPTION.status
          }
          response.reporter.write(t.message)
        } else if (response.message) {
          response.reporter.write(response.message)
        }
      } catch (IOException e) {
        e.printStackTrace()
      }
    }
  }
}


