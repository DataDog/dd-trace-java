import datadog.trace.agent.test.base.HttpServer
import datadog.trace.instrumentation.servlet5.TestServlet5
import jakarta.servlet.Servlet
import jakarta.servlet.ServletException
import org.apache.catalina.Context
import org.apache.catalina.Wrapper
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.valves.ErrorReportValve
import spock.lang.Unroll

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static org.junit.Assume.assumeTrue

@Unroll
class TomcatServletTest extends AbstractServletTest<Tomcat, Context> {


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
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testRequestBodyISVariant() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.throwsException) {
      // Exception classes get wrapped in ServletException
      ["error.message": { endpoint == EXCEPTION ? "Servlet execution threw an exception" : it == endpoint.body },
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
    new TomcatServer(context, dispatch, this.&setupServlets)
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
    wrapper.asyncSupported =true
    servletContext.addChild(wrapper)
    servletContext.addServletMappingDecoded(path, wrapper.name)
  }

  @Override
  Class<Servlet> servlet() {
    TestServlet5
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


