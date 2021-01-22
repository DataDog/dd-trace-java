import datadog.trace.agent.test.AgentTestRunner
import okhttp3.FormBody
import okhttp3.RequestBody
import org.springframework.boot.SpringApplication
import spock.lang.Shared

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.LOGIN

class ProxyRequestHelperTest extends AgentTestRunner {

  public static final SERVER_PORT = 8080
  public static final GATEWAY_PORT = 8090

  def "check if Zuul gateway can run properly"() {
    setup:

    def zuulGateway = new SpringApplication(ZuulGatewayTestApplication)
    def server = new SpringApplication(TestApplication)

    def gatewayProperties = new Properties()
    gatewayProperties.put("ribbon.eureka.enabled",false)
    gatewayProperties.put("server.port", GATEWAY_PORT)
    gatewayProperties.put("zuul.routes.books.url", "http://localhost:" + SERVER_PORT)
    zuulGateway.setDefaultProperties(gatewayProperties)

    def serverProperties = new Properties()
    serverProperties.put("spring.application.name", "book")
    serverProperties.put("server.port", SERVER_PORT)
    server.setDefaultProperties(serverProperties)

//    def client = OkHttpUtils.client()
//    def request = request(LOGIN, "GET", ).build()

    when:
    def serverCtx = server.run()
    def gatewayCtx = zuulGateway.run()

    then:
    assert true

    cleanup:
    serverCtx.close()
    gatewayCtx.close()

  }
}
