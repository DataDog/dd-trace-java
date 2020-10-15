package datadog.smoketest

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.FutureTask
import java.util.concurrent.RecursiveTask
import java.util.concurrent.RunnableFuture
import java.util.concurrent.TimeUnit

class FieldInjectionSmokeTest extends Specification {

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  @Shared
  protected String buildDirectory = System.getProperty("datadog.smoketest.builddir")
  @Shared
  protected String shadowJarPath = System.getProperty("datadog.smoketest.agent.shadowJar.path")

  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  def "types are injected with expected fields"() {
    setup:
    // send them all over in one go to keep the test quick
    Map<String, Set<String>> testedTypesAndExpectedFields = new HashMap<>()
    testedTypesAndExpectedFields.put(ForkJoinTask.getName(),
      new HashSet<>([fieldName(ForkJoinTask)]))
    testedTypesAndExpectedFields.put(RecursiveTask.getName(),
      new HashSet<>([fieldName(ForkJoinTask)]))
    // TODO - want rid of the Runnable field
    testedTypesAndExpectedFields.put(FutureTask.getName(),
      new HashSet<>([fieldName(RunnableFuture), fieldName(Runnable)]))
    String jar = System.getProperty("datadog.smoketest.fieldinjection.shadowJar.path")
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.add("-javaagent:${shadowJarPath}" as String)
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

    Path testOutput = Files.createTempFile("output", "tmp")
    processBuilder.redirectError(testOutput.toFile())
    Process testedProcess = processBuilder.start()
    expect:
    testedProcess.waitFor() == 0
    List<String> lines = Files.readAllLines(testOutput)
    Map<String, Set<String>> foundTypesAndFields = new HashMap<>()
    for (String line : lines) {
      if (line.startsWith("___FIELD___")) {
        String[] parts = line.split(":")
        foundTypesAndFields.compute(parts[1],
          { String type, Set<String> fields ->
            if (null == fields) {
              fields = new HashSet<>()
            }
            return fields
          }).add(parts[2])
      }
    }
    assert testedTypesAndExpectedFields == foundTypesAndFields
  }


  def fieldName(Class<?> klass) {
    return "__datadogContext\$" + klass.getName().replace('.', '$')
  }
}
