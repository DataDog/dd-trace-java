package test.normalizedroute

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext
import spock.lang.Shared
import test.boot.SecurityConfig

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG

/** Normalized route for annotated AntPathMatcher routes, with and without a mount prefix. */
abstract class AbstractNormalizedRouteTest extends InstrumentationSpecification {

  @Shared
  EmbeddedWebApplicationContext context

  @Shared
  OkHttpClient client = OkHttpUtils.client()

  /** Servlet context path, empty for the root context. */
  abstract String contextPath()

  /** DispatcherServlet path mapping ({@code /api} for {@code /api/*}), empty for {@code /}. */
  abstract String servletPath()

  def setupSpec() {
    def app = new SpringApplication(NormalizedRouteAppConfig, SecurityConfig)
    Map<String, Object> props = ['server.port': 0]
    if (contextPath()) {
      props['server.context-path'] = contextPath()
    }
    if (servletPath()) {
      props['server.servlet-path'] = servletPath()
    }
    app.setDefaultProperties(props)
    context = app.run() as EmbeddedWebApplicationContext
  }

  def cleanupSpec() {
    context?.close()
  }

  def 'normalized route for #path'() {
    setup:
    def prefix = contextPath() + servletPath()
    def port = context.embeddedServletContainer.port
    def request = new Request.Builder().url("http://localhost:$port$prefix$path").get().build()

    when:
    def response = client.newCall(request).execute()
    response.close()
    TEST_WRITER.waitForTraces(1)
    def span = TEST_WRITER.flatten().find { it.operationName.toString() == 'servlet.request' }

    then:
    response.code() == 200
    span != null
    span.getTag(Tags.HTTP_ROUTE) == route
    span.getTag(NORMALIZED_ROUTE_TAG) == prefix + normalized

    where:
    path           | route                     | normalized
    '/regex/123'   | '/regex/{id:\\d+}'        | '/regex/{id}'
    '/nested/123'  | '/nested/{code:[0-9]{3}}' | '/nested/{code}'
    '/files/a.txt' | '/files/*.txt'            | '/files/{param1}.txt'
    '/static/a/b'  | '/static/**'              | '/static/{param1}'
    // ** matched nothing: removed with its "/"; matched only "/": the trailing "/" is kept
    '/static'      | '/static/**'              | '/static'
    '/static/'     | '/static/**'              | '/static/'
    '/mixed/1/x/y' | '/mixed/{id}/**'          | '/mixed/{id}/{param1}'
    '/mixed/1'     | '/mixed/{id}/**'          | '/mixed/{id}'
    '/mixed/1/'    | '/mixed/{id}/**'          | '/mixed/{id}/'
    // a wildcard before ** shifts Spring's remainder: presence of ** is not known (fallback)
    '/star/a/b'    | '/star/*/**'              | '/star/{param1}'
    // presence of a mid-path ** is not known: the part is removed (RFC-1125 fallback)
    '/mid/x/y/end' | '/mid/**/end'             | '/mid/end'
  }
}

/** Root context, DispatcherServlet mapped to {@code /}. */
class NormalizedRouteTest extends AbstractNormalizedRouteTest {
  @Override
  String contextPath() {
    ''
  }

  @Override
  String servletPath() {
    ''
  }
}

/** Context path plus a path-mapped DispatcherServlet ({@code /api/*}): both are in the prefix. */
class NormalizedRouteServletPathTest extends AbstractNormalizedRouteTest {
  @Override
  String contextPath() {
    '/ctx'
  }

  @Override
  String servletPath() {
    '/api'
  }
}
