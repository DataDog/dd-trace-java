package datadog.smoketest

import spock.util.concurrent.PollingConditions

import static java.util.concurrent.TimeUnit.SECONDS

abstract class CliApplicationSmokeTest extends AbstractSmokeTest {
  // Estimate for the amount of time instrumentation, plus request, plus some extra
  private static final int TIMEOUT_SECS = 60

  // Timeout for individual requests
  public static final int REQUEST_TIMEOUT = 5

  @Override
  ProcessBuilder createProcessBuilder() {
    String cliShadowJar = System.getProperty("datadog.smoketest.cli.shadowJar.path")
    assert new File(cliShadowJar).isFile()

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

  def "Receive traces in agent and CLI exits"() {
    when:
    def conditions = new PollingConditions(timeout: TIMEOUT_SECS, initialDelay: 1, factor: 1)

    then:
    waitForTraceCount(1, conditions)
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}

class BasicCLITest extends CliApplicationSmokeTest {
}

class NoKeystoreTest extends CliApplicationSmokeTest {
  List<String> additionalArguments() {
    return [
      "-Djava.security.properties=${buildDirectory}/resources/main/remove.tls.properties".toString()
    ]
  }
}

class BootstrapTest extends CliApplicationSmokeTest {
  List<String> additionalArguments() {
    return ["-Xbootclasspath/a:${shadowJarPath}".toString()]
  }
}
