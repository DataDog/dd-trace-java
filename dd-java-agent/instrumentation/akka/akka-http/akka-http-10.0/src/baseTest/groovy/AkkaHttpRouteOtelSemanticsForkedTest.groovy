import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.naming.TestingOtelHttpSemantics
import okhttp3.OkHttpClient
import okhttp3.Request
import spock.lang.Shared

class AkkaHttpRouteOtelSemanticsForkedTest extends InstrumentationSpecification implements TestingOtelHttpSemantics {
  @Shared
  def server = new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandle())

  @Shared
  def client = new OkHttpClient()

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.otel.semantics.enabled", "true")
  }

  def setupSpec() {
    server.start()
  }

  def cleanupSpec() {
    server.stop()
    client.connectionPool().evictAll()
    client.dispatcher().executorService().shutdown()
  }

  def "nested path matchers produce a stable server route across parameter values"() {
    when:
    [1, 2].each { id ->
      def response = client.newCall(new Request.Builder().url(server.address().resolve("/injected-id/ping/$id").toString()).build()).execute()
      assert response.code() == 200
      response.close()
    }
    TEST_WRITER.waitForTraces(2)

    then:
    def servers = TEST_WRITER.flatten().findAll { it.getTag('component')?.toString() == 'akka-http-server' }
    servers.size() == 2
    servers.every { it.getTag('http.route') == '/injected-id/ping/{param}' }
    servers.every { it.resourceName.toString() == 'GET /injected-id/ping/{param}' }
  }
}
