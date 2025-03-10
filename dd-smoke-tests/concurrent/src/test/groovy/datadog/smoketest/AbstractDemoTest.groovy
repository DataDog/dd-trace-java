package datadog.smoketest

import datadog.trace.test.agent.decoder.DecodedTrace

import java.util.function.Function

abstract class AbstractDemoTest extends AbstractSmokeTest {
  protected static final int TIMEOUT_SECS = 10
  protected abstract List<String> getTestArguments()

  @Override
  ProcessBuilder createProcessBuilder() {
    def jarPath = System.getProperty("datadog.smoketest.shadowJar.path")
    def command = new ArrayList<String>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.trace.otel.enabled=true")
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
}
