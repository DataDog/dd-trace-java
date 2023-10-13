package test.dynamic

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
import org.springframework.web.servlet.view.RedirectView
import org.springframework.web.util.NestedServletException
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
    'root-servlet'
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
  boolean changesAll404s() {
    true
  }

  @Override
  boolean testBadUrl() {
    false
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path, 'servlet.context': '/']
  }

  int spanCount(ServerEndpoint endpoint) {
    if (endpoint == REDIRECT) {
      // Spring is generates a RenderView and ResponseSpan for REDIRECT
      super.spanCount(endpoint) + 1
    }  else if (endpoint == EXCEPTION) {
      super.spanCount(endpoint) +2
    } else {
      super.spanCount(endpoint)
    }
  }

  @Override
  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT
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

  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint == EXCEPTION) {
      ["error.message"  : 'Request processing failed; nested exception is java.lang.Exception: controller exception',
        "error.type" : NestedServletException.name,
        "error.stack": String]
    } else {
      super.expectedExtraErrorInformation(endpoint)
    }
  }

  protected void trailingSpans(TraceAssert traceAssert, ServerEndpoint serverEndpoint) {
    if (serverEndpoint == EXCEPTION) {
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
