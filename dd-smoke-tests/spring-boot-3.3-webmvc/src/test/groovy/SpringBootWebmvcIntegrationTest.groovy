import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Request

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class SpringBootWebmvcIntegrationTest extends AbstractServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.uberJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.rum.enabled=true",
      "-Ddd.rum.application.id=appid",
      "-Ddd.rum.client.token=token",
      "-Ddd.rum.remote.configuration.id=12345",
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()}:includeResource,DDAgentWriter",
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPort}"
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile() {
    return File.createTempFile("trace-structure-docs", "out")
  }

  @Override
  protected Set<String> expectedTraces() {
    return [
      "\\[servlet\\.request:GET /fruits\\[spring\\.handler:FruitController\\.listFruits\\[repository\\.operation:FruitRepository\\.findAll\\[h2\\.query:.*",
      "\\[servlet\\.request:GET /fruits/\\{name}\\[spring\\.handler:FruitController\\.findOneFruit\\[repository\\.operation:FruitRepository\\.findByName\\[h2\\.query:.*",
      "\\[servlet\\.request:GET /form-response\\[spring\\.handler:FormResponseController\\.formResponse.*"
    ]
  }

  @Override
  protected Set<String> assertTraceCounts(Set<String> expected, Map<String, AtomicInteger> traceCounts) {
    List<Pattern> remaining = expected.collect { Pattern.compile(it) }.toList()
    for (def i = remaining.size() - 1; i >= 0; i--) {
      for (Map.Entry<String, AtomicInteger> entry : traceCounts.entrySet()) {
        if (entry.getValue() > 0 && remaining.get(i).matcher(entry.getKey()).matches()) {
          remaining.remove(i)
          break
        }
      }
    }
    return remaining.collect { it.pattern() }.toSet()
  }

  def "find all fruits"() {
    setup:
    String url = "http://localhost:${httpPort}/fruits"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    ["banana", "apple", "orange"].each { responseBodyStr.contains(it) }
    waitForTraceCount(1)
  }

  def "find a banana"() {
    setup:
    String url = "http://localhost:${httpPort}/fruits/banana"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    ["apple", "orange"].each { !responseBodyStr.contains(it) }
    responseBodyStr.contains("banana")
    waitForTraceCount(1)
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

  @Override
  List<String> expectedTelemetryDependencies() {
    ['spring-core']
  }
}
