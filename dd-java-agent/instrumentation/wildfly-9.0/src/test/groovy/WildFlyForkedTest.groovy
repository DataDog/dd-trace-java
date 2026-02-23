import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.HttpUrl
import okhttp3.Request
import org.jboss.shrinkwrap.api.ShrinkWrap
import org.jboss.shrinkwrap.api.spec.WebArchive
import spock.lang.IgnoreIf
import test.JakartaTestServlet
import test.TestServlet

@IgnoreIf(reason = "WildFly does not guarantee support for Java SE 24. The latest version 36 of WildFly recommends using the latest Java LTS: https://docs.wildfly.org/36/Getting_Started_Guide.html#requirements", value = {
  JavaVirtualMachine.isJavaVersionAtLeast(22)
})
class WildFlyForkedTest extends WithHttpServer<EmbeddedWildfly> implements TestingGenericHttpNamingConventions.ServerV0 {
  @Override
  EmbeddedWildfly startServer(int port) {
    // create the archive
    def war = ShrinkWrap.create(WebArchive)
    war.setWebXML(getClass().getResource("/WEB-INF/web.xml"))
      .addClass(TestServlet) // for wildfly 21 (EE 8)
      .addClass(JakartaTestServlet) // for latestDep

    def server = new EmbeddedWildfly(System.getProperty("test.jboss.home"), port)
    server.start()
    server.deploy(war)
    server
  }

  @Override
  void stopServer(EmbeddedWildfly embeddedWildfly) {
    embeddedWildfly?.stop()
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // otherwise there are differences in setting the resource name across wildfly versions
    injectSysConfig("undertow.legacy.tracing.enabled", "false")
  }

  def "should have service name and tags set"() {
    setup:
    TEST_WRITER.clear()
    when:
    def request = new Request.Builder().url(HttpUrl.get(server.address()).resolve("/test/hello")).build()
    def call = OkHttpUtils.client().newCall(request)
    def response = call.execute()
    then:
    assert response.code() == 200
    assertTraces(1, {
      trace(1) {
        span {
          serviceName "custom-service"
          operationName "servlet.request"
          resourceName "GET /test/hello"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          parent()

          tags {
            "$Tags.COMPONENT" "undertow-http-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_PORT" Integer
            "$Tags.PEER_HOST_IPV4" { String }
            "$Tags.HTTP_CLIENT_IP" { String }
            "$Tags.HTTP_HOSTNAME" address.host
            "$Tags.HTTP_URL" { String }
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_ROUTE" { true }
            "$InstrumentationTags.SERVLET_CONTEXT" "/test"
            "$InstrumentationTags.SERVLET_PATH" "/hello"
            "custom-metric" 1983
            // the service name is set as tag - no source expected right now
            serviceNameSource null
            defaultTags(true)
          }
        }
      }
    })
  }
}
