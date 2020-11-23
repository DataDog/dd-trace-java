package datadog.smoketest

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class Slf4jMDCSmokeTest extends Specification {
  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  @Shared
  protected String buildDirectory = System.getProperty("datadog.smoketest.builddir")
  @Shared
  protected String shadowJarPath = System.getProperty("datadog.smoketest.agent.shadowJar.path")

  private String logPrefix = "DEBUG datadog.smoketest.slf4jmdc.Slf4jMDCApp - Iteration|"

  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  def "trace and span is injected into logging mdc"() {
    setup:
    List<Integer> traceDepths = (1..10).toList()
    String codeJar = System.getProperty("datadog.smoketest.slf4jmdc.shadowJar.path")
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.add("-javaagent:${shadowJarPath}" as String)
    command.add("-Ddd.writer.type=TraceStructureWriter")
    command.add("-Ddd.trace.debug=true")
    command.add("-Ddd.logs.injection=true")
    command.add("-Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG")
    command.add("-Dorg.slf4j.simpleLogger.showThreadName=false")
    command.add("-jar")
    command.add(codeJar)
    traceDepths.each {
      command.add(it as String)
    }
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))
    Path testOutput = Files.createTempFile("output", "tmp")
    processBuilder.redirectError(testOutput.toFile())

    when:
    Process testedProcess = processBuilder.start()

    then:
    testedProcess.waitFor() == 0

    when:
    List<String> lines = Files.readAllLines(testOutput, Charset.defaultCharset())
    Map<String, List<TraceAndSpan>> foundTracesAndSpans = new HashMap<>()
    List<TraceAndSpan> current = null
    for (String line : lines) {
      if (line.startsWith(logPrefix)) {
        def parts = line.split(Pattern.quote("|"))
        if (parts[1] == "1") {
          current = new ArrayList<>()
          foundTracesAndSpans.put(parts[2], current)
        }
        current.add(new TraceAndSpan(parts[4], parts[6], parts[8], parts[10]))
      }
    }

    then:
    foundTracesAndSpans.size() == traceDepths.size()
    traceDepths.each {
      def traceAndSpans = foundTracesAndSpans.get(it as String)
      assert traceAndSpans.size() == it * 2
      def traceId = traceAndSpans.head().tracerTraceId
      traceAndSpans.each {tas ->
        assert tas.tracerTraceId == traceId
        assert tas.tracerTraceId == tas.mdcTraceId
        assert tas.tracerSpanId == tas.mdcSpanId
      }
    }
  }

  private static final class TraceAndSpan {
    public final String tracerTraceId
    public final String tracerSpanId
    public final String mdcTraceId
    public final String mdcSpanId

    TraceAndSpan(String tracerTraceId, String tracerSpanId, String mdcTraceId, String mdcSpanId) {
      this.tracerTraceId = tracerTraceId
      this.tracerSpanId = tracerSpanId
      this.mdcTraceId = mdcTraceId
      this.mdcSpanId = mdcSpanId
    }
  }
}
