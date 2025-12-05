package datadog.smoketest

import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.FutureTask
import java.util.concurrent.RecursiveTask
import java.util.concurrent.RunnableFuture
import java.util.regex.Matcher
import java.util.regex.Pattern

import static datadog.trace.test.util.ForkedTestUtils.getMaxMemoryArgumentForFork
import static datadog.trace.test.util.ForkedTestUtils.getMinMemoryArgumentForFork
import static java.util.concurrent.TimeUnit.SECONDS

class FieldInjectionSmokeTest extends Specification {
  private static final int TIMEOUT_SECS = 20

  private static final Pattern CONTEXT_STORE_ALLOCATION =
  Pattern.compile('.*Allocated ContextStore #(\\d+) - instrumentation.target.context=(\\S+)->(\\S+)')

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  @Shared
  protected String buildDirectory = System.getProperty("datadog.smoketest.builddir")
  @Shared
  protected String shadowJarPath = System.getProperty("datadog.smoketest.agent.shadowJar.path")
  @Shared
  protected String outFilePath = "${buildDirectory}/reports/testProcess.${this.getClass().getName()}.out.log"
  @Shared
  protected String errFilePath = "${buildDirectory}/reports/testProcess.${this.getClass().getName()}.err.log"

  def "types are injected with expected fields"() {
    setup:
    // send them all over in one go to keep the test quick
    Map<String, Set<String>> testedTypesAndExpectedFields = new HashMap<>()
    testedTypesAndExpectedFields.put(ForkJoinTask.getName(),
      new HashSet<>([fieldName(ForkJoinTask)]))
    testedTypesAndExpectedFields.put(RecursiveTask.getName(),
      new HashSet<>([fieldName(ForkJoinTask)]))
    testedTypesAndExpectedFields.put(FutureTask.getName(),
      new HashSet<>([fieldName(RunnableFuture)]))
    testedTypesAndExpectedFields.put("java.util.concurrent.CompletableFuture\$UniCompletion",
      new HashSet<>([
        fieldName(ForkJoinTask),
        fieldName("java.util.concurrent.CompletableFuture\$UniCompletion")
      ]))
    String jar = System.getProperty("datadog.smoketest.fieldinjection.shadowJar.path")
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.add("${getMaxMemoryArgumentForFork()}" as String)
    command.add("${getMinMemoryArgumentForFork()}" as String)
    command.add("-javaagent:${shadowJarPath}" as String)
    command.add("-XX:ErrorFile=/tmp/hs_err_pid%p.log")
    // turn off these features as their debug output can break up our expected logging lines on IBM JVMs
    // causing random test failures (we are not testing these features here so they don't need to be on)
    command.add("-Ddd.instrumentation.telemetry.enabled=false")
    command.add("-Ddd.remote_config.enabled=false")
    command.add("-Ddd.writer.type=TraceStructureWriter")
    command.add("-Ddd.trace.debug=true")
    command.add("-jar")
    command.add(jar)
    for (String type : testedTypesAndExpectedFields.keySet()) {
      command.add(type)
    }
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))

    Path testOut = Paths.get(outFilePath)
    Path testErr = Paths.get(errFilePath)
    processBuilder.redirectOutput(testOut.toFile())
    processBuilder.redirectError(testErr.toFile())
    Process testedProcess = processBuilder.start()

    expect:
    testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    testedProcess.exitValue() == 0
    List<String> linesOut = Files.readAllLines(testOut)
    List<String> linesErr = Files.readAllLines(testErr)
    Map<String, Set<String>> foundTypesAndFields = new HashMap<>()
    Map<String, List<String>> foundTypesAndInterfaces = new HashMap<>()
    Map<String, List<String>> foundTypesAndGenericInterfaces = new HashMap<>()
    Map<String, String> storeFieldAliases = new HashMap<>()
    for (String line : linesErr) {
      System.err.println(line)
      // extract context-store allocations from tracer logging
      Matcher storeAllocation = CONTEXT_STORE_ALLOCATION.matcher(line)
      if (storeAllocation.matches()) {
        // assertions use context key while internally we use storeId,
        // so we need to record the storeId alias for each context key
        String storeId = storeAllocation.group(1)
        String keyName = storeAllocation.group(2)
        storeFieldAliases.put(fieldName(storeId), fieldName(keyName))
      }
    }
    for (String line : linesOut) {
      System.out.println(line)
      // extract structural info from test application logging
      if (line.startsWith("___FIELD___")) {
        String[] parts = line.split(":")
        parts[2] = storeFieldAliases.get(parts[2])
        foundTypesAndFields.computeIfAbsent(parts[1], { new HashSet<>() }).add(parts[2])
      } else if (line.startsWith("___INTERFACE___")) {
        String[] parts = line.split(":")
        foundTypesAndInterfaces.computeIfAbsent(parts[1], { new HashSet<>() }).add(parts[2])
      } else if (line.startsWith("___GENERIC_INTERFACE___")) {
        String[] parts = line.split(":")
        foundTypesAndGenericInterfaces.computeIfAbsent(parts[1], { new HashSet<>() }).add(parts[2])
      }
    }
    assert testedTypesAndExpectedFields == foundTypesAndFields
    // check same list of names for interfaces and generic interfaces
    assert foundTypesAndInterfaces == foundTypesAndGenericInterfaces
  }

  def fieldName(Class<?> klass) {
    return fieldName(klass.getName())
  }

  def fieldName(String klass) {
    return "__datadogContext\$" + klass.replace('.', '$')
  }
}
