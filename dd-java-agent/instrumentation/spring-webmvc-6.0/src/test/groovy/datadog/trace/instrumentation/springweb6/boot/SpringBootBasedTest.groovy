package datadog.trace.instrumentation.springweb6.boot

import datadog.trace.api.telemetry.Endpoint
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.WebsocketServer
import datadog.trace.api.ConfigDefaults
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.springweb6.SetupSpecHelper
import datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.springframework.beans.BeansException
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.MediaType
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import spock.lang.Shared

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.LOGIN
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.MATRIX_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_HERE
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SECURE_SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.WEBSOCKET

class SpringBootBasedTest extends HttpServerTest<ConfigurableApplicationContext> {

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Shared
  def context

  Map<String, String> extraServerTags = [:]

  SpringApplication application() {
    return new SpringApplication(SecurityConfig, TestController, AppConfig, WebsocketConfig)
  }

  class SpringBootServer implements WebsocketServer {
    def port = 0
    final app = application()
    WebsocketEndpoint endpoint


    @Override
    void start() {
      app.setDefaultProperties(["server.port": 0, "server.context-path": "/$servletContext",
        "spring.mvc.throw-exception-if-no-handler-found": false,
        "spring.web.resources.add-mappings"             : false,
        "server.forward-headers-strategy": "NONE"])
      context = app.run()
      port = (context as ServletWebServerApplicationContext).webServer.port
      try {
        endpoint = context.getBean(WebsocketEndpoint)
      } catch (BeansException ignored) {
        // silently ignore since not all the tests are deploying this endpoint
      }
      assert port > 0
    }

    @Override
    void stop() {
      context.close()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/$servletContext/")
    }

    @Override
    String toString() {
      return this.class.name
    }

    @Override
    void serverSendText(String[] messages) {
      WebSocketSession session = endpoint?.activeSession
      if (session != null) {
        if (messages.size() == 1) {
          session.sendMessage(new TextMessage(messages[0]))
        } else {
          for (def i = 0; i < messages.size(); i++) {
            session.sendMessage(new TextMessage(messages[i], i == messages.size() - 1))
          }
        }
      }
    }

    @Override
    void serverSendBinary(byte[][] binaries) {
      WebSocketSession session = endpoint?.activeSession
      if (session != null) {
        if (binaries.length == 1) {
          session.sendMessage(new BinaryMessage(binaries[0]))
        } else {
          for (def i = 0; i < binaries.length; i++) {
            session.sendMessage(new BinaryMessage(binaries[i], i == binaries.length - 1))
          }
        }
      }
    }

    @Override
    void serverClose() {
      endpoint?.activeSession?.close()
    }

    @Override
    synchronized void awaitConnected() {
      synchronized (WebsocketEndpoint) {
        try {
          while (endpoint?.activeSession == null) {
            WebsocketEndpoint.wait()
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt()
        }
      }
    }

    @Override
    void setMaxPayloadSize(int size) {
      endpoint?.activeSession?.setBinaryMessageSizeLimit(size)
      endpoint?.activeSession?.setTextMessageSizeLimit(size)
    }
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
    return new SpringBootServer()
  }

  @Override
  String component() {
    'tomcat-server'
  }

  String getServletContext() {
    return ""
  }

  @Override
  String expectedServiceName() {
    ConfigDefaults.DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME
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
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    // latest DispatcherServlet throws if no handlers have been found
    if (endpoint == NOT_FOUND && isLatestDepTest) {
      return [(DDTags.ERROR_STACK): { String },
        (DDTags.ERROR_MSG)  : { String },
        (DDTags.ERROR_TYPE) : { String }]
    }
    return super.expectedExtraErrorInformation(endpoint)
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
      //not found is handled by the application server in this test case
      return super.spanCount(endpoint) - 1
    }
    return super.spanCount(endpoint)
  }

  @Override
  String testPathParam() {
    "/path/{id}/param"
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
      assert it.metadata['handler'] == 'datadog.trace.instrumentation.springweb6.boot.TestController#discovery()'
    }
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
      trace(1) {
        serverSpan(it, null, null, "POST", LOGIN)
        //FIXME uncomment when jakarta servlet will be instrumented
        //responseSpan(it, LOGIN)
      }
    }

    where:
    testPassword << ["password", "dfsdföääöüüä", "🤓"]
  }

  def "test authenticated request"() {
    setup:
    injectSysConfig("trace.servlet.principal.enabled", "true")

    def request = request(SECURE_SUCCESS, "GET", null)
      .header("Authorization", Credentials.basic("test", "password"))
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200

    and:
    assertTraces(1) {
      trace(3) {
        serverSpan(it, null, null, "GET", SECURE_SUCCESS, ["user.principal": "test"])
        handlerSpan(it, SECURE_SUCCESS)
        controllerSpan(it)
      }
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
    // spring-security filter causes uri matching to happen twice
    (1.._) * mod.taintString(_ as IastContext, '123', SourceTypes.REQUEST_PATH_PARAMETER, 'id')
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
    //FIXME: tomcat seems removing the part after the ';' on the decodedUri
    // should be  [var:['a=x,y;a=z'
    span.getTag(IG_PATH_PARAMS_TAG) == [var: ['a=x,y', [a: ['x', 'y', 'z']]]]
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
    // spring-security filter (AuthorizationFilter.java:95) causes uri matching to happen twice (or three times in recent spring (6.1+) versions)
    (1.._) * mod.taintString(_ as IastContext, 'a=x,y', SourceTypes.REQUEST_PATH_PARAMETER, 'var') // this version of spring removes ;a=z
    (1.._) * mod.taintString(_ as IastContext, 'a', SourceTypes.REQUEST_MATRIX_PARAMETER, 'var')
    (1.._) * mod.taintString(_ as IastContext, 'x', SourceTypes.REQUEST_MATRIX_PARAMETER, 'var')
    (1.._) * mod.taintString(_ as IastContext, 'y', SourceTypes.REQUEST_MATRIX_PARAMETER, 'var')
    (1.._) * mod.taintString(_ as IastContext, 'z', SourceTypes.REQUEST_MATRIX_PARAMETER, 'var')
    0 * mod._

    cleanup:
    InstrumentationBridge.clearIastModules()
  }

  def 'path is extract when preHandle fails'() {
    setup:
    def request = request(PATH_PARAM, 'GET', null).header("fail", "true").build()
    context.getBeanFactory().registerSingleton("testHandler", new HandlerInterceptor() {
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
    // FIXME: uncomment when jakarta servlet will be instrumented
    //  endpoint == REDIRECT || endpoint == NOT_FOUND || endpoint == LOGIN
    false
  }

  // FIXME: remove me when jakarta servlet instrumentation will be implemented
  @Override
  boolean testRedirect() {
    false
  }

  @Override
  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
  }


  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    if (endpoint == NOT_FOUND) {
      return
    }
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
}
