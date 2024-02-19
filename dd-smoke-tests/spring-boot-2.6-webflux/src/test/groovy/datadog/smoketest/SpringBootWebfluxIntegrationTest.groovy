import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Request

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class SpringBootWebfluxIntegrationTest extends AbstractServerSmokeTest {

  @Override
  def inferServiceName() {
    false // will be taken from spring application.properties
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()}:includeResource:includeService,DDAgentWriter",
      "-Ddd.integration.spring-boot.enabled=true",
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
      "\\[$SERVICE_NAME:netty\\.request:GET /fruits/\\{name}\\[$SERVICE_NAME:FruitRouter\\.lambda:FruitRouter\\.lambda\\[$SERVICE_NAME:repository\\.operation:FruitRepository\\.findByName\\[h2:h2\\.query:.*",
      "\\[$SERVICE_NAME:netty\\.request:GET /fruits\\[$SERVICE_NAME:FruitRouter\\.lambda:FruitRouter\\.lambda\\[$SERVICE_NAME:repository\\.operation:FruitRepository\\.findAll\\[h2:h2.query:.*",
      Pattern.quote("[$SERVICE_NAME:netty.request:GET /hello[$SERVICE_NAME:WebController.hello:WebController.hello]]")
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

  def "hello world requestmapping"() {
    setup:
    String url = "http://localhost:${httpPort}/hello"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("Hello world")
    waitForTraceCount(1)
  }
}
