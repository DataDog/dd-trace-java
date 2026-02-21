package datadog.smoketest.concurrent

import static java.nio.charset.StandardCharsets.UTF_8
import static java.util.concurrent.TimeUnit.SECONDS

import datadog.smoketest.AbstractSmokeTest
import datadog.trace.test.agent.decoder.DecodedTrace
import java.util.function.Function

abstract class AbstractConcurrentTest extends AbstractSmokeTest {
  protected static final int TIMEOUT_SECS = 10

  @Override
  ProcessBuilder createProcessBuilder() {
    def jarPath = System.getProperty("datadog.smoketest.shadowJar.path")
    def command = new ArrayList<String>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.trace.otel.enabled=true")
    command.addAll(["-jar", jarPath])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
  }

  protected void sendScenarioSignal(String signal) {
    testedProcess.outputStream.write((signal + System.lineSeparator()).getBytes(UTF_8))
    testedProcess.outputStream.flush()
  }

  protected static Function<DecodedTrace, Boolean> checkTrace() {
    return {
      trace ->
      // Check for 'main' span
      def mainSpan = trace.spans.find {
        it.name == 'main'
      }
      if (!mainSpan) {
        return false
      }
      // Check that there are only 'main' and 'compute' spans
      def otherSpans = trace.spans.findAll {
        it.name != 'main' && it.name != 'compute'
      }
      if (!otherSpans.isEmpty()) {
        return false
      }
      // Check that every 'compute' span is in the same trace and is either a child of the 'main' span or another 'compute' span
      def computeSpans = trace.spans.findAll {
        it.name == 'compute'
      }
      if (computeSpans.isEmpty()) {
        return false
      }
      return computeSpans.every {
        if (it.traceId != mainSpan.traceId) {
          return false
        }

        return !(it.parentId != mainSpan.spanId && trace.spans.find(s -> s.spanId == it.parentId).name != 'compute')
      }
    }
  }

  protected void receivedCorrectTrace(String signal) {
    sendScenarioSignal(signal)

    waitForTrace(longPoll, checkTrace())

    assert traceCount.get() == 1
    assert testedProcess.alive
  }

  def cleanupSpec() {
    if (testedProcess?.alive) {
      sendScenarioSignal("exit")

      assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
      assert testedProcess.exitValue() == 0
    }
  }
}
