package datadog.smoketest

import static java.util.concurrent.TimeUnit.SECONDS

class Java9ModulesSmokeTest extends AbstractSmokeTest {
  // Estimate for the amount of time instrumentation plus some extra
  private static final int TIMEOUT_SECS = 30

  @Override
  ProcessBuilder createProcessBuilder() {
    String imageDir = System.getProperty("datadog.smoketest.module.image")

    assert imageDir != null

    List<String> command = new ArrayList<>()
    command.add(imageDir + "/bin/java")
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) ["-m", "datadog.smoketest.moduleapp/datadog.smoketest.moduleapp.ModuleApplication"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def "Module application runs correctly"() {
    expect:
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}
