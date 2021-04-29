import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.tomcat.TomcatDecorator
import org.apache.catalina.Context
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.valves.ErrorReportValve
import org.apache.catalina.valves.RemoteIpValve
import org.apache.tomcat.JarScanFilter
import org.apache.tomcat.JarScanType

import javax.servlet.ServletException

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static org.junit.Assume.assumeTrue

class Struts1Test extends HttpServerTest<Tomcat> {

  class TomcatServer implements HttpServer {
    def port = 0
    final server

    TomcatServer() {
      server = new Tomcat()

      def webappDir = new File(TomcatServer.getResource("/webapp/").getFile())
      assert webappDir.exists()
      def baseDir = webappDir.parentFile
      server.setBaseDir(baseDir.getAbsolutePath())

      server.setPort(0) // select random open port
      server.getConnector().enableLookups = true // get localhost instead of 127.0.0.1

      Context servletContext = server.addWebapp("/$context", webappDir.getAbsolutePath())
      // Speed up startup by disabling jar scanning:
      servletContext.getJarScanner().setJarScanFilter(new JarScanFilter() {
          @Override
          boolean check(JarScanType jarScanType, String jarName) {
            return false
          }
        })

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
      return new URI("http://localhost:$port/$context/")
    }

    @Override
    String toString() {
      return this.class.name
    }
  }

  @Override
  HttpServer server() {
    return new TomcatServer()
  }

  String getContext() {
    return "struts"
  }

  @Override
  String component() {
    return TomcatDecorator.DECORATE.component()
  }

  @Override
  String expectedServiceName() {
    context
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  @Override
  boolean testForwarded() {
    return false
  }

  @Override
  boolean testRedirect() {
    return false
  }

  def "test exception with custom status"() {
    setup:
    assumeTrue(testException())
    def request = request(CUSTOM_EXCEPTION, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    if (testExceptionBody()) {
      assert response.body().string() == CUSTOM_EXCEPTION.body
    }
    response.code() == CUSTOM_EXCEPTION.status

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
    def dispatch = false
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
        "$Tags.PEER_HOST_IPV4" { endpoint == HttpServerTest.ServerEndpoint.FORWARDED ? it == endpoint.body : (it == null || it == "127.0.0.1") }
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


