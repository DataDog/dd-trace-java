import datadog.trace.api.ProcessTags
import spock.lang.IgnoreIf

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED

import com.google.common.io.Files
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.DDTags
import datadog.trace.instrumentation.servlet3.TestServlet3
import org.apache.catalina.Context
import org.apache.catalina.Engine
import org.apache.catalina.Wrapper
import org.apache.catalina.connector.Connector
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.startup.Embedded
import org.apache.catalina.valves.ErrorReportValve
import org.apache.coyote.http11.Http11BaseProtocol

import javax.servlet.Servlet
import javax.servlet.ServletException

abstract class TomcatServletTest extends AbstractServletTest<Embedded, Context> {

  class TomcatServer implements HttpServer {
    def port = 0
    final server

    TomcatServer() {
      server = new Embedded()
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
      connector.port = 0 // select random open port

      // Call addConnector() to attach this Connector to the set of defined Connectors for this object. The added Connector will use the most recently added Engine to process its received requests.
      server.addConnector(connector)

      host.errorReportValveClass = ErrorHandlerValve.name
    }

    @Override
    void start() {
      server.start()
      port = ((server.connectors[0] as Connector).protocolHandler as Http11BaseProtocol).ep.serverSocket.localPort
      assert port > 0
      if (testProcessTags()) {
        assert ProcessTags.getTagsAsStringList().containsAll(["server.type:tomcat", "server.name:test"])
      } else {
        assert ProcessTags.getTagsAsStringList() == null
      }
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
    servletContext.addServletMapping(path, wrapper.name)
  }

  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }

  @Override
  boolean testEncodedPath() {
    // Don't know why Tomcat 5.5 is unable to match the encoded path to a servlet path
    false
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
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testRequestBodyISVariant() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  boolean testSessionId() {
    true
  }


  boolean testProcessTags() {
    false
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "${testProcessTags()}")
  }

  def cleanupSpec() {
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    ProcessTags.reset()
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    def responseSpans = [REDIRECT, NOT_FOUND, ERROR, EXCEPTION, CUSTOM_EXCEPTION]
    return responseSpans.contains(endpoint)
  }

  @Override
  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    switch (endpoint) {
      case REDIRECT:
        trace.span {
          operationName "servlet.response"
          resourceName "HttpServletResponse.sendRedirect"
          childOfPrevious()
          tags {
            "component" "java-web-servlet-response"
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags()
          }
        }
        break
      case ERROR:
      case NOT_FOUND:
        trace.span {
          operationName "servlet.response"
          resourceName "HttpServletResponse.sendError"
          childOfPrevious()
          tags {
            "component" "java-web-servlet-response"
            defaultTags()
          }
        }
        break
      case EXCEPTION:
      case CUSTOM_EXCEPTION:
        trace.span {
          operationName "servlet.response"
          resourceName "HttpServletResponse.sendError"
          tags {
            "component" "java-web-servlet-response"
            defaultTags()
          }
        }
        break
      default:
        throw new UnsupportedOperationException("responseSpan not implemented for " + endpoint)
    }
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.throwsException) {
      // Exception classes get wrapped in ServletException
      ["error.message": { endpoint == EXCEPTION ? "Servlet execution threw an exception" : it == endpoint.body },
        "error.type"   : { it == ServletException.name || it == InputMismatchException.name },
        "error.stack"  : String]
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

  @IgnoreIf({ !instance.testException() })
  def "test exception with custom status"() {
    setup:
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

class TomcatServletV0Test extends TomcatServletTest implements TestingGenericHttpNamingConventions.ServerV0 {
}

class TomcatServletV1ForkedTest extends TomcatServletTest implements TestingGenericHttpNamingConventions.ServerV1 {

  @Override
  boolean testProcessTags() {
    true
  }
}
