package datadog.smoketest.concurrent

class DemoMultipleConcurrenciesTest extends AbstractDemoTest {
  protected List<String> getTestArguments() {
    return ["executorService", "forkJoinPoolTask"]
  }

  def 'receive one correct trace when using multiple concurrency strategies (ExecutorService and ForkJoinPool task API)'() {
    expect:
    receivedCorrectTrace()
  }
}
