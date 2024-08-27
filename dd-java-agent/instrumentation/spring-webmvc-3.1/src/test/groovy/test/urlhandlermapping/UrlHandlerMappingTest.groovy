package test.urlhandlermapping

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator
import okhttp3.Request
import okhttp3.Response
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import test.SetupSpecHelper
import test.boot.SecurityConfig

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.LOGIN
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static java.util.Collections.singletonMap

/**
 * Test instrumentation of UriTemplateVariablesHandlerInterceptor
 * (used by AbstractUrlHandlerMapping).
 */
class UrlHandlerMappingTest extends HttpServerTest<ConfigurableApplicationContext> {

  class SpringBootServer implements HttpServer {
    def port = 0
    def context
    final app = new SpringApplication(UrlHandlerMappingAppConfig, SecurityConfig)

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
      new URI("http://localhost:$port/")
    }

    @Override
    String toString() {
      this.class.name
    }
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  HttpServer server() {
    new SpringBootServer()
  }

  @Override
  String component() {
    'tomcat-server'
  }

  @Override
  String expectedOperationName() {
    'servlet.request'
  }

  def setupSpec() {
    SetupSpecHelper.provideBlockResponseFunction()
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean testException() {
    // generates extra trace for the error handling invocation
    false
  }

  @Override
  boolean testRedirect() {
    // generates extra trace at the end
    false
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
  boolean testBadUrl() {
    false
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testUserBlocking() {
    false
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
        return null
    }
  }

  @Override
  String testPathParam() {
    '/path/{id:\\d+}/param'
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ['servlet.path': endpoint.path, 'servlet.context': '/']
  }

  @Override
  String expectedServiceName() {
    'root-servlet'
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "spring.handler"
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

  void 'tainting on template var'() {
    setup:
    final mod = Mock(PropagationModule)
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
}
