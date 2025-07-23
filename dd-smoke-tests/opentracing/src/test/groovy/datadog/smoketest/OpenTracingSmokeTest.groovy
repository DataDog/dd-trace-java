package datadog.smoketest

import datadog.smoketest.opentracing.OTWithAgentApplication
import datadog.smoketest.opentracing.OTWithoutAgentApplication
import spock.util.concurrent.PollingConditions

import static java.util.concurrent.TimeUnit.SECONDS

abstract class OpenTracingSmokeTest extends AbstractSmokeTest {
  // Estimate for the amount of time instrumentation, plus request, plus some extra
  public static final int TIMEOUT_SECS = 30

  List<String> baseCommand() {
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) ["-cp", System.getProperty("datadog.smoketest.shadowJar.path")])
    return command
  }

  def "Receive traces in agent"() {
    when:
    def conditions = new PollingConditions(timeout: TIMEOUT_SECS, initialDelay: 1, factor: 1)

    then:
    waitForTraceCount(1, conditions)
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}

class OTWithoutAgentTest extends OpenTracingSmokeTest {
  @Override
  ProcessBuilder createProcessBuilder() {
    List<String> command = baseCommand()
    command.removeAll { it.startsWith("-javaagent") }
    command.add(OTWithoutAgentApplication.name)

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }
}

class OTWithAgentTest extends OpenTracingSmokeTest {
  @Override
  ProcessBuilder createProcessBuilder() {
    List<String> command = baseCommand()
    command.add(OTWithAgentApplication.name)

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }
}

class OTWithAgentAssertionErrorTest extends OpenTracingSmokeTest {
  @Override
  ProcessBuilder createProcessBuilder() {
    List<String> command = baseCommand()
    command.add(OTWithAgentApplication.name)
    command.add("true")

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }
}
