package datadog.smoketest

import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

abstract class CliApplicationSmokeTest extends AbstractSmokeTest {
  // Estimate for the amount of time instrumentation, plus request, plus some extra
  private static final int TIMEOUT_SECS = 60

  // Timeout for individual requests
  public static final int REQUEST_TIMEOUT = 5

  @Override
  ProcessBuilder createProcessBuilder() {
    String cliShadowJar = System.getProperty("datadog.smoketest.cli.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(additionalArguments())
    command.addAll((String[]) ["-jar", cliShadowJar])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))

    return processBuilder
  }

  List<String> additionalArguments() {
    return Collections.emptyList()
  }

  // TODO: once java7 support is dropped use waitFor() with timeout call added in java8
  // instead of timeout on test
  @Timeout(value = TIMEOUT_SECS, unit = TimeUnit.SECONDS)
  def "Receive traces in agent and CLI exits"() {
    when:
    def conditions = new PollingConditions(timeout: TIMEOUT_SECS, initialDelay: 1, factor: 1)

    then:
    conditions.eventually {
      assert traceRequests.poll(REQUEST_TIMEOUT, TimeUnit.SECONDS)?.getHeader("X-Datadog-Trace-Count")?.size() > 0
    }
    assert testedProcess.waitFor() == 0
  }
}

class BasicCLITest extends CliApplicationSmokeTest {

}

class NoKeystoreTest extends CliApplicationSmokeTest {
  List<String> additionalArguments() {
    return ["-Djava.security.properties=${buildDirectory}/resources/main/remove.tls.properties".toString()]
  }
}
