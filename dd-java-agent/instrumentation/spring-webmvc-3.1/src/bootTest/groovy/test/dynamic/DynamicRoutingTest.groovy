package test.dynamic

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
import org.springframework.web.servlet.view.RedirectView
import test.boot.SecurityConfig

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static java.util.Collections.singletonMap

class DynamicRoutingTest extends HttpServerTest<ConfigurableApplicationContext> {

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  class SpringBootServer implements HttpServer {
    def port = 0
    def context
    final app = new SpringApplication(DynamicRoutingAppConfig, SecurityConfig)

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
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean testNotFound() {
    // Tested by the regular spring boot test
    false
  }

  @Override
  String testPathParam() {
    // this handler mapping scheme does not support path params!
    null
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.errored) {
      ["error.msg"  : { it == null || it == "Request processing failed; nested exception is java.lang.Exception: controller exception" },
        "error.type" : { it == null || it == "org.springframework.web.util.NestedServletException" },
        "error.stack": { it == null || it instanceof String }]
    } else {
      Collections.emptyMap()
    }
  }

  @Override
  boolean changesAll404s() {
    true
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path, "servlet.context": "/"]
  }

  @Override
  int spanCount(ServerEndpoint endpoint) {
    if (endpoint == REDIRECT) {
      // Spring is generates a RenderView and ResponseSpan for REDIRECT
      return super.spanCount(endpoint) + 1
    } else if (endpoint == EXCEPTION) {
      return super.spanCount(endpoint) + 1
    }
    return super.spanCount(endpoint)
  }

  @Override
  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == EXCEPTION
  }

  @Override
  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    if (endpoint == REDIRECT) {
      // Spring creates a RenderView span and the response span is the child the servlet
      // This is not part of the controller hierarchy because rendering happens after the controller
      // method returns

      trace.span {
        operationName "response.render"
        resourceName "response.render"
        spanType "web"
        errored false
        tags {
          "$Tags.COMPONENT" "spring-webmvc"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
          "view.type" RedirectView.simpleName
          defaultTags()
        }
      }
      trace.span {
        operationName "servlet.response"
        resourceName "HttpServletResponse.sendRedirect"
        childOfPrevious()
        tags {
          "component" "java-web-servlet-response"
          defaultTags()
        }
      }
    } else if (endpoint == EXCEPTION) {
      def extraTags = expectedExtraServerTags(EXCEPTION)
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
          "component" "spring-web-controller"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
          defaultTags()
        }
      }
    } else {
      throw new UnsupportedOperationException("responseSpan not implemented for " + endpoint)
    }
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "spring.handler"
      resourceName "TestController.${formatEndpoint(endpoint)}"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" SpringWebHttpServerDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        if (endpoint == EXCEPTION) {
          errorTags(Exception, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }

  def formatEndpoint(HttpServerTest.ServerEndpoint endpoint) {
    if (!endpoint.errored) {
      return endpoint.relativePath().replace(' ', '_')
    }
    String x = endpoint.name().toLowerCase()
    int firstUnderscore = x.indexOf('_')
    return firstUnderscore == -1 ? x : x.substring(0, firstUnderscore)
  }
}
