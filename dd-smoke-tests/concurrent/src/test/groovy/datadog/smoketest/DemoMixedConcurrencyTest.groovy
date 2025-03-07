package datadog.smoketest

import datadog.trace.test.agent.decoder.DecodedSpan
import spock.util.concurrent.PollingConditions
import java.util.function.Function
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

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
  }

  private static Function<DecodedSpan, Boolean> checkSpanName() {
    return { span -> span.getName() == "ConcurrentApp.spanWrapper" }
  }

  def 'receive one expected trace for ExecutorService and ForkJoin'() {
    given:
    def poll = new PollingConditions(timeout: TIMEOUT_SECS)

    when:
    waitForTraceCount(1)

    then:
    waitForSpan(poll, checkSpanName())
    traceCount.get() == 1

    and:
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}
