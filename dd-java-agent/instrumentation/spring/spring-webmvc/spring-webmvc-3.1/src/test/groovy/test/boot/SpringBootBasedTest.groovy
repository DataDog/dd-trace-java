package test.boot

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.telemetry.Endpoint
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.MediaType
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter
import org.springframework.web.servlet.view.RedirectView
import test.SetupSpecHelper

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.LOGIN
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.MATRIX_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_HERE
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.WEBSOCKET

class SpringBootBasedTest extends HttpServerTest<ConfigurableApplicationContext> {

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  Map<String, String> extraServerTags = [:]

  SpringApplication application() {
    new SpringApplication(AppConfig, SecurityConfig, AuthServerConfig, TestController, WebsocketConfig)
  }

  def setupSpec() {
    SetupSpecHelper.provideBlockResponseFunction()
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  HttpServer server() {
    new SpringBootServer(application(), servletContext)
  }

  @Override
  String component() {
    'tomcat-server'
  }

  String getServletContext() {
    return "boot-context"
  }

  @Override
  boolean testResponseBodyJson() {
    return true
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
  protected boolean enabledFinishTimingChecks() {
    true
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
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBodyJson() {
    true
  }

  @Override
  boolean testBadUrl() {
    false
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testUserBlocking() {
    true
  }

  @Override
  boolean testEndpointDiscovery() {
    true
  }

  @Override
  void assertEndpointDiscovery(final List<?> endpoints) {
    final discovered = endpoints.collectEntries { [(it.method): it] }  as Map<String, Endpoint>
    assert discovered.keySet().containsAll([Endpoint.Method.POST, Endpoint.Method.PATCH, Endpoint.Method.PUT])
    discovered.values().each {
      assert it.requestBodyType.containsAll([MediaType.APPLICATION_JSON_VALUE])
      assert it.responseBodyType.containsAll([MediaType.TEXT_PLAIN_VALUE])
      assert it.metadata['handler'] == 'public org.springframework.http.ResponseEntity test.boot.TestController.discovery()'
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

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path, "servlet.context": "/$servletContext"] + extraServerTags
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
      super.spanCount(endpoint) + 1
    } else if (endpoint == NOT_FOUND) {
      super.spanCount(endpoint) + 2
    } else {
      super.spanCount(endpoint)
    }
  }

  @Override
  String testPathParam() {
    "/path/{id}/param"
  }

  private EmbeddedWebApplicationContext getContext() {
    this.server.context
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
    DDSpan span = TEST_WRITER.flatten().find { it.operationName == 'appsec-span' }

    then:
    response.code() == PATH_PARAM.status
    span.getTag(IG_PATH_PARAMS_TAG) == [id: '123']
  }

  void 'tainting on template var'() {
    setup:
    PropagationModule mod = Mock()
    InstrumentationBridge.registerIastModule(mod)
    Request request = this.request(PATH_PARAM, 'GET', null).build()

    when:
    Response response = client.newCall(request).execute()
    response.code() == PATH_PARAM.status
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    1 * mod.taintString(_ as IastContext, '123', SourceTypes.REQUEST_PATH_PARAMETER, 'id')
    0 * mod._

    cleanup:
    InstrumentationBridge.clearIastModules()
  }

  def 'matrix var is pushed to IG'() {
    setup:
    def request = request(MATRIX_PARAM, 'GET', null)
      .header(IG_EXTRA_SPAN_NAME_HEADER, 'appsec-span').build()

    when:
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(2)
    DDSpan span = TEST_WRITER.flatten().find { it.operationName == 'appsec-span' }

    then:
    response.code() == MATRIX_PARAM.status
    response.body().string() == MATRIX_PARAM.body
    span.getTag(IG_PATH_PARAMS_TAG) == [var: ['a=x,y;a=z', [a: ['x', 'y', 'z']]]]
  }

  void 'tainting on matrix var'() {
    setup:
    PropagationModule mod = Mock()
    InstrumentationBridge.registerIastModule(mod)
    Request request = this.request(MATRIX_PARAM, 'GET', null).build()

    when:
    Response response = client.newCall(request).execute()
    response.code() == MATRIX_PARAM.status
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    1 * mod.taintString(_ as IastContext, 'a=x,y;a=z', SourceTypes.REQUEST_PATH_PARAMETER, 'var')
    1 * mod.taintString(_ as IastContext, 'a', SourceTypes.REQUEST_MATRIX_PARAMETER, 'var')
    1 * mod.taintString(_ as IastContext, 'x', SourceTypes.REQUEST_MATRIX_PARAMETER, 'var')
    1 * mod.taintString(_ as IastContext, 'y', SourceTypes.REQUEST_MATRIX_PARAMETER, 'var')
    1 * mod.taintString(_ as IastContext, 'z', SourceTypes.REQUEST_MATRIX_PARAMETER, 'var')
    0 * mod._

    cleanup:
    InstrumentationBridge.clearIastModules()
  }

  def 'path is extract when preHandle fails'() {
    setup:
    def request = request(PATH_PARAM, 'GET', null).header("fail", "true").build()
    context.getBeanFactory().registerSingleton("testHandler", new HandlerInterceptorAdapter() {
        @Override
        boolean preHandle(HttpServletRequest req, HttpServletResponse response, Object handler) throws Exception {
          if ("true".equalsIgnoreCase(req.getHeader("fail"))) {
            throw new RuntimeException("Stop here")
          }
          return true
        }
      })

    when:
    client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER.flatten().find { "servlet.request".contentEquals(it.operationName) }

    then:
    span.getResourceName().toString() == "GET " + testPathParam()
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == NOT_FOUND || endpoint == LOGIN || endpoint == FORWARDED
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
    }  else if (endpoint == FORWARDED) {
      trace.span {
        serviceName expectedServiceName()
        operationName 'servlet.dispatch'
        resourceName 'servlet.dispatch'
        tags {
          "$Tags.COMPONENT" 'java-web-servlet-async-dispatcher'
          'servlet.context' "/$servletContext"
          'servlet.path' '/forwarded'
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
        it == "TestController.${endpoint.name().toLowerCase()}"
        || endpoint == NOT_FOUND && it == "ResourceHttpRequestHandler.handleRequest"
        || endpoint == WEBSOCKET && it == "WebSocketHttpRequestHandler.handleRequest"
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


  protected void trailingSpans(TraceAssert traceAssert, ServerEndpoint serverEndpoint) {
    if (serverEndpoint == NOT_FOUND) {
      traceAssert.with {
        span {
          spanType 'web'
          serviceName expectedServiceName()
          operationName 'servlet.forward'
          resourceName 'GET /error'
          tags {
            "$Tags.COMPONENT" 'java-web-servlet-dispatcher'
            "$Tags.HTTP_ROUTE" '/error'
            'servlet.context' "/$servletContext"
            'servlet.path' '/not-found'
            "$DDTags.PATHWAY_HASH" String
            defaultTags()
          }
        }
        span {
          spanType 'web'
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
