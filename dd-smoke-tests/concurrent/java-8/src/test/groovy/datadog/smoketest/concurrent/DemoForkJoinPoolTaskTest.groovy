package datadog.smoketest.concurrent

class DemoForkJoinPoolTaskTest extends AbstractDemoTest {
  protected List<String> getTestArguments() {
    return ["forkJoinPoolTask"]
  }

  def 'receive one correct trace when using ForkJoinPool task API'() {
    expect:
    receivedCorrectTrace()
  }
}
