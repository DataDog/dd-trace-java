import datadog.smoketest.AbstractServerSmokeTest

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

abstract class AbstractSpringBootWebmvcIntegrationTest extends AbstractServerSmokeTest {

  protected List<String> additionalJavaProperties() {
    []
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.uberJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(additionalJavaProperties())
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

  @Override
  List<String> expectedTelemetryDependencies() {
    ['spring-core']
  }
}
