package test.filter

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Shared
import test.ContainerType
import test.boot.SecurityConfig

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static java.util.Collections.singletonMap
import static test.ContainerType.JETTY

class ServletFilterTest extends HttpServerTest<ConfigurableApplicationContext> {

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Shared
  EmbeddedWebApplicationContext context

  class SpringBootServer implements HttpServer {
    def port = 0
    final app = new SpringApplication(FilteredAppConfig, SecurityConfig)

    SpringBootServer() {
      app.setDefaultProperties(singletonMap("server.port", 0))
      context = app.run() as EmbeddedWebApplicationContext
    }

    @Override
    void start() {
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

  private ContainerType containerType

  ContainerType getContainerType() {
    if (containerType == null) {
      containerType = ContainerType.forEmbeddedServletContainer(context.getEmbeddedServletContainer())
    }
    return containerType
  }

  @Override
  String component() {
    return getContainerType().component
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  @Override
  String testPathParam() {
    "/path/?/param"
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
      ["error.msg"  : { it == null || it == "java.lang.Exception: controller exception" },
        "error.type" : { it == null || it == "javax.servlet.ServletException" },
        "error.stack": { it == null || it instanceof String }]
    } else {
      Collections.emptyMap()
    }
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path, "servlet.context": "/"]
  }

  @Override
  int spanCount(ServerEndpoint endpoint) {
    if (endpoint == ERROR) {
      // Jetty: [servlet.request[controller[servlet.response[spring.handler]]]]
      if (getContainerType() == JETTY) {
        return super.spanCount(endpoint) + 1
      }
      // Tomcat: [servlet.request[servlet.forward[spring.handler]][controller[servlet.response]]]
      // adds servlet.forward/GET /error and spring.handler/BasicErrorController.error
      return super.spanCount(endpoint) + 2
    } else if (endpoint == EXCEPTION) {
      // Jetty: [servlet.request[servlet.response[spring.handler]][controller]]
      if (getContainerType() == JETTY) {
        // jetty doesn't have servlet.forward
        return super.spanCount(endpoint) + 1
      }
      // adds servlet.forward/GET /error and spring.handler/BasicErrorController.error
      // removes servlet.response/HttpServletResponse
      return super.spanCount(endpoint) + 2
    }
    return super.spanCount(endpoint)
  }

  @Override
  String expectedResourceName(ServerEndpoint endpoint, String method, URI address) {
    if (endpoint.status == 404 && endpoint.path == "/not-found") {
      return "404"
    } else if (endpoint.hasPathParam) {
      return "$method ${testPathParam()}"
    } else if (endpoint.errored) {
      return "$method /error"
    }
    return "$method ${endpoint.resolve(address).path}"
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    if (endpoint.errored) {
      return "/error"
    }
    return null
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
