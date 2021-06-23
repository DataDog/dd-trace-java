package datadog.smoketest

class SampleTraceSmokeTest extends AbstractSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    // tests tracer as a jar sending sample traces instead of a javaagent
    command.addAll((String[]) [
      "-Ddd.trace.agent.port=${server.address.port}",
      '-jar',
      "${shadowJarPath}",
      'sampleTrace',
      '-c',
      '10',
      '-i',
      '0.1'
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def 'sample traces are sent'() {
    when:
    waitForTraceCount(10)
    testedProcess.waitFor()

    then:
    testedProcess.exitValue() == 0
    traceCount.get() == 10
  }
}
