package test.filter

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import test.boot.SecurityConfig

import javax.servlet.ServletException

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
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
  String component() {
    'tomcat-server'
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean hasHandlerSpan() {
    false
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
  boolean testBadUrl() {
    false
  }

  @Override
  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == ERROR
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  String expectedServiceName() {
    'root-servlet'
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
    ["servlet.path": endpoint.path, 'servlet.context': '/']
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
      default:
        throw new UnsupportedOperationException("responseSpan not implemented for " + endpoint)
    }
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

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "spring.handler"
      resourceName "TestController.${endpoint.name().toLowerCase()}"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" SpringWebHttpServerDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.HTTP_ROUTE" String
        if (endpoint == EXCEPTION) {
          errorTags(Exception, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }


  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint == EXCEPTION) {
      ["error.message"  : 'Filter execution threw an exception',
        "error.type" : ServletException.name,
        "error.stack": String]
    } else {
      super.expectedExtraErrorInformation(endpoint)
    }
  }

  @Override
  int spanCount(ServerEndpoint endpoint) {
    if (endpoint in [ERROR, EXCEPTION]) {
      super.spanCount(endpoint) + 2
    } else {
      super.spanCount(endpoint)
    }
  }

  protected void trailingSpans(TraceAssert traceAssert, ServerEndpoint serverEndpoint) {
    if (serverEndpoint == ERROR || serverEndpoint == EXCEPTION) {
      traceAssert.with {
        span {
          spanType 'web'
          serviceName expectedServiceName()
          operationName 'servlet.forward'
          resourceName 'GET /error'
          tags {
            "$Tags.COMPONENT" 'java-web-servlet-dispatcher'
            "$Tags.HTTP_ROUTE" '/error'
            'servlet.context' '/'
            'servlet.path' serverEndpoint.path
            "$DDTags.PATHWAY_HASH" String
            defaultTags()
          }
        }
        span {
          spanType 'web'
          childOfPrevious()
          serviceName expectedServiceName()
          operationName 'spring.handler'
          resourceName 'BasicErrorController.error'
          tags {
            "$Tags.COMPONENT" 'spring-web-controller'
            "$Tags.SPAN_KIND" 'server'
            defaultTags()
          }
        }
      }
    }
  }
}
