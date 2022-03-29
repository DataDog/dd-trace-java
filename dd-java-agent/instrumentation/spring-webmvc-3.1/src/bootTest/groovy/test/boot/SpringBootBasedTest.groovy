package test.boot

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.jetty9.JettyDecorator
import datadog.trace.instrumentation.servlet3.Servlet3Decorator
import datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator
import datadog.trace.instrumentation.tomcat.TomcatDecorator
import okhttp3.FormBody
import okhttp3.RequestBody
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext
import org.springframework.boot.context.embedded.jetty.JettyEmbeddedServletContainer
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainer
import org.springframework.boot.context.embedded.undertow.UndertowEmbeddedServletContainer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.servlet.view.RedirectView
import spock.lang.Shared

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.LOGIN
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.MATRIX_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_HERE
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class SpringBootBasedTest extends HttpServerTest<ConfigurableApplicationContext> {

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Shared
  EmbeddedWebApplicationContext context

  Map<String, String> extraServerTags = [:]

  SpringApplication application() {
    return new SpringApplication(AppConfig, SecurityConfig, AuthServerConfig, TestController)
  }

  class SpringBootServer implements HttpServer {
    def port = 0
    final app = application()

    SpringBootServer() {
      app.setDefaultProperties(["server.port": 0, "server.context-path": "/$servletContext"])
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
      return new URI("http://localhost:$port/$servletContext${context.environment.getProperty("server.servlet-path", "")}/")
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
    if (context.getEmbeddedServletContainer() instanceof TomcatEmbeddedServletContainer) {
      return "tomcat-server"
    } else if (context.getEmbeddedServletContainer() instanceof JettyEmbeddedServletContainer) {
      return "jetty-server"
    }
    return "java-web-servlet"
  }

  String getServletContext() {
    return "spring-context"
  }

  @Override
  String expectedServiceName() {
    servletContext
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
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyJson() {
    true
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

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path, "servlet.context": "/$servletContext"] +
    extraServerTags
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

  int spanCount(ServerEndpoint endpoint) {
    if (endpoint == REDIRECT) {
      // Spring is generates a RenderView and ResponseSpan for REDIRECT
      return super.spanCount(endpoint) + 1
    } else if (endpoint == NOT_FOUND) {
      return super.spanCount(endpoint) + 2
    }
    return super.spanCount(endpoint)
  }

  @Override
  String testPathParam() {
    "/path/{id}/param"
  }

  def "test character encoding of #testPassword"() {
    setup:
    def authProvider = context.getBean(SavingAuthenticationProvider)
    extraServerTags = ['request.body.converted': [username: ['test'], password: [testPassword]] as String]

    RequestBody formBody = new FormBody.Builder()
      .add("username", "test")
      .add("password", testPassword).build()

    def request = request(LOGIN, "POST", formBody).build()

    when:
    authProvider.latestAuthentications.clear()
    def response = client.newCall(request).execute()

    then:
    response.code() == 302 // redirect after success
    authProvider.latestAuthentications.get(0).password == testPassword

    and:
    assertTraces(1) {
      trace(2) {
        serverSpan(it, null, null, "POST", LOGIN)
        responseSpan(it, LOGIN)
      }
    }

    where:
    testPassword << ["password", "dfsdföääöüüä", "🤓"]
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
        handlerSpan(it, NOT_HERE)
        controllerSpan(it)
      }
    }

    where:
    method = "GET"
    body = null
  }

  def 'template var is pushed to IG'() {
    setup:
    def request = request(PATH_PARAM, 'GET', null).header(IG_EXTRA_SPAN_NAME_HEADER, 'appsec-span').build()

    when:
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(2)
    DDSpan span = TEST_WRITER.flatten().find {it.operationName =='appsec-span' }

    then:
    response.code() == PATH_PARAM.status
    span.getTag(IG_PATH_PARAMS_TAG) == [id: '123']
  }

  def 'matrix var is pushed to IG'() {
    setup:
    def request = request(MATRIX_PARAM, 'GET', null)
      .header(IG_EXTRA_SPAN_NAME_HEADER, 'appsec-span').build()

    when:
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(2)
    DDSpan span = TEST_WRITER.flatten().find {it.operationName =='appsec-span' }

    then:
    response.code() == MATRIX_PARAM.status
    response.body().string() == MATRIX_PARAM.body
    span.getTag(IG_PATH_PARAMS_TAG) == [var:['a=x,y;a=z', [a:['x', 'y', 'z']]]]
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == NOT_FOUND || endpoint == LOGIN
  }

  @Override
  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    if (endpoint == LOGIN) {
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
      def extraTags = expectedExtraServerTags(NOT_FOUND)
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
