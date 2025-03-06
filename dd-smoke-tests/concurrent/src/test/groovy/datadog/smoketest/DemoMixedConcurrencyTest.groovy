package datadog.smoketest

import static java.util.concurrent.TimeUnit.SECONDS

class DemoMixedConcurrencyTest extends AbstractSmokeTest {
  public static final int TIMEOUT_SECS = 10

  @Override
  ProcessBuilder createProcessBuilder() {
    def jarPath = System.getProperty("datadog.smoketest.shadowJar.path")
    def command = new ArrayList<String>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.trace.otel.enabled=true")
    command.addAll(["-jar", jarPath, "executorService", "forkJoin"])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def 'tmp'() {
    expect:
    assert true == true
  }

  def 'receive trace for ExecutorService and ForkJoin'() {
    expect:
    waitForTraceCount(1) // one parent trace
    traceCount.get() == 1

    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}
