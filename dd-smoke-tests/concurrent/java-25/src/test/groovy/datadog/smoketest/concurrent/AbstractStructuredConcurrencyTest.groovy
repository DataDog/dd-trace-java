package datadog.smoketest.concurrent

import datadog.environment.EnvironmentVariables
import datadog.smoketest.AbstractSmokeTest
import datadog.trace.test.agent.decoder.DecodedSpan
import datadog.trace.test.agent.decoder.DecodedTrace

import java.nio.file.Paths
import java.util.function.Function

import static java.util.concurrent.TimeUnit.SECONDS

abstract class AbstractStructuredConcurrencyTest extends AbstractSmokeTest {
  protected static final int TIMEOUT_SECS = 10
  protected abstract String testCaseName()

  @Override
  ProcessBuilder createProcessBuilder() {
    def jarPath = System.getProperty("datadog.smoketest.shadowJar.path")
    def command = new ArrayList<String>()
    command.add(Paths.get(EnvironmentVariables.get("JAVA_25_HOME"), "bin", "java").toString())
    command.addAll(defaultJavaProperties)
    command.add("--enable-preview")
    command.add("-Ddd.trace.otel.enabled=true")
    command.addAll(["-jar", jarPath])
    command.add(testCaseName())

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
  }

  protected void receivedCorrectTrace() {
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
    waitForTrace(defaultPoll, checkTrace())
    assert traceCount.get() == 1
  }

  protected abstract Function<DecodedTrace, Boolean> checkTrace()

  protected DecodedSpan findRootSpan(DecodedTrace trace, String resource) {
    return trace.spans.find { it.resource == resource && it.parentId == 0 }
  }

  protected DecodedSpan findChildSpan(DecodedTrace trace, String resource, long parentSpanId) {
    return trace.spans.find { it.resource == resource && it.parentId == parentSpanId }
  }
}
