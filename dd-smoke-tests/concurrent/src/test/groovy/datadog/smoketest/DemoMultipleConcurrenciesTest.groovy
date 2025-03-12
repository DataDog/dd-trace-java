package datadog.smoketest

class DemoMultipleConcurrenciesTest extends AbstractDemoTest {
  protected List<String> getTestArguments() {
    return ["executorService", "forkJoin"]
  }

  def 'receive one correct trace when using multiple concurrency strategies (ExecutorService and ForkJoin)'() {
    expect:
    receivedCorrectTrace()
  }
}
