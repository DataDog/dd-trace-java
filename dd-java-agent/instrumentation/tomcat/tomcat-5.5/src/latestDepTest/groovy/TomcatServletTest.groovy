import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.api.ProcessTags
import datadog.trace.instrumentation.servlet5.HtmlAsyncRumServlet
import datadog.trace.instrumentation.servlet5.HtmlRumServlet
import datadog.trace.instrumentation.servlet5.TestServlet5
import datadog.trace.instrumentation.servlet5.XmlAsyncRumServlet
import datadog.trace.instrumentation.servlet5.XmlRumServlet
import jakarta.servlet.Filter
import jakarta.servlet.Servlet
import jakarta.servlet.ServletException
import jakarta.websocket.server.ServerContainer
import jakarta.websocket.server.ServerEndpointConfig
import org.apache.catalina.Context
import org.apache.catalina.Wrapper
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.valves.ErrorReportValve
import org.apache.tomcat.util.descriptor.web.ContextEnvironment
import org.apache.tomcat.util.descriptor.web.FilterDef
import org.apache.tomcat.util.descriptor.web.FilterMap
import spock.lang.IgnoreIf

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.WEBSOCKET
import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED

class TomcatServletTest extends AbstractServletTest<Tomcat, Context> {
  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testRequestBodyISVariant() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  boolean testSessionId() {
    true
  }

  boolean testProcessTags() {
    false
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "${testProcessTags()}")
    ProcessTags.reset()
  }

  def cleanupSpec() {
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    ProcessTags.reset()
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.throwsException) {
      // Exception classes get wrapped in ServletException
      ["error.message": { endpoint == EXCEPTION ? "Servlet execution threw an exception" : it == endpoint.body },
        "error.type"   : { it == ServletException.name || it == InputMismatchException.name },
        "error.stack"  : String]
    } else {
      Collections.emptyMap()
    }
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    Map<String, Serializable> map = ["servlet.path": dispatch ? "/dispatch$endpoint.path" : endpoint.path]
    if (context) {
      map.put("servlet.context", "/$context")
    }
    map
  }

  @Override
  boolean expectedErrored(ServerEndpoint endpoint) {
    (endpoint.errored && bubblesResponse()) || [EXCEPTION, CUSTOM_EXCEPTION, TIMEOUT_ERROR].contains(endpoint)
  }

  @Override
  Serializable expectedStatus(ServerEndpoint endpoint) {
    return { !bubblesResponse() || it == endpoint.status }
  }

  @Override
  HttpServer server() {
    new TomcatServer(context, dispatch, this.&setupServlets, this.&setupWebsockets, isWebsocketAsyncSend())
  }

  @Override
  String getContext() {
    return "tomcat-context"
  }

  @Override
  void addServlet(Context servletContext, String path, Class<Servlet> servlet) {
    Wrapper wrapper = servletContext.createWrapper()
    wrapper.name = UUID.randomUUID()
    wrapper.servletClass = servlet.name
    wrapper.asyncSupported = true
    servletContext.addChild(wrapper)
    servletContext.addServletMappingDecoded(path, wrapper.name)
  }

  @Override
  void addFilter(Context context, String path, Class<Filter> filter) {
    def filterDef = new FilterDef()
    def filterMap = new FilterMap()
    filterDef.filterClass = filter.getName()
    filterDef.asyncSupported = true
    filterDef.filterName = UUID.randomUUID()
    filterMap.filterName = filterDef.filterName
    filterMap.addURLPattern(path)
    context.addFilterDef(filterDef)
    context.addFilterMap(filterMap)
  }

  @Override
  Class<Servlet> servlet() {
    TestServlet5
  }

  protected boolean isWebsocketAsyncSend() {
    false
  }

  protected void setupWebsockets(ServerContainer serverContainer) {
    serverContainer.addEndpoint(ServerEndpointConfig.Builder.create(WsEndpoint.StdPartialEndpoint,
      WEBSOCKET.path).build())
  }

  @IgnoreIf({ !instance.testException() })
  def "test exception with custom status"() {
    setup:
    def request = request(CUSTOM_EXCEPTION, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == CUSTOM_EXCEPTION.status
    if (testExceptionBody()) {
      assert response.body().string() == CUSTOM_EXCEPTION.body
    }

    and:
    assertTraces(1) {
      trace(spanCount(CUSTOM_EXCEPTION)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, CUSTOM_EXCEPTION)
        if (hasHandlerSpan()) {
          handlerSpan(it, CUSTOM_EXCEPTION)
        }
        controllerSpan(it, CUSTOM_EXCEPTION)
        if (hasResponseSpan(CUSTOM_EXCEPTION)) {
          responseSpan(it, CUSTOM_EXCEPTION)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test user principal extracted"() {
    setup:
    injectSysConfig("trace.servlet.principal.enabled", "true")
    when:
    def request = request(SUCCESS, "GET", null).build()
    def response = client.newCall(request).execute()
    then:
    response.code() == 200
    and:
    assertTraces(1) {
      trace(2) {
        serverSpan(it, null, null, "GET", SUCCESS, ["user.principal": "superadmin"])
        controllerSpan(it)
      }
    }
  }

  static class ErrorHandlerValve extends ErrorReportValve {
    @Override
    protected void report(Request request, Response response, Throwable t) {
      if (!response.error) {
        return
      }
      try {
        if (t) {
          if (t instanceof ServletException) {
            t = t.rootCause
          }
          if (t instanceof InputMismatchException) {
            response.status = CUSTOM_EXCEPTION.status
          }
          response.reporter.write(t.message)
        } else if (response.message) {
          response.reporter.write(response.message)
        }
      } catch (IOException e) {
        e.printStackTrace()
      }
    }
  }
}

class TomcatServletClassloaderNamingForkedTest extends TomcatServletTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // will not set the service name according to the servlet context value
    injectSysConfig("trace.experimental.jee.split-by-deployment", "true")
  }

  @Override
  protected boolean isWebsocketAsyncSend() {
    true
  }

  @Override
  protected void setupWebsockets(ServerContainer serverContainer) {
    serverContainer.addEndpoint(WsEndpoint.PojoEndpoint)
  }
}

class TomcatServletEnvEntriesTagTest extends TomcatServletTest {
  def addEntry(context, name, value) {
    def envEntry = new ContextEnvironment()
    envEntry.setName(name)
    envEntry.setValue(value)
    envEntry.setType("java.lang.String")
    context.getNamingResources().addEnvironment(envEntry)
  }

  @Override
  protected void setupServlets(Context context) {
    super.setupServlets(context)
    addEntry(context, "datadog/tags/custom-tag", "custom-value")
    addEntry(context, "java:comp/env/datadog/tags/service", "custom-service")
  }

  @Override
  String expectedServiceName() {
    "custom-service"
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    super.expectedExtraServerTags(endpoint) + ["custom-tag": "custom-value", "_dd.svc_src": null] as Map<String, Serializable>
  }

  @Override
  Map<String, Serializable> expectedExtraControllerTags(ServerEndpoint endpoint) {
    super.expectedExtraControllerTags(endpoint) + ["_dd.svc_src": null] as Map<String, Serializable>
  }

  @Override
  boolean testWebsockets() {
    false
  }

  @Override
  boolean testProcessTags() {
    true
  }
}

class TomcatRumInjectionForkedTest extends TomcatServletTest {
  @Override
  boolean testRumInjection() {
    true
  }

  @Override
  protected void setupServlets(Context context) {
    super.setupServlets(context)
    addServlet(context, "/gimme-html", HtmlRumServlet)
    addServlet(context, "/gimme-xml", XmlRumServlet)
  }
}

class TomcatAsyncRumInjectionForkedTest extends TomcatRumInjectionForkedTest {
  @Override
  protected void setupServlets(Context context) {
    super.setupServlets(context)
    addServlet(context, "/gimme-html", HtmlAsyncRumServlet)
    addServlet(context, "/gimme-xml", XmlAsyncRumServlet)
  }
}




