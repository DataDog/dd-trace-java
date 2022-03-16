package test.filter

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator
import datadog.trace.instrumentation.tomcat.TomcatDecorator
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import test.boot.SecurityConfig

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static java.util.Collections.singletonMap

class ServletFilterTest extends HttpServerTest<ConfigurableApplicationContext> {

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  class SpringBootServer implements HttpServer {
    def port = 0
    def context
    final app = new SpringApplication(FilteredAppConfig, SecurityConfig)

    @Override
    void start() {
      app.setDefaultProperties(singletonMap("server.port", 0))
      context = app.run() as EmbeddedWebApplicationContext
      port = context.embeddedServletContainer.port
      assert port > 0
    }

    @Override
    void stop() {
      context.close()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }

    @Override
    String toString() {
      return this.class.name
    }
  }

  @Override
  HttpServer server() {
    return new SpringBootServer()
  }

  @Override
  String expectedServiceName() {
    return "root-servlet"
  }

  @Override
  String component() {
    return TomcatDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  @Override
  String testPathParam() {
    "/path/{id}/param"
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean testNotFound() {
    // Tested by the regular spring boot test
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return [REDIRECT, ERROR, EXCEPTION].contains(endpoint)
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.errored) {
      ["error.msg"  : { it == null || it == "Filter execution threw an exception" },
        "error.type" : { it == null || it == "javax.servlet.ServletException" },
        "error.stack": { it == null || it instanceof String }]
    } else {
      Collections.emptyMap()
    }
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    if (endpoint == PATH_PARAM) {
      return testPathParam()
    }
    return endpoint.path
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path, "servlet.context": "/"]
  }

  @Override
  int spanCount(ServerEndpoint endpoint) {
    if (endpoint == ERROR) {
      // adds servlet.forward/GET /error and spring.handler/BasicErrorController.error
      return super.spanCount(endpoint) + 2
    } else if (endpoint == EXCEPTION) {
      // adds servlet.forward/GET /error and spring.handler/BasicErrorController.error
      // removes servlet.response/HttpServletResponse
      return super.spanCount(endpoint) + 1
    }
    return super.spanCount(endpoint)
  }

  @Override
  String expectedResourceName(ServerEndpoint endpoint, String method, URI address) {
    if (endpoint.status == 404 && endpoint.path == "/not-found") {
      return "404"
    } else if (endpoint.hasPathParam) {
      return "$method ${testPathParam()}"
    }
    return "$method ${endpoint.resolve(address).path}"
  }

  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    String method
    switch (endpoint) {
      case REDIRECT:
        method = "sendRedirect"
        break
      case ERROR:
        method = "sendError"
        break
      case EXCEPTION:
      // no method
        break
      default:
        throw new UnsupportedOperationException("responseSpan not implemented for " + endpoint)
    }
    if (endpoint != EXCEPTION) {
      trace.span {
        operationName "servlet.response"
        resourceName "HttpServletResponse.$method"
        childOfPrevious()
        tags {
          "component" "java-web-servlet-response"
          defaultTags()
        }
      }
    }
    if (endpoint == ERROR || endpoint == EXCEPTION) {
      def extraTags = expectedExtraServerTags(endpoint)
      trace.span {
        operationName "servlet.forward"
        resourceName "GET /error"
        spanType DDSpanTypes.HTTP_SERVER
        childOf(trace.span(0))
        tags {
          "component" "java-web-servlet-dispatcher"
          "$Tags.HTTP_ROUTE" "/error"
          addTags(extraTags)
          defaultTags()
        }
      }
      trace.span {
        operationName "spring.handler"
        resourceName "BasicErrorController.error"
        spanType DDSpanTypes.HTTP_SERVER
        childOfPrevious()
        tags {
          "$Tags.COMPONENT" SpringWebHttpServerDecorator.DECORATE.component()
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
          defaultTags()
        }
      }
    }
  }
}
