import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.servlet3.Servlet3Decorator
import datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.servlet.view.RedirectView
import spock.lang.Ignore

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static java.util.Collections.singletonMap

@Ignore("Does not assert servlet.context tag - https://github.com/DataDog/dd-trace-java/pull/5213")
class SpringBootZuulTest extends HttpServerTest<ConfigurableApplicationContext> {

  class SpringBootServer implements HttpServer {
    def port = 0
    def context
    def app = new SpringApplication(AppConfig, TestController)

    @Override
    void start() {
      app.setDefaultProperties(singletonMap("server.port", 0))
      context = app.run() as ServletWebServerApplicationContext
      port = context.webServer.port
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
    return Servlet3Decorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  boolean testNotFound() {
    false // Zuul forwards to the error handler instead
  }

  //@Ignore("https://github.com/DataDog/dd-trace-java/pull/5213")
  @Override
  boolean testBadUrl() {
    false
  }

  int spanCount(ServerEndpoint endpoint) {
    if (endpoint == REDIRECT) {
      // Spring is generates a RenderView and ResponseSpan for REDIRECT
      return super.spanCount(endpoint) + 3
    }
    return super.spanCount(endpoint) + 2
  }

  @Override
  String testPathParam() {
    "/path/{id}/param"
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == NOT_FOUND
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
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

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    switch (endpoint) {
      case PATH_PARAM:
        return testPathParam()
      default:
        return endpoint.path
    }
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path]
  }

  @Override
  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    if (endpoint == NOT_FOUND) {
      trace.span {
        operationName "servlet.response"
        resourceName "HttpServletResponse.sendError"
        childOfPrevious()
        tags {
          "component" "java-web-servlet-response"
          defaultTags()
        }
      }
    } else if (endpoint == REDIRECT) {
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
      resourceName "ZuulController.handleRequest"
      spanType DDSpanTypes.HTTP_SERVER
      if (endpoint != REDIRECT) {
        childOfPrevious()
      }
      tags {
        "$Tags.COMPONENT" SpringWebHttpServerDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        defaultTags()
      }
    }
    trace.span {
      serviceName expectedServiceName()
      operationName "servlet.forward"
      resourceName expectedResourceName(endpoint, "GET", address)
      spanType DDSpanTypes.HTTP_SERVER
      errored false // never errored since we don't set the http status.
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" "java-web-servlet-dispatcher"
        "servlet.path" endpoint.path
        "$Tags.HTTP_ROUTE" String

        if (endpoint.throwsException) {
          "error.message" EXCEPTION.body
          "error.type" Exception.name
          "error.stack" String
        }
        if ({ isDataStreamsEnabled() }) {
          "$DDTags.PATHWAY_HASH" { String }
        }
        defaultTags()
      }
    }
    trace.span {
      serviceName expectedServiceName()
      operationName "spring.handler"
      resourceName {
        it == "TestController.${endpoint.name().toLowerCase()}" || endpoint == NOT_FOUND && it == "ResourceHttpRequestHandler.handleRequest"
      }
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == EXCEPTION
      if (endpoint != REDIRECT) {
        childOfPrevious()
      }
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
}
