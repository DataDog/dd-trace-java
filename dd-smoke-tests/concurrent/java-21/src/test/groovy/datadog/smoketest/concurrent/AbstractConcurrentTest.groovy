package datadog.smoketest.concurrent

import datadog.smoketest.AbstractSmokeTest
import datadog.trace.test.agent.decoder.DecodedTrace

import java.util.function.Function

import static java.util.concurrent.TimeUnit.SECONDS

abstract class AbstractConcurrentTest extends AbstractSmokeTest {
  protected static final int TIMEOUT_SECS = 10
  protected abstract List<String> getTestArguments()

  @Override
  ProcessBuilder createProcessBuilder() {
    def jarPath = System.getProperty("datadog.smoketest.shadowJar.path")
    def command = new ArrayList<String>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.trace.otel.enabled=true")
    command.add("-Ddd.trace.java.lang.21.enabled=true")
    command.addAll(["-jar", jarPath])
    command.addAll(getTestArguments())

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
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

  protected void receivedCorrectTrace() {
    waitForTrace(defaultPoll, checkTrace())
    assert traceCount.get() == 1
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}
