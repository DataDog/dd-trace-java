import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Request

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class SpringBootWebmvcIntegrationTest extends AbstractServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
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
      "\\[servlet\\.request:GET /hello/not-found\\[servlet\\.error:GET /error\\[spring\\.handler:BasicErrorController\\.error\\]\\]\\[servlet\\.response:HttpServletResponse\\.sendError\\]\\[spring\\.handler:ServerController\\.notFound\\]\\]",
      "\\[servlet\\.request:GET /hello/not-here\\[spring\\.handler:ServerController\\.notHere\\]\\].*",
      "\\[servlet\\.request:404\\[servlet\\.error:GET /error\\[spring\\.handler:BasicErrorController\\.error\\]\\]\\[spring\\.handler:ResourceHttpRequestHandler\\.handleRequest\\[servlet\\.response:HttpServletResponse\\.sendError\\]\\].*",
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

  def "not found"() {
    setup:
    String url = "http://localhost:${httpPort}/hello/not-found"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.code() == 404
    waitForTraceCount(1)
  }

  def "not here"() {
    setup:
    String url = "http://localhost:${httpPort}/hello/not-here"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.code() == 404
    waitForTraceCount(1)
  }

  def "not existing"() {
    setup:
    String url = "http://localhost:${httpPort}/hello/not-existing"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.code() == 404
    waitForTraceCount(1)
  }
}
