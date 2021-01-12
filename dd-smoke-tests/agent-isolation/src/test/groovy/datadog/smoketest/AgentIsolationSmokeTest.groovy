package datadog.smoketest

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

import static datadog.trace.test.util.ForkedTestUtils.getMaxMemoryArgumentForFork
import static datadog.trace.test.util.ForkedTestUtils.getMinMemoryArgumentForFork

class AgentIsolationSmokeTest extends Specification {

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  @Shared
  protected String buildDirectory = System.getProperty("datadog.smoketest.builddir")
  @Shared
  protected String shadowJarPath = System.getProperty("datadog.smoketest.agent.shadowJar.path")

  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  def "other agents can safely call Class.getSimpleName() on loaded instrumentation classes"() {
    setup:
    // force transformation of instrumented types
    def triggerTypes = ["org.apache.http.impl.client.MinimalHttpClient", ForkJoinTask.getName(), FutureTask.getName()]
    String app = System.getProperty("datadog.smoketest.agentisolation.appJar.path")
    String agent = System.getProperty("datadog.smoketest.agentisolation.agentJar.path")
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.add("${getMaxMemoryArgumentForFork()}" as String)
    command.add("${getMinMemoryArgumentForFork()}" as String)
    command.add("-javaagent:${shadowJarPath}" as String)
    command.add("-javaagent:${agent}=${triggerTypes.join(",")}" as String)
    command.add("-XX:ErrorFile=/tmp/hs_err_pid%p.log")
    command.add("-Ddd.writer.type=TraceStructureWriter")
    command.add("-Ddd.trace.debug=true")
    command.add("-jar")
    command.add(app)
    for (String type : triggerTypes) {
      command.add(type)
    }
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))

    Path testOutput = Files.createTempFile("output", "tmp")
    processBuilder.redirectError(testOutput.toFile())
    Process testedProcess = processBuilder.start()

    expect:
    testedProcess.waitFor() == 0
    List<String> lines = Files.readAllLines(testOutput)

    Set<String> errors = new HashSet<>()
    for (String line : lines) {
      if (line.startsWith("___ERROR____")) {
        String[] parts = line.split(":")
        errors.add(parts[1])
      }
    }
    assert errors.isEmpty()
  }
}
