package datadog.smoketest

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.FutureTask
import java.util.concurrent.RecursiveTask
import java.util.concurrent.RunnableFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

import static datadog.trace.test.util.ForkedTestUtils.getMaxMemoryArgumentForFork
import static datadog.trace.test.util.ForkedTestUtils.getMinMemoryArgumentForFork

class FieldInjectionSmokeTest extends Specification {

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
  protected String logFilePath = "${buildDirectory}/reports/testProcess.${this.getClass().getName()}.log"

  @Timeout(value = 20, unit = TimeUnit.SECONDS)
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

    Path testOutput = Paths.get(logFilePath)
    processBuilder.redirectErrorStream(true)
    processBuilder.redirectOutput(testOutput.toFile())
    Process testedProcess = processBuilder.start()

    expect:
    testedProcess.waitFor() == 0
    List<String> lines = Files.readAllLines(testOutput)
    Map<String, Set<String>> foundTypesAndFields = new HashMap<>()
    Map<String, List<String>> foundTypesAndInterfaces = new HashMap<>()
    Map<String, List<String>> foundTypesAndGenericInterfaces = new HashMap<>()
    Map<String, String> storeFieldAliases = new HashMap<>()
    for (String line : lines) {
      System.out.println(line)
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
      } else {
        Matcher storeAllocation = CONTEXT_STORE_ALLOCATION.matcher(line)
        if (storeAllocation.matches()) {
          // assertions use context key while internally we use storeId,
          // so we need to record the storeId alias for each context key
          String storeId = storeAllocation.group(1)
          String keyName = storeAllocation.group(2)
          storeFieldAliases.put(fieldName(storeId), fieldName(keyName))
        }
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
