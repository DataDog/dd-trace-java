import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.server.handlers.PathHandler
import io.undertow.servlet.api.DeploymentInfo
import io.undertow.servlet.api.DeploymentManager
import io.undertow.servlet.api.ServletContainer
import io.undertow.servlet.api.ServletInfo

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.*

abstract class UndertowServletTest extends HttpServerTest<Undertow> {
  private static final CONTEXT = "ctx"

  class UndertowServer implements HttpServer {
    def port = 0
    Undertow undertowServer

    UndertowServer() {
      final PathHandler root = new PathHandler()
      final ServletContainer container = ServletContainer.Factory.newInstance()

      DeploymentInfo builder = new DeploymentInfo()
        .setClassLoader(UndertowServletTest.getClassLoader())
        .setContextPath("/$CONTEXT")
        .setDeploymentName("servletContext.war")
        .addServlet(new ServletInfo("SuccessServlet", SuccessServlet).addMapping(SUCCESS.getPath()))
        .addServlet(new ServletInfo("ForwardedServlet", ForwardedServlet).addMapping(FORWARDED.getPath()))
        .addServlet(new ServletInfo("QueryEncodedBothServlet", QueryEncodedBothServlet).addMapping(QUERY_ENCODED_BOTH.getPath()))
        .addServlet(new ServletInfo("QueryEncodedServlet", QueryEncodedServlet).addMapping(QUERY_ENCODED_QUERY.getPath()))
        .addServlet(new ServletInfo("QueryParamServlet", QueryServlet).addMapping(QUERY_PARAM.getPath()))
        .addServlet(new ServletInfo("RedirectServlet", RedirectServlet).addMapping(REDIRECT.getPath()))
        .addServlet(new ServletInfo("ErrorServlet", ErrorServlet).addMapping(ERROR.getPath()))
        .addServlet(new ServletInfo("ExceptionServlet", ExceptionServlet).addMapping(EXCEPTION.getPath()))
        .addServlet(new ServletInfo("UserBlockServlet", UserBlockServlet).addMapping(USER_BLOCK.path))
        .addServlet(new ServletInfo("NotHereServlet", NotHereServlet).addMapping(NOT_HERE.path))

      DeploymentManager manager = container.addDeployment(builder)
      manager.deploy()
      root.addPrefixPath(builder.getContextPath(), manager.start())

      undertowServer = Undertow.builder()
        .addHttpListener(port, "localhost")
        .setServerOption(UndertowOptions.DECODE_URL, true)
        .setHandler(root)
        .build()
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
      return new URI("http://localhost:$port/$CONTEXT/")
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
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean testBlocking() {
    true
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == NOT_FOUND
  }

  @Override
  String expectedServiceName() {
    CONTEXT
  }

  @Override
  String expectedResourceName(ServerEndpoint endpoint, String method, URI address) {
    if (endpoint.status == 404 && endpoint.path == "/not-found") {
      return "404"
    } else if (endpoint.hasPathParam) {
      return "$method ${testPathParam()}"
    }
    def base = endpoint == LOGIN ? address : address.resolve("/")
    return "$method ${endpoint.resolve(base).path}"
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path, "servlet.context": "/$CONTEXT"]
  }

  @Override
  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    if (endpoint == REDIRECT) {
      trace.span {
        operationName "servlet.response"
        resourceName "HttpServletResponse.sendRedirect"
        childOfPrevious()
        tags {
          "component" "java-web-servlet-response"
          defaultTags()
        }
      }
    } else if (endpoint == NOT_FOUND) {
      trace.span {
        operationName "servlet.response"
        resourceName "HttpServletResponse.sendError"
        childOfPrevious()
        tags {
          "component" "java-web-servlet-response"
          defaultTags()
        }
      }
    } else {
      throw new UnsupportedOperationException("responseSpan not implemented for " + endpoint)
    }
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    switch (endpoint) {
      case LOGIN:
      case NOT_FOUND:
        return null
      case PATH_PARAM:
        return testPathParam()
      default:
        return endpoint.path
    }
  }

  def "test not-here"() {
    setup:
    def request = request(NOT_HERE, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == NOT_HERE.status

    and:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        serverSpan(it, null, null, method, NOT_HERE)
        controllerSpan(it)
        handlerSpan(it, NOT_HERE)
      }
    }

    where:
    method = "GET"
    body = null
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "servlet.response"
      resourceName "HttpServletResponse.sendError"
      spanType null
      errored endpoint == EXCEPTION
      if (endpoint != REDIRECT) {
        childOfPrevious()
      }
      tags {
        "$Tags.COMPONENT" "java-web-servlet-response"
        "$Tags.SPAN_KIND" null
        if (endpoint == EXCEPTION) {
          errorTags(Exception, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }
}

class UndertowServletV0ForkedTest extends UndertowServletTest implements TestingGenericHttpNamingConventions.ServerV0 {
}

class UndertowServletV1ForkedTest extends UndertowServletTest implements TestingGenericHttpNamingConventions.ServerV1 {
}
