package datadog.smoketest.concurrent

import static java.nio.charset.StandardCharsets.UTF_8
import static java.util.concurrent.TimeUnit.SECONDS

import datadog.smoketest.AbstractSmokeTest
import datadog.trace.test.agent.decoder.DecodedTrace
import java.util.function.Function
import spock.util.concurrent.PollingConditions

abstract class AbstractConcurrentTest extends AbstractSmokeTest {
  protected static final int TIMEOUT_SECS = 10

  // Poll every second for up to 60 s.  This is generous enough for slow CI
  // while still failing fast enough that the automatic thread dump (see
  // dumpDiagnostics) fires before any CI job-level timeout can kill us.
  // Do NOT use longPoll (21-min exponential backoff) here: even on success it
  // adds a 5-second idle gap per test case.
  protected final PollingConditions concurrentPoll =
  new PollingConditions(timeout: 60, initialDelay: 0, delay: 1, factor: 1)

  @Override
  ProcessBuilder createProcessBuilder() {
    def jarPath = System.getProperty("datadog.smoketest.shadowJar.path")
    def command = new ArrayList<String>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.trace.otel.enabled=true")
    // Log virtual thread pinning events (carrier thread stuck in synchronized).
    // On JDK 21 the DD agent instrumentation may use synchronized blocks in
    // ExecutorService.submit() advice, pinning carrier threads.  With n=10
    // Fibonacci there are 143 concurrent virtual threads; if they all pin
    // carrier threads simultaneously the ForkJoinPool can deadlock on CI
    // (few CPUs).  This flag prints a stack trace for every pinned event so
    // we can confirm the theory when the test hangs.
    command.add("-Djdk.tracePinnedThreads=full")
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

    try {
      waitForTrace(concurrentPoll, checkTrace())
    } catch (Throwable t) {
      dumpDiagnostics(signal)
      throw t
    }

    // Use >= 1 rather than == 1: a trace from the previous scenario can arrive
    // late (after setup() reset the counter) and increment traceCount before
    // this scenario's trace does, making the count 2.
    assert traceCount.get() >= 1
    assert testedProcess.alive
  }

  private void dumpDiagnostics(String signal) {
    System.out.println("=== waitForTrace timed out for signal '${signal}' ===")
    System.out.println("=== Process alive: ${testedProcess?.alive} ===")
    System.out.println("=== Requesting thread dump via SIGQUIT ===")
    try {
      // SIGQUIT triggers a JVM thread dump to stdout (captured in the log file).
      // The -Djdk.tracePinnedThreads=full JVM flag will include pinned-carrier
      // stack traces in the output if virtual thread pinning is the cause.
      new ProcessBuilder("kill", "-3", String.valueOf(testedProcess.pid()))
      .redirectErrorStream(true)
      .start()
      .waitFor(5, SECONDS)
      Thread.sleep(2000) // give the JVM time to write the dump
    } catch (Exception e) {
      System.out.println("Failed to send SIGQUIT: ${e.message}")
    }
    System.out.println("=== App process log ===")
    forEachLogLine { System.out.println(it) }
  }

  def cleanupSpec() {
    if (testedProcess?.alive) {
      sendScenarioSignal("exit")

      assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
      assert testedProcess.exitValue() == 0
    }
  }
}
