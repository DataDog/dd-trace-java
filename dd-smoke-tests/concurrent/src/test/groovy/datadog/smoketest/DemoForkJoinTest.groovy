package datadog.smoketest

import static java.util.concurrent.TimeUnit.SECONDS

class DemoForkJoinTest extends AbstractDemoTest {
  @Override
  protected List<String> getTestArguments() {
    return ["forkJoin"]
  }

  def 'receive one expected trace for ExecutorService'() {
    when:
    waitForTrace(DEFAULT_POLL, checkTrace())

    then:
    traceCount.get() == 1

    and:
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}
