package datadog.smoketest

class DemoForkJoinTest extends AbstractDemoTest {
  protected List<String> getTestArguments() {
    return ["forkJoin"]
  }

  def 'receive one correct trace when using ForkJoin'() {
    expect:
    receivedCorrectTrace()
  }
}
