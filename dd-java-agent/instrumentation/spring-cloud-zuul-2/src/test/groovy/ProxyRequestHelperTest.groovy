import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.core.DDSpan
import okhttp3.HttpUrl
import okhttp3.Request
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

  public static final String PARENT_ID = "0"
  public static final String TRACE_ID = "0"
  public static final String APP_NAME = "test"
  public static final String TRACE_ID_HEADER = "X-DATADOG-TRACE-ID"
  public static final String PARENT_ID_HEADER = "X-DATADOG-PARENT-ID"
  @Override
  ConfigurableApplicationContext startServer(int port) {
    def server = new SpringApplication(TestApplication)
    def properties = new Properties()
    properties.put("spring.application.name", APP_NAME)
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
    gatewayProperties.put("zuul.routes." + APP_NAME + ".url", getAddress().toString())
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
    def url = buildProxyURL(["available"])
    def request = new Request.Builder()
      .url(url)
      .method("GET", null)
      .header(PARENT_ID_HEADER, PARENT_ID)
      .header(TRACE_ID_HEADER, TRACE_ID)
      .build()

    when:
    client.newCall(request).execute()

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
    def url = buildProxyURL(["nested", proxyPort.toString()])
    def request = new Request.Builder()
      .url(url)
      .method("GET", null)
      .header(PARENT_ID_HEADER, PARENT_ID)
      .header(TRACE_ID_HEADER, TRACE_ID)
      .build()

    when:
    client.newCall(request).execute()

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

  def "Test baggage headers to be correctly ignored through proxy"() {
    setup:
    def hsBaggageHeader = "Baggage-test"
    def ddBaggageHeader = "ot-baggage-test"
    def nonTracerHeader ="ot"

    def url = buildProxyURL(["headers"])
    def request = new Request.Builder()
      .url(url)
      .method("GET", null)
      .header(PARENT_ID_HEADER, PARENT_ID)
      .header(TRACE_ID_HEADER, TRACE_ID)
      .header(hsBaggageHeader, "shouldnotappear")
      .header(ddBaggageHeader, "shouldnotappear")
      .header(nonTracerHeader, "shouldAppear")
      .build()

    when:
    def response = client.newCall(request).execute().body()
    def receivedHeaders = new ObjectMapper().readValue(response.string(), HashMap)

    then:
    !receivedHeaders.containsKey(hsBaggageHeader)
    !receivedHeaders.containsKey(ddBaggageHeader)
    receivedHeaders.containsKey(nonTracerHeader)

    receivedHeaders.containsKey(defaultParentHeader)
    ((String) receivedHeaders.get(defaultParentHeader)) != PARENT_ID
    receivedHeaders.containsKey(defaultTraceHeader)

    where:
    defaultParentHeader = "x-datadog-parent-id"
    defaultTraceHeader = "x-datadog-parent-id"
  }

  HttpUrl buildProxyURL(List<String> path) {
    def url = new HttpUrl.Builder()
      .scheme("http")
      .host("localhost")
      .port(proxyPort)
      .addPathSegment(APP_NAME)
    path.each { seg ->
      url.addPathSegment(seg)
    }
    return url.build()
  }

  void serverSpan(TraceAssert trace, String opName, Object type, DDSpan parentSpan = null) {
    trace.span {
      if (parentSpan) {
        childOf parentSpan
      }
      operationName opName
      spanType type
    }
  }
}
