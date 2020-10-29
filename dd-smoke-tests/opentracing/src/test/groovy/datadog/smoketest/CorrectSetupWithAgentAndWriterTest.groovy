package datadog.smoketest

import datadog.smoketest.opentracing.CorrectSetupWithAgentAndWriterApplication
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

class CorrectSetupWithAgentAndWriterTest extends AbstractSmokeTest {
  public static final int TIMEOUT_SECS = 30

  @Override
  ProcessBuilder createProcessBuilder() {
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) ["-cp", System.getProperty("datadog.smoketest.shadowJar.path")])
    command.add(CorrectSetupWithAgentAndWriterApplication.name)

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))

    return processBuilder
  }

  @Timeout(value = TIMEOUT_SECS, unit = TimeUnit.SECONDS)
  def "Application exits without error"() {
    expect:
    assert testedProcess.waitFor() == 0
  }
}
