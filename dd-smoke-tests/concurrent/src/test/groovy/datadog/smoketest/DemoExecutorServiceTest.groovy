package datadog.smoketest

import datadog.trace.test.agent.decoder.DecodedTrace
import spock.util.concurrent.PollingConditions

import java.util.function.Function

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

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
  }

  private static Function<DecodedTrace, Boolean> checkTrace() {
    return { trace ->
      def parentSpanCount = 0
      //      def parentSpanId = -1
      def childSpanCount = 0
      def otherSpanCount = 0

      trace.spans.each { span ->
        if (span.getName() == "ConcurrentApp.spanWrapper") {
          parentSpanCount++
          //          parentSpanId = span.getSpanId()
        }
        //        else if (parentSpanId != -1 && span.getParentId() == parentSpanId) {
        //          childSpanCount++
        //        } else {
        //          otherSpanCount++
        //        }
      }
      parentSpanCount == 1 && childSpanCount == 0 && otherSpanCount == 0
    }
  }

  def 'receive one expected trace for ExecutorService'() {
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
