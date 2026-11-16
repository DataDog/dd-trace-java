package test.normalizedroute

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.config.AppSecConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext
import spock.lang.Shared
import test.boot.SecurityConfig

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG

/** With API Security disabled the normalized route is not set; {@code http.route} still is. */
class NormalizedRouteApiSecurityDisabledForkedTest extends InstrumentationSpecification {

  @Shared
  EmbeddedWebApplicationContext context

  @Shared
  OkHttpClient client = OkHttpUtils.client()

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(AppSecConfig.API_SECURITY_ENABLED, 'false')
  }

  def setupSpec() {
    def app = new SpringApplication(NormalizedRouteAppConfig, SecurityConfig)
    app.setDefaultProperties(['server.port': 0])
    context = app.run() as EmbeddedWebApplicationContext
  }

  def cleanupSpec() {
    context?.close()
  }

  def 'no normalized route when API Security is disabled'() {
    setup:
    def port = context.embeddedServletContainer.port
    def request = new Request.Builder().url("http://localhost:$port/regex/123").get().build()

    when:
    def response = client.newCall(request).execute()
    response.close()
    TEST_WRITER.waitForTraces(1)
    def span = TEST_WRITER.flatten().find { it.operationName.toString() == 'servlet.request' }

    then:
    response.code() == 200
    span != null
    span.getTag(Tags.HTTP_ROUTE) == '/regex/{id:\\d+}'
    span.getTag(NORMALIZED_ROUTE_TAG) == null
  }
}
