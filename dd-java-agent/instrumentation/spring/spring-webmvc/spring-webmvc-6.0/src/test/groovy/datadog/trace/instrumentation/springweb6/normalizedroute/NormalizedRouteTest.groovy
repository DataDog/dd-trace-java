package datadog.trace.instrumentation.springweb6.normalizedroute

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import spock.lang.Shared

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG

/** Normalized route for PathPatternParser routes under a context path and a servlet path. */
abstract class AbstractNormalizedRouteTest extends InstrumentationSpecification {

  @Shared
  ServletWebServerApplicationContext context

  @Shared
  OkHttpClient client = OkHttpUtils.client()

  /** Servlet context path. */
  String contextPath() {
    '/ctx'
  }

  /** DispatcherServlet path mapping ({@code /api} for {@code /api/*}), empty for {@code /}. */
  abstract String servletPath()

  def setupSpec() {
    def app = new SpringApplication(NormalizedRouteAppConfig)
    Map<String, Object> props = [
      'server.port'                 : 0,
      'server.servlet.context-path' : contextPath(),
      'spring.web.resources.add-mappings': false
    ]
    if (servletPath()) {
      props['spring.mvc.servlet.path'] = servletPath()
    }
    app.setDefaultProperties(props)
    context = app.run() as ServletWebServerApplicationContext
  }

  def cleanupSpec() {
    context?.close()
  }

  def 'normalized route for #path'() {
    setup:
    def prefix = contextPath() + servletPath()
    def port = context.webServer.port
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
    '/rest/a/b'    | '/rest/{*rest}'           | '/rest/{rest}'
    '/rest'        | '/rest/{*rest}'           | '/rest'
    '/rest/'       | '/rest/{*rest}'           | '/rest/'
  }
}

/** Non-root context path, DispatcherServlet mapped to {@code /}. */
class NormalizedRouteTest extends AbstractNormalizedRouteTest {
  @Override
  String servletPath() {
    ''
  }
}

/** Non-root context path plus a path-mapped DispatcherServlet ({@code /api/*}). */
class NormalizedRouteServletPathTest extends AbstractNormalizedRouteTest {
  @Override
  String servletPath() {
    '/api'
  }
}
