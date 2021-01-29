import com.google.common.io.Files
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import jakarta.servlet.Servlet
import jakarta.servlet.ServletException
import org.apache.catalina.Context
import org.apache.catalina.Wrapper
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.valves.ErrorReportValve
import org.apache.tomcat.JarScanFilter
import org.apache.tomcat.JarScanType
import spock.lang.Unroll

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static org.junit.Assume.assumeTrue

@Unroll
class TomcatServletTest extends AbstractServletTest<Tomcat, Context> {

  @Override
  Tomcat startServer(int port) {
    def tomcatServer = new Tomcat()

    def baseDir = Files.createTempDir()
    baseDir.deleteOnExit()
    tomcatServer.setBaseDir(baseDir.getAbsolutePath())

    tomcatServer.setPort(port)
    tomcatServer.getConnector().enableLookups = true // get localhost instead of 127.0.0.1

    final File applicationDir = new File(baseDir, "/webapps/ROOT")
    if (!applicationDir.exists()) {
      applicationDir.mkdirs()
      applicationDir.deleteOnExit()
    }
    Context servletContext = tomcatServer.addWebapp("/$context", applicationDir.getAbsolutePath())
    // Speed up startup by disabling jar scanning:
    servletContext.getJarScanner().setJarScanFilter(new JarScanFilter() {
      @Override
      boolean check(JarScanType jarScanType, String jarName) {
        return false
      }
    })

    setupServlets(servletContext)

    (tomcatServer.host as StandardHost).errorReportValveClass = ErrorHandlerValve.name

    tomcatServer.start()

    return tomcatServer
  }

  @Override
  void stopServer(Tomcat server) {
    server.stop()
    server.destroy()
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

  @Override
  void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", HttpServerTest.ServerEndpoint endpoint = SUCCESS) {
    def dispatch = isDispatch()
    def bubblesResponse = bubblesResponse()
    trace.span {
      serviceName expectedServiceName()
      operationName expectedOperationName()
      resourceName endpoint.status == 404 ? "404" : "$method ${endpoint.resolve(address).path}"
      spanType DDSpanTypes.HTTP_SERVER
      // Exceptions are always bubbled up, other statuses: only if bubblesResponse == true
      errored((endpoint.errored && bubblesResponse && endpoint != TIMEOUT) || endpoint == EXCEPTION || endpoint == TIMEOUT_ERROR)
      if (parentID != null) {
        traceId traceID
        parentId parentID
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.PEER_PORT" Integer
        "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
        "$Tags.HTTP_METHOD" method
        if (endpoint != TIMEOUT && endpoint != TIMEOUT_ERROR) {
          "$Tags.HTTP_STATUS" { it == endpoint.status || !bubblesResponse }
        } else {
          "timeout" 1_000
        }
        if (context) {
          "servlet.context" "/$context"
        }

        if (dispatch) {
          "servlet.path" "/dispatch$endpoint.path"
        } else {
          "servlet.path" endpoint.path
        }

        if (endpoint.throwsException && !dispatch) {
          // Exception classes get wrapped in ServletException
          "error.msg" { endpoint == EXCEPTION ? "Servlet execution threw an exception" : it == endpoint.body }
          "error.type" { it == ServletException.name || it == InputMismatchException.name }
          "error.stack" String
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" endpoint.query
        }
        defaultTags(true)
      }
    }
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


