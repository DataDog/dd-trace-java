package datadog.smoketest

import static java.util.concurrent.TimeUnit.SECONDS

class DemoExecutorServiceTest extends AbstractSmokeTest {
  public static final int TIMEOUT_SECS = 10

  @Override
  ProcessBuilder createProcessBuilder() {
    def jarPath = System.getProperty("datadog.smoketest.shadowJar.path")
    def command = new ArrayList<String>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.trace.otel.enabled=true")
    command.addAll(["-jar", jarPath, "executorService"])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def 'tmp'() {
    expect:
    assert true == true
  }

  def 'receive trace for ExecutorService'() {
    expect:
    waitForTraceCount(2) // one parent trace

    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}
