package datadog.smoketest

import datadog.trace.test.agent.decoder.DecodedTrace
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

  private static Function<DecodedTrace, Boolean> checkTrace() {
    return {
      trace ->
      // Get root span
      def rootSpan = trace.spans.find { it.name == 'main' }
      if (!rootSpan) {
        return false
      }
      // Check that there are only 'main' and 'compute' spans
      def otherSpans = trace.spans.findAll { it.name != 'main' && it.name != 'compute' }
      if (!otherSpans.isEmpty()) {
        return false
      }
      // Check every 'compute' span is either a child of the root span or another 'compute' span
      def computeSpans = trace.spans.findAll { it.name == 'compute' }
      if (computeSpans.isEmpty()) {
        return false
      }
      return computeSpans.every {
        // Check same trace
        if (it.traceId != rootSpan.traceId) {
          return false
        }
        // Check parent
        if (it.parentId != rootSpan.spanId && trace.spans.find(s -> s.spanId == it.parentId).name != 'compute') {
          return false
        }
        return true
      }
    }
  }

  def 'receive one expected trace for ExecutorService and ForkJoin'() {
    given:
    def poll = new PollingConditions(timeout: TIMEOUT_SECS)

    when:
    waitForTraceCount(1)

    then:
    waitForTrace(poll, checkTrace())
    traceCount.get() == 1

    and:
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}
