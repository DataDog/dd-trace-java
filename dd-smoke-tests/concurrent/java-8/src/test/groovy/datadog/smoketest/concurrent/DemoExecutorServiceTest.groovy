package datadog.smoketest.concurrent

class DemoExecutorServiceTest extends AbstractDemoTest {
  protected List<String> getTestArguments() {
    return ["executorService"]
  }

  def 'receive one correct trace when using ExecutorService'() {
    expect:
    receivedCorrectTrace()
  }
}
