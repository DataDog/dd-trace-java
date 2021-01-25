import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.core.DDSpan
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Shared
import java.util.concurrent.TimeUnit


class ProxyRequestHelperTest extends WithHttpServer<ConfigurableApplicationContext> {

  @Shared
  ConfigurableApplicationContext proxyServer = null

  @Shared
  ServerSocket proxySocket = PortUtils.randomOpenSocket()

  @Shared
  int proxyPort = proxySocket.localPort

  public static final PARENT_ID = 0;
  public static final TRACE_ID = 0;

  @Override
  ConfigurableApplicationContext startServer(int port) {
    def server = new SpringApplication(TestApplication)
    def properties = new Properties()
    properties.put("spring.application.name", "test")
    properties.put("server.port", port)
    server.setDefaultProperties(properties)
    return server.run()
  }

  @Override
  void stopServer(ConfigurableApplicationContext ctx) {
    ctx.close()
  }

  def setupSpec() {
    proxySocket.close()
    PortUtils.waitForPortToClose(proxyPort, 5, TimeUnit.SECONDS)

    def zuulGateway = new SpringApplication(ZuulGatewayTestApplication)
    def gatewayProperties = new Properties()
    gatewayProperties.put("ribbon.eureka.enabled", false)
    gatewayProperties.put("server.port", proxyPort)
    gatewayProperties.put("zuul.routes.test.url", getAddress().toString())
    zuulGateway.setDefaultProperties(gatewayProperties)
    proxyServer = zuulGateway.run()

    PortUtils.waitForPortToOpen(proxyPort, 5, TimeUnit.SECONDS)
  }

  def cleanupSpec() {
    proxyServer.close()
    PortUtils.waitForPortToClose(proxyPort, 5, TimeUnit.SECONDS)
  }

  def "Test propagation for singular call through proxy"() {
    setup:
    def url = new HttpUrl.Builder()
      .scheme("http")
      .host("localhost")
      .port(proxyPort)
      .addPathSegment("test")
      .addPathSegment("available")
      .build()

    def request = new Request.Builder()
      .url(url)
      .method("GET", null)
      .header("x-datadog-trace-id", PARENT_ID.toString())
      .header("x-datadog-parent-id", TRACE_ID.toString())
      .build()

    when:
    Response response = client.newCall(request).execute()

    then:
    assertTraces(2) {
      trace (3) {
        sortSpansByStart()
        //zuul
        serverSpan(it, "servlet.request", DDSpanTypes.HTTP_SERVER)
        serverSpan(it, "spring.handler", DDSpanTypes.HTTP_SERVER)
        serverSpan(it, "http.request", DDSpanTypes.HTTP_CLIENT)
      }
      //spring application
      trace (2) {
        sortSpansByStart()
        serverSpan(it, "servlet.request", DDSpanTypes.HTTP_SERVER, trace(0)[2])
        serverSpan(it, "spring.handler", DDSpanTypes.HTTP_SERVER)
      }
    }
  }

  def "Test span propagation for nested calls through proxy"() {
    setup:
    def url = new HttpUrl.Builder()
      .scheme("http")
      .host("localhost")
      .port(proxyPort)
      .addPathSegment("test")
      .addPathSegment("nested")
      .addPathSegment(proxyPort.toString())
      .build()

    def request = new Request.Builder()
      .url(url)
      .method("GET", null)
      .header("x-datadog-trace-id", PARENT_ID.toString())
      .header("x-datadog-parent-id", TRACE_ID.toString())
      .build()

    when:
    Response response = client.newCall(request).execute()

    then:
    assertTraces(4) {
      trace (3) {
        sortSpansByStart()
        serverSpan(it, "servlet.request", DDSpanTypes.HTTP_SERVER)
        serverSpan(it, "spring.handler", DDSpanTypes.HTTP_SERVER)
        serverSpan(it, "http.request", DDSpanTypes.HTTP_CLIENT)
      }
      trace (3) {
        sortSpansByStart()
        serverSpan(it, "servlet.request", DDSpanTypes.HTTP_SERVER, trace(0)[2])
        serverSpan(it, "spring.handler", DDSpanTypes.HTTP_SERVER)
        serverSpan(it, "http.request", DDSpanTypes.HTTP_CLIENT)
      }
      trace (3) {
        sortSpansByStart()
        serverSpan(it, "servlet.request", DDSpanTypes.HTTP_SERVER, trace(1)[2])
        serverSpan(it, "spring.handler", DDSpanTypes.HTTP_SERVER)
        serverSpan(it, "http.request", DDSpanTypes.HTTP_CLIENT)
      }
      trace (2) {
        sortSpansByStart()
        serverSpan(it, "servlet.request", DDSpanTypes.HTTP_SERVER, trace(2)[2])
        serverSpan(it, "spring.handler", DDSpanTypes.HTTP_SERVER)
      }
    }
  }

  void serverSpan(TraceAssert trace, String opName, Object type, DDSpan parentSpan = null, boolean error = false) {
    trace.span {
      if (parentSpan) {
        childOf parentSpan
      }
      operationName opName
      spanType type
      errored error
    }
  }
}
