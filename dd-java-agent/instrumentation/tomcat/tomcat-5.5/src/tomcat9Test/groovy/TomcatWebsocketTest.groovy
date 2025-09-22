import spock.lang.IgnoreIf

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.WEBSOCKET

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDTags
import datadog.trace.instrumentation.servlet3.TestServlet3
import datadog.trace.instrumentation.tomcat.TomcatDecorator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.apache.catalina.Context
import org.apache.catalina.Wrapper
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.valves.ErrorReportValve
import org.apache.tomcat.util.descriptor.web.FilterDef
import org.apache.tomcat.util.descriptor.web.FilterMap

import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.FilterConfig
import javax.servlet.Servlet
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper
import javax.websocket.server.ServerContainer
import javax.websocket.server.ServerEndpointConfig
import java.security.Principal

abstract class TomcatWebsocketTest extends HttpServerTest<Tomcat> {

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
    false
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
    false
  }

  @Override
  boolean testBlockingOnResponse() {
    false
  }

  @Override
  boolean testSessionId() {
    true
  }

  @Override
  boolean testEncodedPath() {
    false
  }

  @Override
  boolean testEncodedQuery() {
    false
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    def responseSpans = [REDIRECT, NOT_FOUND, ERROR]
    return responseSpans.contains(endpoint)
  }

  @Override
  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    switch (endpoint) {
      case REDIRECT:
        trace.span {
          operationName "servlet.response"
          resourceName "HttpServletResponse.sendRedirect"
          childOfPrevious()
          tags {
            "component" "java-web-servlet-response"
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags()
          }
        }
        break
      case ERROR:
      case NOT_FOUND:
        trace.span {
          operationName "servlet.response"
          resourceName "HttpServletResponse.sendError"
          childOfPrevious()
          tags {
            "component" "java-web-servlet-response"
            defaultTags()
          }
        }
        break
      default:
        throw new UnsupportedOperationException("responseSpan not implemented for " + endpoint)
    }
  }

  @Override
  URI buildAddress(int port) {
    return new URI("http://localhost:$port/$context/")
  }

  @Override
  String component() {
    return TomcatDecorator.DECORATE.component()
  }

  @Override
  String expectedServiceName() {
    context
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  boolean hasHandlerSpan() {
    return false
  }


  @Override
  OkHttpClient getClient() {
    return super.getClient().newBuilder()
      .addInterceptor(new Interceptor() {
        @Override
        okhttp3.Response intercept(Interceptor.Chain chain) throws IOException {
          return chain.proceed(chain.request().newBuilder()
            .header("Cookie", "somethingcouldbreaktherequest=" + UUID.randomUUID())

            .build())
        }
      }).build()
  }

  static class SecurityFilter implements Filter {

    @Override
    void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
      chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest) request) {
          @Override
          Principal getUserPrincipal() {
            return new Principal() {
                @Override
                String getName() {
                  return "superadmin"
                }
              }
          }
        }, response)
    }

    @Override
    void destroy() {
    }
  }

  protected void setupServlets(Context context) {
    ServerEndpoint.values().findAll { it != NOT_FOUND && it != UNKNOWN }.each {
      addServlet(context, it.path, TestServlet3.Sync)
    }
    addFilter(context, "/*", SecurityFilter)
  }

  /**
   * Use async send
   * @return
   */
  protected boolean isWebsocketAsyncSend() {
    false
  }

  protected abstract void setupWebsockets(ServerContainer serverContainer)


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
    Map<String, Serializable> map = ["servlet.path": endpoint.path]
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
    new TomcatServer(context, this.&setupServlets, this.&setupWebsockets, isWebsocketAsyncSend())
  }

  static String getContext() {
    return "tomcat-context"
  }

  static void addServlet(Context servletContext, String path, Class<Servlet> servlet) {
    Wrapper wrapper = servletContext.createWrapper()
    wrapper.name = UUID.randomUUID()
    wrapper.servletClass = servlet.name
    wrapper.asyncSupported = true
    servletContext.addChild(wrapper)
    servletContext.addServletMappingDecoded(path, wrapper.name)
  }

  static void addFilter(Context context, String path, Class<Filter> filter) {
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


class TomcatWebsocketSyncPartialTest extends TomcatWebsocketTest {
  @Override
  protected void setupWebsockets(ServerContainer serverContainer) {
    serverContainer.addEndpoint(ServerEndpointConfig.Builder.create(WsEndpoint.StdPartialEndpoint, WEBSOCKET.path).build())
  }
}

class TomcatWebsocketAsyncPojoTest extends TomcatWebsocketTest {
  @Override
  protected boolean isWebsocketAsyncSend() {
    true
  }

  @Override
  protected void setupWebsockets(ServerContainer serverContainer) {
    serverContainer.addEndpoint(ServerEndpointConfig.Builder.create(WsEndpoint.PojoEndpoint, WEBSOCKET.path).build())
  }
}





