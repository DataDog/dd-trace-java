package datadog.smoketest

import datadog.smoketest.opentracing.OTWithAgentApplication
import datadog.smoketest.opentracing.OTWithoutAgentApplication
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

abstract class OpenTracingSmokeTest extends AbstractSmokeTest {
  // Estimate for the amount of time instrumentation, plus request, plus some extra
  public static final int TIMEOUT_SECS = 30
  // Timeout for individual requests
  public static final int REQUEST_TIMEOUT = 5

  List<String> baseCommand() {
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) ["-cp", System.getProperty("datadog.smoketest.shadowJar.path")])
    return command
  }

  // TODO: once java7 support is dropped use waitFor() with timeout call added in java8
  // instead of timeout on test
  @Timeout(value = TIMEOUT_SECS, unit = TimeUnit.SECONDS)
  def "Receive traces in agent"() {
    when:
    def conditions = new PollingConditions(timeout: TIMEOUT_SECS, initialDelay: 1, factor: 1)

    then:
    conditions.eventually {
      assert traceRequests.poll(REQUEST_TIMEOUT, TimeUnit.SECONDS)?.getHeader("X-Datadog-Trace-Count")?.size() > 0
    }
    assert serverProcess.waitFor() == 0
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
