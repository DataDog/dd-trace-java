package datadog.smoketest

import static java.util.concurrent.TimeUnit.SECONDS

class DemoMixedConcurrencyTest extends AbstractDemoTest {
  @Override
  protected List<String> getTestArguments() {
    return ["executorService", "forkJoin"]
  }

  def 'receive one expected trace for ExecutorService'() {
    when:
    waitForTrace(defaultPoll, checkTrace())

    then:
    traceCount.get() == 1

    and:
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}
