import okhttp3.Request

class SpringBootWebmvcRumInjectionTest extends AbstractSpringBootWebmvcIntegrationTest {

  @Override
  protected List<String> additionalJavaProperties() {
    [
      "-Ddd.rum.enabled=true",
      "-Ddd.rum.application.id=appid",
      "-Ddd.rum.client.token=token",
      "-Ddd.rum.remote.configuration.id=12345"
    ]
  }

  @Override
  protected Set<String> expectedTraces() {
    [
      "\\[servlet\\.request:GET /form-response\\[spring\\.handler:FormResponseController\\.formResponse.*"
    ]
  }

  def "inject RUM without corrupting a Thymeleaf form response"() {
    setup:
    String url = "http://localhost:${httpPort}/form-response"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.code() == 200
    response.header("x-datadog-rum-injected") == "1"
    def responseBodyStr = response.body().string()
    responseBodyStr.contains('const returnUrl = "https:\\/\\/app.example.test\\/flows\\/complete?request=request-123";')
    responseBodyStr.contains('window.formResponse = { returnUrl: returnUrl };')
    responseBodyStr.contains('onload="document.getElementById(\'response-form\').submit()"')
    responseBodyStr.contains('action="https://provider.example.test/flows/continue"')
    responseBodyStr.contains('value="request-123"')
    responseBodyStr.count("DD_RUM.init(") == 1
    responseBodyStr.contains("https://www.datadoghq-browser-agent.com")
    responseBodyStr.count("</head>") == 1
    responseBodyStr.indexOf("DD_RUM.init(") < responseBodyStr.indexOf("</head>")
    responseBodyStr.trim().endsWith("</html>")
    waitForTraceCount(1)
  }
}
