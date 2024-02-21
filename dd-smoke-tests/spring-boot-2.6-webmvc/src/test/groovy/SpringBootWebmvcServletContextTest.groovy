import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Request

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class SpringBootWebmvcServletContextTest extends AbstractServerSmokeTest {

  static final SERVLET_PATH = "srv"

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()}:includeResource,DDAgentWriter",
      "-Dserver.servlet.context-path=/$SERVLET_PATH",
      "-Ddd.trace.PrependServletPathToResourceNameRule.enabled=true",
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
      "\\[servlet\\.request:GET /$SERVLET_PATH/fruits\\[spring\\.handler:FruitController\\.listFruits\\[repository\\.operation:FruitRepository\\.findAll\\[h2\\.query:.*",
      "\\[servlet\\.request:GET /$SERVLET_PATH/fruits/\\{name}\\[spring\\.handler:FruitController\\.findOneFruit\\[repository\\.operation:FruitRepository\\.findByName\\[h2\\.query:.*"
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
    String url = "http://localhost:${httpPort}/$SERVLET_PATH/fruits"

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
    String url = "http://localhost:${httpPort}/$SERVLET_PATH/fruits/banana"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    ["apple", "orange"].each { !responseBodyStr.contains(it) }
    responseBodyStr.contains("banana")
    waitForTraceCount(1)
  }
}
