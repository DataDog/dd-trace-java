package datadog.smoketest.concurrent

class DemoForkJoinPoolExternalClientTest extends AbstractDemoTest {
  protected List<String> getTestArguments() {
    return ["forkJoinPoolExternalClient"]
  }

  def 'receive one correct trace when using ForkJoinPool external client API'() {
    expect:
    receivedCorrectTrace()
  }
}
