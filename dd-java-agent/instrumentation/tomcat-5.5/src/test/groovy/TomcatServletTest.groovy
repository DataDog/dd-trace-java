import com.google.common.io.Files
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.apache.catalina.Context
import org.apache.catalina.Engine
import org.apache.catalina.Wrapper
import org.apache.catalina.connector.Connector
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.startup.Embedded
import org.apache.catalina.valves.ErrorReportValve
import spock.lang.Unroll

import javax.servlet.Servlet
import javax.servlet.ServletException

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static org.junit.Assume.assumeTrue

@Unroll
class TomcatServletTest extends AbstractServletTest<Embedded, Context> {

  @Override
  Embedded startServer(int port) {
    def server = new Embedded()
    def baseDir = Files.createTempDir()
    baseDir.deleteOnExit()

    System.setProperty("catalina.home", baseDir.path)

    final File webappDir = new File(baseDir, "/webapps")
    webappDir.mkdirs()
    webappDir.deleteOnExit()

    // Call createEngine() to create an Engine object, and then call its property setters as desired.
    Engine engine = server.createEngine()
    engine.name = "test"
    engine.setDefaultHost("localhost")

    // Call createHost() to create at least one virtual Host associated with the newly created Engine, and then call its property setters as desired.
    StandardHost host = server.createHost("localhost", "$webappDir")
    //  After you customize this Host, add it to the corresponding Engine with engine.addChild(host).
    engine.addChild(host)


    // Call createContext() to create at least one Context associated with each newly created Host, and then call its property setters as desired.
    Context context = server.createContext("/$context", "$webappDir")
    context.privileged = true
    setupServlets(context)

    // After you customize this Context, add it to the corresponding Host with host.addChild(context).
    host.addChild(context)
    // You SHOULD create a Context with a pathname equal to a zero-length string, which will be used to process all requests not mapped to some other Context.

    // Call addEngine() to attach this Engine to the set of defined Engines for this object.
    server.addEngine(engine)

    // Call createConnector() to create at least one TCP/IP connector, and then call its property setters as desired.
    // There seems to be a bug in this version that makes it impossible to create an 'http' connector
//    Connector connector = server.createConnector("localhost", port, true)
    Connector connector = new Connector("HTTP/1.1")
    connector.enableLookups = true // get localhost instead of 127.0.0.1
    connector.scheme = "http"
    connector.port = port

    // Call addConnector() to attach this Connector to the set of defined Connectors for this object. The added Connector will use the most recently added Engine to process its received requests.
    server.addConnector(connector)

    host.errorReportValveClass = ErrorHandlerValve.name

    server.start()

    return server
  }

  @Override
  void stopServer(Embedded server) {
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
    servletContext.addServletMapping(path, wrapper.name)
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
          "error.msg" endpoint.body
          "error.type" { it == Exception.name || it == InputMismatchException.name }
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


